package xyz.linplayer.app.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import kotlinx.serialization.json.JsonElement
import java.util.IdentityHashMap

/** 预加载期间字幕只留在所属媒体中，正式接管时才交给当前libass。 */
internal class PreparedSubtitles {
    private val headers = linkedMapOf<String, ByteArray?>()
    private val files = linkedMapOf<String, ByteArray>()
    private var active = false

    @Synchronized fun header(id: String, bytes: ByteArray?) {
        headers[id] = bytes
        if (active) Libass.header(id, bytes)
    }
    @Synchronized fun file(id: String, bytes: ByteArray) {
        files[id] = bytes
        if (active) Libass.file(id, bytes)
    }
    @Synchronized fun activate() {
        active = true
        headers.forEach { (id, bytes) -> Libass.header(id, bytes) }
        files.forEach { (id, bytes) -> Libass.file(id, bytes) }
    }
    @Synchronized fun deactivate() { active = false }
}

/** 当前媒体和唯一下一集共享Media3加载线程；不创建第二个解码器或输出窗口。 */
@OptIn(UnstableApi::class)
internal class ExoPreload(context: Context, private val dataSource: DataSource.Factory, loadControl: LoadControl, private val fontsDir: String) {
    private data class Entry(val url: String, val subs: JsonElement?, val item: MediaItem, val subtitles: PreparedSubtitles)
    private val builder = DefaultPreloadManager.Builder(context) { _ ->
        DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(0, 5_000)
    }.setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setSubtitleParserFactory(LibassParserFactory(fontsDir)))
    val player: ExoPlayer = builder.buildExoPlayer()
    private val manager = builder.build()
    private var pending: Entry? = null
    private var playing: Entry? = null

    private var queued: Entry? = null
    private var onAdvance: (() -> Unit)? = null
    init {
        owners[player] = this
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val next = queued ?: return
                if (mediaItem != next.item) return
                val callback = onAdvance
                queued = null; onAdvance = null
                playing?.subtitles?.deactivate()
                Libass.reset()
                next.subtitles.activate()
                playing?.let { manager.remove(it.item) }
                playing = next
                callback?.invoke()
            }
        })
    }

    /** 追加已预载源，由同一播放器完成片尾衔接；回调仅在实际媒体切入后执行。 */
    fun queue(url: String, subs: JsonElement?, onTransition: () -> Unit): Boolean {
        val next = pending?.takeIf { it.url == url && it.subs == subs } ?: return false
        val source = manager.getMediaSource(next.item) ?: return false
        queued = next; pending = null; onAdvance = onTransition
        player.addMediaSource(source)
        return true
    }

    fun cancelQueue() {
        val next = queued ?: return
        queued = null; onAdvance = null
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i) == next.item && i != player.currentMediaItemIndex) player.removeMediaItem(i)
        }
        manager.remove(next.item)
    }

    fun trimPrevious() {
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
    }

    fun prepare(url: String, subs: JsonElement?) {
        if (pending?.let { it.url == url && it.subs == subs } == true) return
        cancel()
        val subtitles = PreparedSubtitles()
        val item = exoMediaItem(url, subs)
        val source = DefaultMediaSourceFactory(dataSource)
            .setSubtitleParserFactory(LibassParserFactory(fontsDir, subtitles::header, subtitles::file))
            .createMediaSource(item)
        pending = Entry(url, subs, item, subtitles)
        manager.add(source, 0)
        manager.invalidate()
    }

    fun cancel() {
        cancelQueue()
        pending?.let { manager.remove(it.item) }
        pending = null
    }

    fun load(url: String, subs: JsonElement?): Boolean {
        cancelQueue()
        val next = pending?.takeIf { it.url == url && it.subs == subs }
        val source = next?.let { manager.getMediaSource(it.item) }
        playing?.subtitles?.deactivate()
        if (source != null) {
            player.setMediaSource(source)
            next.subtitles.activate()
            pending = null
        } else {
            player.setMediaItem(exoMediaItem(url, subs))
            cancel()
        }
        playing?.let { manager.remove(it.item) }
        playing = if (source != null) next else null
        return source != null
    }

    fun deactivate() { playing?.subtitles?.deactivate() }

    fun release() {
        deactivate()
        manager.release()
        owners.remove(player)
    }

    companion object {
        private val owners = IdentityHashMap<ExoPlayer, ExoPreload>()
        fun of(player: ExoPlayer?) = owners[player]
    }
}
