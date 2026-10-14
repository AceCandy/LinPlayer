package xyz.linplayer.app.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
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

    init { owners[player] = this }

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
        pending?.let { manager.remove(it.item) }
        pending = null
    }

    fun load(url: String, subs: JsonElement?): Boolean {
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
