package xyz.linplayer.app.ui.player

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.str

/** 只展示当前内核已知的媒体参数，不展示可能带凭据的播放地址或错误原文。 */
internal fun playbackMediaInfo(player: ExoPlayer?, opts: JsonObject?): List<Pair<String, String>> = buildList {
    add("播放内核" to if (player == null) "MPV" else "Media3")
    fun field(label: String, value: String?) { value?.takeIf { it.isNotBlank() }?.let { add(label to it) } }
    val speed = if (player == null) opts.str("speed")?.toDoubleOrNull() else player.playbackParameters.speed.toDouble()
    speed?.takeIf { it.isFinite() && it > 0 }?.let { add("实际倍速" to "%.2f×".format(it)) }
    if (player == null) {
        for ((label, key) in listOf("渲染丢帧" to "frame-drop-count", "解码丢帧" to "decoder-frame-drop-count")) {
            opts.str(key)?.toLongOrNull()?.takeIf { it >= 0 }?.let { add(label to "$it 帧") }
        }
        field("视频编码", opts.str("video-codec"))
        val width = opts.str("dwidth")?.toIntOrNull()
        val height = opts.str("dheight")?.toIntOrNull()
        if (width != null && width > 0 && height != null && height > 0) add("分辨率" to "$width × $height")
        opts.str("container-fps")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let {
            add("帧率" to "%.3f fps".format(it))
        }
        field("像素格式", opts.str("video-format"))
        field("硬件解码", opts.str("hwdec-current")?.let { if (it == "no") "未启用" else it })
        field("视频输出", opts.str("current-vo"))
        field("音频编码", opts.str("audio-codec-name"))
    } else {
        player.videoFormat?.let { video ->
            field("视频编码", video.codecs ?: video.sampleMimeType)
            if (video.width > 0 && video.height > 0) add("分辨率" to "${video.width} × ${video.height}")
            if (video.frameRate.isFinite() && video.frameRate > 0) add("帧率" to "%.3f fps".format(video.frameRate))
            if (video.bitrate > 0) add("视频码率" to "%.2f Mbps".format(video.bitrate / 1_000_000.0))
        }
        player.audioFormat?.let { audio ->
            field("音频编码", audio.codecs ?: audio.sampleMimeType)
            if (audio.sampleRate > 0) add("采样率" to "${audio.sampleRate} Hz")
            if (audio.channelCount > 0) add("声道数" to audio.channelCount.toString())
            if (audio.bitrate > 0) add("音频码率" to "${audio.bitrate / 1000} kbps")
        }
    }
    if (size == 1) add("媒体参数" to "当前暂无可用信息")
}
