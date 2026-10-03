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
    @Test fun 外挂WebVtt进入真实MediaItem且切集清空() {
        val subs = Json.parseToJsonElement("""[{"url":"/Videos/item/Subtitles/3/Stream.vtt","mime_type":"text/vtt","title":"中文","lang":"chi","is_default":true},{"url":"/subtitle.ass","mime_type":"text/x-ssa"}]""")
        val item = exoMediaItem("/stream.mkv", subs)
        val configs = item.localConfiguration!!.subtitleConfigurations
        assertEquals(1, configs.size)
        assertEquals("text/vtt", configs[0].mimeType)
        assertEquals("中文", configs[0].label)
        assertEquals("chi", configs[0].language)
        assertTrue(configs[0].selectionFlags != 0)
        assertTrue(exoMediaItem("/next.mkv", null).localConfiguration!!.subtitleConfigurations.isEmpty())
    }
}
