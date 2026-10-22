package xyz.linplayer.app.ui.player.liuying

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import kotlinx.serialization.json.JsonElement
import xyz.linplayer.app.BuildConfig
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.LibassParserFactory
import xyz.linplayer.app.ui.player.PreparedSubtitles
import xyz.linplayer.app.ui.player.exoMediaItem
import xyz.linplayer.app.ui.player.syncLibassTrack

internal data class LiuyingMedia(
    val request: String, val item: String, val url: String, val source: String,
    val subtitles: JsonElement?, val canPreload: Boolean, val createdAt: Long,
)

/** 所有入口在主线程。管理器只加载一个未来源，备用槽不发声、不激活全局字幕。 */
@OptIn(UnstableApi::class)
internal class LiuyingMediaSlots(private val context: Context) {
    internal class Entry(val media: LiuyingMedia, var item: MediaItem, val subtitles: PreparedSubtitles, val ranking: Int) {
        var rank = 0
        var samplesReady = false
        var added = false
        var failed = false
        var interrupted = false
        var sourceRevision = 0
    }
    internal class Slot(val player: ExoPlayer, val name: String) {
        var entry by mutableStateOf<Entry?>(null)
        var surface: Surface? = null
        var frameReady by mutableStateOf(false)
        var released = false
    }

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent("LinPlayer/" + BuildConfig.VERSION_NAME).setAllowCrossProtocolRedirects(true)
    private val baseLoad = DefaultLoadControl.Builder()
        .setBufferDurationsMs(3_000, 20_000, 500, 1_000)
        .setTargetBufferBytes(16 * 1024 * 1024)
        .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, 16 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false).build()
    @Volatile private var currentName = ""
    @Volatile private var warmName = ""
    @Volatile private var foreground = true
    @Volatile private var reading = false
    private val load = object : LoadControl by baseLoad {
        // Java接口的default方法不会由Kotlin的by转发，必须覆盖Media3实际调用的重载。
        override fun onPrepared(playerId: PlayerId) = baseLoad.onPrepared(playerId)
        override fun onStopped(playerId: PlayerId) = baseLoad.onStopped(playerId)
        override fun onReleased(playerId: PlayerId) = baseLoad.onReleased(playerId)
        override fun getBackBufferDurationUs(playerId: PlayerId) = baseLoad.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: PlayerId) = baseLoad.retainBackBufferFromKeyframe(playerId)
        override fun onTracksSelected(parameters: LoadControl.Parameters,
            trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
            selections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) =
            baseLoad.onTracksSelected(parameters, trackGroups, selections)
        override fun shouldStartPlayback(parameters: LoadControl.Parameters) = baseLoad.shouldStartPlayback(parameters)
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            if (!foreground) return false
            if (parameters.playerId.name != currentName &&
                (!reading || parameters.playerId.name != warmName || parameters.bufferedDurationUs >= 5_000_000)) return false
            return baseLoad.shouldContinueLoading(parameters)
        }
        override fun shouldContinuePreloading(playerId: PlayerId, timeline: androidx.media3.common.Timeline,
            mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
            foreground && reading && baseLoad.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
    }
    private var warming: Entry? = null
    private val builder = DefaultPreloadManager.Builder(context) { ranking ->
        entries.values.firstOrNull { it.ranking == ranking }?.takeIf {
            it.samplesReady || reading && it === warming && !it.failed
        }?.let {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(0, when (it.rank) { 1 -> 5_000L; 2 -> 3_000L; else -> 1_000L })
        } ?: DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
    }.setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(http))
    private val manager = builder.build()
    private val entries = linkedMapOf<String, Entry>()
    private var ranking = 0
    private val slots = mutableListOf<Slot>()
    var active by mutableStateOf<Slot?>(null)
        private set
    var spare by mutableStateOf<Slot?>(null)
        private set
    var singleSlot = false
        private set
    private var closed = false
    private var ownsSubtitles = false
    var onError: (Throwable) -> Unit = {}
    var onEnded: () -> Unit = {}
    var onPrepared: (String) -> Unit = {}
    var onSurfaceChanged: () -> Unit = {}

    init {
        manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                entries.values.firstOrNull { it.item == mediaItem }?.let {
                    if (reading && warming === it && !it.failed) {
                        it.samplesReady = true
                        onPrepared(it.media.request)
                    }
                }
            }
            override fun onError(exception: PreloadException) {
                entries.values.firstOrNull { it.item == exception.mediaItem }?.let {
                    it.failed = true
                    it.interrupted = false; it.samplesReady = false
                }
            }
        })
    }

    private fun createSlot(): Slot {
        check(slots.size < 2 && !closed)
        val name = "liuying-${if (slots.any { it.name == "liuying-0" }) 1 else 0}"
        val slot = Slot(builder.buildExoPlayer(ExoPlayer.Builder(context).setName(name)
            .setWakeMode(C.WAKE_MODE_NONE)), name)
        slots += slot
        slot.player.volume = 0f
        slot.player.addAnalyticsListener(object : AnalyticsListener {
            private fun matches(eventTime: AnalyticsListener.EventTime): Boolean {
                val timeline = eventTime.timeline
                val mediaId = if (eventTime.windowIndex in 0 until timeline.windowCount)
                    timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId else null
                return !closed && !slot.released && mediaId != null && mediaId == slot.entry?.item?.mediaId
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (matches(eventTime) && output === slot.surface)
                    slot.frameReady = true
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (!matches(eventTime)) return
                if (active === slot) onError(error) else if (spare === slot) {
                    slot.entry?.let { it.failed = true; it.samplesReady = false }
                    singleSlot = true
                    releaseSpare()
                }
            }
        })
        slot.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (active === slot && playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (active === slot) syncLibassTrack(slot.player, tracks, false)
            }
        })
        return slot
    }

    private fun entry(media: LiuyingMedia): Entry {
        entries[media.request]?.let { return it }
        val subtitles = PreparedSubtitles()
        val item = exoMediaItem(media.url, media.subtitles).buildUpon().setMediaId(media.request).build()
        return Entry(media, item, subtitles, ++ranking).also {
            entries[media.request] = it
        }
    }

    private fun addSource(e: Entry) {
        if (e.added) return
        val source = DefaultMediaSourceFactory(http)
            .setSubtitleParserFactory(LibassParserFactory("/system/fonts", e.subtitles::header, e.subtitles::file))
            .createMediaSource(e.item)
        manager.add(source, e.ranking)
        e.added = true
    }

    fun prepare(media: LiuyingMedia, rank: Int) {
        if (closed || !media.canPreload) return
        val e = entry(media)
        if (e.rank != rank) {
            if (rank < e.rank) e.samplesReady = false
            e.rank = rank
        }
        if (spare?.entry?.rank != 1) releaseSpare()
    }

    /** 样本先由管理器填充，再由唯一备用实例准备；不再调度同源预载。 */
    fun warm(request: String?, allow: Boolean) {
        if (closed) return
        if (!allow) warming?.takeIf { !it.failed && !it.samplesReady }?.let { it.interrupted = true }
        if (allow) entries[request]?.takeIf { !it.failed && it.interrupted && it !== spare?.entry }?.let {
            resetSource(it)
        }
        val candidate = entries[request]?.takeIf { !it.failed && it !== active?.entry && it !== spare?.entry }
        val changed = reading != allow || warming !== candidate
        reading = allow
        warming = if (allow) candidate else null
        // 未获读取资格的条目不交给管理器，避免选轨阶段偷偷读取其它候选。
        if (allow) warming?.let(::addSource)
        if (changed) {
            manager.setCurrentPlayingIndex((warming ?: spare?.entry)?.ranking ?: 0)
            manager.invalidate()
        }
        val nearest = entries.values.firstOrNull { it.rank == 1 && it.samplesReady && !it.failed && it !== active?.entry }
        if (allow && nearest != null && nearest.media.request == request && !singleSlot && spare == null) {
            val slot = runCatching { slots.firstOrNull { it !== active && it.entry == null } ?: createSlot() }
                .getOrElse { singleSlot = true; return }
            spare = slot
            warming = null
            warmName = slot.name
            manager.invalidate()
            bind(slot, nearest)
        }
        warmName = if (allow && spare?.entry?.media?.request == request) spare?.name.orEmpty() else ""
    }

    fun nextWarmRequest(): String? {
        spare?.let { slot ->
            if (slot.player.playbackState != Player.STATE_READY ||
                slot.player.bufferedPosition < 5_000 && slot.player.bufferedPosition < slot.player.duration) return slot.entry?.media?.request
        }
        if (spare == null && !singleSlot) entries.values.firstOrNull {
            it.rank == 1 && it.samplesReady && !it.failed && it !== active?.entry
        }?.let { return it.media.request }
        return entries.values.filter { it.rank > 0 && !it.failed && !it.samplesReady && it !== active?.entry }
            .minByOrNull { it.rank }?.media?.request
            ?: entries.values.firstOrNull { it.rank == 1 && !it.failed && it !== active?.entry && spare == null && !singleSlot }?.media?.request
    }

    private fun bind(slot: Slot, e: Entry) {
        if (e.failed) resetSource(e)
        addSource(e)
        slot.entry = e
        slot.frameReady = false
        slot.player.playWhenReady = false
        slot.player.volume = 0f
        val source = checkNotNull(manager.getMediaSource(e.item))
        slot.player.setMediaSource(source)
        slot.player.prepare()
    }

    private fun resetSource(e: Entry) {
        if (e.added) manager.remove(e.item)
        e.item = e.item.buildUpon().setMediaId("${e.media.request}:${++e.sourceRevision}").build()
        e.added = false; e.failed = false; e.samplesReady = false; e.interrupted = false
    }

    fun promote(media: LiuyingMedia, speed: Float, muted: Boolean) {
        silence()
        val e = entry(media)
        val next = spare?.takeIf { it.entry === e }
        val old = active
        old?.entry?.subtitles?.deactivate()
        Libass.reset()
        ownsSubtitles = true
        if (next != null) {
            active = next
            spare = null
            old?.let(::clear)
        } else {
            releaseSpare()
            val slot = old ?: createSlot()
            clear(slot)
            active = slot
            currentName = slot.name
            bind(slot, e)
        }
        currentName = active!!.name
        warmName = ""
        e.rank = 0
        warming = null
        manager.setCurrentPlayingIndex(0)
        manager.invalidate()
        e.subtitles.activate()
        active!!.player.setPlaybackSpeed(speed)
        active!!.player.volume = if (muted) 0f else 1f
    }

    fun silence() { slots.forEach { it.player.pause(); it.player.volume = 0f } }
    fun detachCurrent() {
        val previous = active?.entry
        active?.let(::clear)
        if (ownsSubtitles) Libass.reset()
        ownsSubtitles = false
        previous?.let { discard(it.media.request) }
    }
    fun gate(play: Boolean, muted: Boolean, isForeground: Boolean) {
        foreground = isForeground
        active?.player?.let { it.volume = if (muted || !play) 0f else 1f; it.playWhenReady = play }
        if (!isForeground) { warm(null, false); releaseSpare() }
    }
    private fun clear(slot: Slot) {
        slot.entry?.subtitles?.deactivate()
        slot.player.stop()
        slot.player.clearMediaItems()
        slot.entry = null
        slot.frameReady = false
    }
    fun releaseSpare() {
        val slot = spare ?: return
        spare = null; warmName = ""
        clear(slot); slots.remove(slot); slot.released = true; slot.player.release()
    }
    fun discard(request: String) {
        val e = entries[request] ?: return
        if (active?.entry === e) return
        if (spare?.entry === e) releaseSpare()
        if (warming === e) warming = null
        if (e.added) manager.remove(e.item)
        entries.remove(request)
    }
    fun retain(requests: Set<String>) {
        entries.keys.toList().filter { it !in requests && active?.entry?.media?.request != it }.forEach(::discard)
    }
    fun release() {
        if (closed) return
        closed = true; foreground = false; reading = false
        slots.forEach { clear(it); it.released = true; it.player.clearVideoSurface(); it.player.release() }
        slots.clear(); active = null; spare = null
        manager.release(); entries.clear()
        if (ownsSubtitles) Libass.reset()
        ownsSubtitles = false
    }
}

/** 当前与相邻页各自持有Surface；销毁立即解绑，首帧只接受实际输出Surface。 */
@Composable
internal fun LiuyingVideoSurface(slot: LiuyingMediaSlots.Slot, onSurfaceChanged: () -> Unit, modifier: Modifier = Modifier) {
    var ownedSurface by remember(slot) { mutableStateOf<Surface?>(null) }
    DisposableEffect(slot) {
        onDispose {
            if (slot.surface === ownedSurface) {
                slot.surface = null; slot.frameReady = false
                if (!slot.released) slot.player.clearVideoSurface()
                onSurfaceChanged()
            }
        }
    }
    AndroidView(modifier = modifier, factory = { context ->
        SurfaceView(context).also { view ->
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!slot.released) {
                        ownedSurface = holder.surface
                        slot.surface = holder.surface; slot.frameReady = false
                        slot.player.setVideoSurface(holder.surface)
                        onSurfaceChanged()
                    }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (slot.surface === holder.surface) {
                        slot.surface = null; slot.frameReady = false
                        if (!slot.released) slot.player.clearVideoSurface()
                        onSurfaceChanged()
                    }
                }
            })
        }
    })
}
