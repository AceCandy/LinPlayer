package xyz.linplayer.app

import android.app.Application
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.player.exoMediaItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExoSubtitleTest {
    @Test fun 字幕菜单从转码前格式标注类型() {
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(org.robolectric.RuntimeEnvironment.getApplication()).build()
        try {
            val formats = listOf("text/x-ssa", "application/pgs", "application/x-subrip", "text/vtt", "unknown")
            val groups = formats.mapIndexed { index, mime ->
                val format = androidx.media3.common.Format.Builder().setSampleMimeType("application/x-media3-cues")
                    .setCodecs(mime).setLabel("字幕$index").build()
                androidx.media3.common.Tracks.Group(androidx.media3.common.TrackGroup("$index", format), false,
                    intArrayOf(androidx.media3.common.C.FORMAT_HANDLED), booleanArrayOf(false))
            }
            val info: Any = org.robolectric.util.ReflectionHelpers.getField(player, "playbackInfo")
            val selector: Any = org.robolectric.util.ReflectionHelpers.getField(info, "trackSelectorResult")
            org.robolectric.util.ReflectionHelpers.setField(selector, "tracks", androidx.media3.common.Tracks(groups))
            val tracks = xyz.linplayer.app.ui.player.exoTracks(player, "subtitle").drop(1)
            assertEquals(listOf("ASS/SSA · 特效字幕", "PGS · 图形字幕", "SRT · 文本字幕", "WebVTT · 文本字幕", null),
                tracks.map { it.second })
            assertEquals(formats.indices.map { "字幕$it" }, tracks.map { it.third })
        } finally { player.release() }
    }
    @Test fun 外挂WebVtt进入真实MediaItem且切集清空() {
        val subs = Json.parseToJsonElement("""[{"url":"/Videos/item/Subtitles/3/Stream.vtt","mime_type":"text/vtt","title":"中文","lang":"chi","is_default":true},{"url":"/subtitle.ass","mime_type":"text/x-ssa"}]""")
        val item = exoMediaItem("/stream.mkv", subs)
        val configs = item.localConfiguration!!.subtitleConfigurations
        assertEquals(2, configs.size)
        assertEquals("text/x-ssa", configs[1].mimeType)
        assertTrue(configs[0].id != configs[1].id)
        assertEquals("text/vtt", configs[0].mimeType)
        assertEquals("中文", configs[0].label)
        assertEquals("chi", configs[0].language)
        assertTrue(configs[0].selectionFlags != 0)
        assertTrue(exoMediaItem("/next.mkv", null).localConfiguration!!.subtitleConfigurations.isEmpty())
    }
    @Test fun 完整外挂Ass交付原始脚本而非按Mkv单行丢弃() {
        val script = "[Script Info]\nScriptType: v4.00+\n[Events]\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,测试"
        var actual: ByteArray? = null
        val parser = xyz.linplayer.app.ui.player.LibassParser("external-subtitle-1",
            androidx.media3.common.Format.Builder().setSampleMimeType("text/x-ssa").build()) { id, data ->
            assertEquals("external-subtitle-1", id)
            actual = data
        }
        val bytes = ("prefix" + script + "suffix").toByteArray()
        parser.parse(bytes, 6, script.toByteArray().size,
            androidx.media3.extractor.text.SubtitleParser.OutputOptions.allCues()) {
            org.junit.Assert.fail("整份ASS由libass渲染，不应重复输出普通文本")
        }
        org.junit.Assert.assertArrayEquals(script.toByteArray(), actual)
    }
    @Test fun 关闭字幕会清除最后一帧残留() {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        xyz.linplayer.app.ui.player.Libass.deactivate()
        assertTrue(xyz.linplayer.app.ui.player.Libass.changed(0))
        assertEquals(1, xyz.linplayer.app.ui.player.Libass.render(bitmap, 0, false))
        assertEquals(android.graphics.Color.TRANSPARENT, bitmap.getPixel(0, 0))
        org.junit.Assert.assertFalse(xyz.linplayer.app.ui.player.Libass.changed(0))
    }
    @Test fun 超过四兆的外挂Ass仍交给渲染器() {
        val bytes = ByteArray((4 shl 20) + 1) { 32 }
        var received = 0
        val parser = xyz.linplayer.app.ui.player.LibassParser("large",
            androidx.media3.common.Format.Builder().setSampleMimeType("text/x-ssa").build()) { _, data -> received = data.size }
        parser.parse(bytes, 0, bytes.size, androidx.media3.extractor.text.SubtitleParser.OutputOptions.allCues()) {}
        assertEquals(bytes.size, received)
    }
}
