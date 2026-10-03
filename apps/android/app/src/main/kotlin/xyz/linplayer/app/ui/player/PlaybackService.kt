package xyz.linplayer.app.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.MainActivity
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.ui.pages.args

/**
 * 前台服务 + MediaSession + 音频焦点(U1.21 / U1.22 / U1.23)。
 *
 * ★ mpv 控制转给核心层;选择 ExoPlayer 时使用播放页持有的同一个实例。
 *   用 `MediaSessionCompat` + `MediaStyle` 通知,不额外实现 Player 适配器。
 *   接一个 `SimpleBasePlayer` 适配器只是为了让 media3 的通知帮我们画一遍,
 *   代价是要把 mpv 的状态映射成 Player 的 20 多个方法 —— 那是一层纯翻译的债。
 *
 * ★ Android 14 起 `foregroundServiceType` 必填(清单里写了 `mediaPlayback`),
 *   且 `startForeground` 必须在 5 秒内调,否则 ANR。所以它在 `onStartCommand` 的第一行。
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var position = 0.0
    private var paused = true
    private var stopping = false
    private var volume = 100.0
    private var duckedVolume: Double? = null
    private var resumeOnGain = false
    private var focusHeld = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false; resumeOnGain = false
                restoreVolume()
                send("player.setPause", "paused" to true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusHeld = false
                resumeOnGain = !paused
                send("player.setPause", "paused" to true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (duckedVolume == null) {
                    duckedVolume = volume
                    send("player.setVolume", "volume" to volume * .3)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                restoreVolume()
                if (resumeOnGain) send("player.setPause", "paused" to false)
                resumeOnGain = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSessionCompat(this, "LinPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { requestFocus(); send("player.setPause", "paused" to !focusHeld) }
                override fun onPause() { resumeOnGain = false; send("player.setPause", "paused" to true) }
                override fun onSeekTo(pos: Long) = send("player.seek", "pos" to pos / 1000.0)
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }

        // ExoPlayer 的状态不经过核心事件;在主线程回读当前内核,后台也保持上报。
        scope.launch {
            var ticks = 0
            while (!stopping) {
                val app = playbackApp ?: break
                val exo = externalPlayer
                val o = if (exo == null) runCatching { app.call("player.status").obj() }.getOrNull() else null
                // 内核尚未准备时不把空状态当作正在播放,也不上报零进度。
                val ready = exo?.let { it.playbackState != androidx.media3.common.Player.STATE_IDLE }
                    ?: (o != null && ((o.dbl("duration") ?: 0.0) > 0 || (o.dbl("position") ?: 0.0) > 0))
                if (!ready) { delay(500); continue }
                val wasPaused = paused
                position = exo?.currentPosition?.div(1000.0) ?: o.dbl("position") ?: 0.0
                paused = exo?.let { !it.playWhenReady } ?: o.bool("paused")
                volume = exo?.volume?.times(100.0) ?: o.dbl("volume") ?: volume
                if (wasPaused && !paused && !focusHeld) requestFocus()
                if (paused) releaseWake() else acquireWake()
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP
                        )
                        .setState(
                            if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                            (position * 1000).toLong(), if (paused) 0f else 1f,
                        ).build()
                )
                notify(mediaTitle, paused)
                if (((!paused && ++ticks % 20 == 0) || (!wasPaused && paused)) && position > 0) runCatching {
                    app.call("emby.reportProgress", args("pos" to position, "paused" to paused))
                }
                delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★ 5 秒内必须调,否则 ANR。放在第一行,别排在任何 IO 后面
        startForeground(NOTI_ID, buildNotification(mediaTitle, paused))
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
            ACTION_PAUSE -> { resumeOnGain = false; send("player.setPause", "paused" to true); return START_NOT_STICKY }
            ACTION_PLAY -> { requestFocus(); send("player.setPause", "paused" to !focusHeld); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        abandonFocus()
        session.isActive = false
        session.release()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTI_ID)
        playbackApp = null; externalPlayer = null
        super.onDestroy()
    }

    /**
     * 音频焦点(U1.23)。
     *
     * ★ **duck 走降 mpv 音量而不是暂停** —— 导航提示音只有两三秒,
     *   为它暂停再恢复会打断观看节奏;而永久丢失(别的 App 开始播)才暂停。
     */
    private fun requestFocus() {
        if (focusHeld) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) send("player.setPause", "paused" to true)
    }

    private fun abandonFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        focusHeld = false
    }

    /** 后台播放期间保住 CPU。**屏幕常亮是 Activity 的事**(FLAG_KEEP_SCREEN_ON),不在这。 */
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinPlayer::playback").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun send(cmd: String, vararg pairs: Pair<String, Any>) {
        externalPlayer?.let { exo ->
            when (cmd) {
                "player.setPause" -> exo.playWhenReady = !(pairs.first().second as Boolean)
                "player.seek" -> exo.seekTo(((pairs.first().second as Number).toDouble() * 1000).toLong())
                "player.setVolume" -> exo.volume = ((pairs.first().second as Number).toFloat() / 100).coerceIn(0f, 1f)
            }
            return
        }
        scope.launch {
            runCatching {
                playbackApp?.call(cmd, JsonObject(pairs.associate { (k, v) ->
                    k to when (v) {
                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                    }
                }))
            }
        }
    }

    private fun restoreVolume() {
        duckedVolume?.let { send("player.setVolume", "volume" to it) }
        duckedVolume = null
    }

    private fun stopPlayback() {
        if (stopping) return
        stopping = true
        val app = playbackApp
        val pos = externalPlayer?.currentPosition?.div(1000.0) ?: position
        externalPlayer?.stop()
        scope.launch {
            try { runCatching { app?.call("player.stopPlayback", args("pos" to pos)) } }
            finally { stopSelf() }
        }
    }

    private fun notify(title: String, paused: Boolean) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTI_ID, buildNotification(title, paused))

    private fun buildNotification(title: String, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(if (paused) "已暂停" else "正在播放")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(!paused)
            .addAction(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "播放" else "暂停", action(if (paused) ACTION_PLAY else ACTION_PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, PlaybackService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "播放控制", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTI_ID = 1001
        private const val ACTION_PLAY = "playback.play"
        private const val ACTION_PAUSE = "playback.pause"
        private const val ACTION_STOP = "playback.stop"
        private var playbackApp: AppState? = null
        private var externalPlayer: ExoPlayer? = null
        private var mediaTitle = "LinPlayer"

        fun start(ctx: Context, app: AppState, exo: ExoPlayer?, title: String) {
            playbackApp = app; externalPlayer = exo; mediaTitle = title
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
            playbackApp = null; externalPlayer = null
        }
    }
}
