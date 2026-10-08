package xyz.linplayer.app.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.linplayer.app.core.Logs

/** 只记录可观察症状，不能据此认定网络/解码故障或触发换核。 */
internal enum class PlaybackDiagnostic { READY, FIRST_FRAME, FIRST_FRAME_MISSING, WAITING_DATA, BUFFERED_STALL }

internal data class PlaybackHealthSample(
    val state: Int, val positionMs: Long, val bufferedPositionMs: Long,
    val speed: Float, val loading: Boolean, val hasVideo: Boolean,
    val wantsPlayback: Boolean, val suppressed: Boolean, val active: Boolean,
    val durationMs: Long,
)

/** 单媒体诊断；等待数据与已有缓冲不动分别计时，用户/生命周期操作清空观察窗口。 */
internal class PlaybackHealthMonitor {
    private var startedAt: Long? = null
    private var readyReported = false
    private var firstFrameReported = false
    private var waiting: PlaybackDiagnostic? = null
    private var waitingAt = 0L
    private var baselinePosition = 0L
    private var baselineSpeed = 1f
    private var warningReported = false

    fun begin(nowMs: Long) {
        startedAt = nowMs
        readyReported = false
        firstFrameReported = false
        interrupt()
    }

    fun end() { startedAt = null; interrupt() }
    fun interrupt() { waiting = null; warningReported = false }

    fun ready(nowMs: Long): Long? {
        val start = startedAt ?: return null
        if (readyReported) return null
        readyReported = true
        return nowMs - start
    }

    fun firstFrame(nowMs: Long): Long? {
        val start = startedAt ?: return null
        if (firstFrameReported) return null
        firstFrameReported = true
        interrupt()
        return nowMs - start
    }

    /** 连续满足诊断门控才累计；返回值仅在本次异常窗口首次超过阈值时产生。 */
    fun sample(nowMs: Long, s: PlaybackHealthSample): PlaybackDiagnostic? {
        val nearEnd = s.durationMs > 0 && s.durationMs - s.positionMs < 2_000
        if (startedAt == null || !s.active || !s.wantsPlayback || s.suppressed || nearEnd ||
            !s.speed.isFinite() || s.speed <= 0 || s.positionMs < 0) {
            interrupt()
            return null
        }
        val aheadMs = (s.bufferedPositionMs - s.positionMs).coerceAtLeast(0)
        val kind = when {
            s.state == Player.STATE_READY && s.hasVideo && !firstFrameReported -> PlaybackDiagnostic.FIRST_FRAME_MISSING
            s.state == Player.STATE_BUFFERING -> if (aheadMs / s.speed >= 7_000 || (!s.loading && aheadMs > 0))
                PlaybackDiagnostic.BUFFERED_STALL else PlaybackDiagnostic.WAITING_DATA
            else -> null
        }
        if (kind == null) { interrupt(); return null }
        val moved = kind != PlaybackDiagnostic.FIRST_FRAME_MISSING &&
            (s.positionMs < baselinePosition || s.positionMs - baselinePosition >= 250)
        if (kind != waiting || s.speed != baselineSpeed || moved) {
            waiting = kind
            waitingAt = nowMs
            baselinePosition = s.positionMs
            baselineSpeed = s.speed
            warningReported = false
        }
        val thresholdMs = if (kind == PlaybackDiagnostic.FIRST_FRAME_MISSING) 6_000 else 12_000
        if (warningReported || nowMs - waitingAt < thresholdMs) return null
        warningReported = true
        return kind
    }
}

/** 在播放器 looper 上接收事件/采样；日志回调只传白名单枚举和数值。 */
internal class Media3DiagnosticObserver(
    private val player: ExoPlayer,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val emit: (PlaybackDiagnostic, Long?, PlaybackHealthSample?) -> Unit,
) : Player.Listener {
    private val monitor = PlaybackHealthMonitor()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null) monitor.end() else monitor.begin(nowMs())
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        monitor.interrupt()
        when (playbackState) {
            Player.STATE_READY -> monitor.ready(nowMs())?.let { emit(PlaybackDiagnostic.READY, it, null) }
            Player.STATE_IDLE, Player.STATE_ENDED -> monitor.end()
        }
    }

    override fun onRenderedFirstFrame() {
        monitor.firstFrame(nowMs())?.let { emit(PlaybackDiagnostic.FIRST_FRAME, it, null) }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = monitor.interrupt()
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = monitor.interrupt()
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = monitor.interrupt()
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = monitor.interrupt()
    override fun onPlayerError(error: PlaybackException) = monitor.end()

    fun interrupt() = monitor.interrupt()
    fun close() = monitor.end()

    fun sample(active: Boolean) {
        val s = PlaybackHealthSample(
            player.playbackState, player.currentPosition, player.bufferedPosition,
            player.playbackParameters.speed, player.isLoading,
            player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected },
            player.playWhenReady, player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            active, player.duration,
        )
        monitor.sample(nowMs(), s)?.let { emit(it, null, s) }
    }
}

/** 手机/TV 共用入口，监听早于页面 load；离页撤监听并取消采样及尚未执行的日志任务。 */
@Composable
internal fun ObserveMedia3Diagnostics(player: ExoPlayer) {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val observer = remember(player) {
        Media3DiagnosticObserver(player) { kind, elapsedMs, sample ->
            val message = "phase=${kind.name.lowercase()}" + (elapsedMs?.let { " media_elapsed_ms=$it" } ?: "") +
                (sample?.let { " state=${it.state} position_ms=${it.positionMs} buffered_ahead_ms=${(it.bufferedPositionMs - it.positionMs).coerceAtLeast(0)}" +
                    " playable_ahead_ms=${((it.bufferedPositionMs - it.positionMs).coerceAtLeast(0) / it.speed).toLong()}" +
                    " speed=${it.speed} loading=${it.loading}" } ?: "")
            scope.launch(Dispatchers.IO) {
                if (sample == null) Logs.d("lp-playback", message) else Logs.w("lp-playback", message)
            }
        }
    }
    DisposableEffect(player, owner, observer) {
        val visibility = LifecycleEventObserver { _, _ -> observer.interrupt() }
        player.addListener(observer)
        owner.lifecycle.addObserver(visibility)
        onDispose {
            observer.close()
            owner.lifecycle.removeObserver(visibility)
            player.removeListener(observer)
        }
    }
    LaunchedEffect(player, owner, observer) {
        while (!player.isReleased) {
            observer.sample(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            delay(1_000)
        }
    }
}
