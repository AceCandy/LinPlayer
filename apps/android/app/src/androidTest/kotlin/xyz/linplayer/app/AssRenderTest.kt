package xyz.linplayer.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.linplayer.app.core.Native
import java.io.File

/** 使用包内真实libass验证像素，不用选轨成功或渲染返回值代替字幕可见。 */
@RunWith(AndroidJUnit4::class)
class AssRenderTest {
    private val header = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360
        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Missing Fixture Font,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent() + "\n"
    private val text = "{\\an5\\move(100,180,540,180)}字幕 ASS"

    @Test fun 缺失样式字体仍绘制内封外挂及移动特效() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        // 附件可以先于轨道到达，开轨仍需设置系统字体目录与缺失字体兜底。
        val font = File("/system/fonts").listFiles().orEmpty().first {
            it.isFile && it.extension.lowercase() in listOf("ttf", "otf", "ttc")
        }
        assertEquals(0, Native.assAddFont(font.name, font.readBytes()))
        try {
            val script = header + "Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,$text\n"
            assertEquals(0, Native.assOpenFile(script.toByteArray(), "/system/fonts"))
            Native.assSetSize(640, 360, 640, 360)
            val left = paintedCenter(bitmap, 1000)
            val right = paintedCenter(bitmap, 4000)
            assertTrue("move特效应向右移动", right > left + 150)
            Native.assAddFont(font.name, font.readBytes())
            assertEquals(right, paintedCenter(bitmap, 4000), 1.0)
            Native.assClose()
            assertEquals(0, Native.assOpen(header.toByteArray(), "/system/fonts"))
            Native.assSetSize(640, 360, 640, 360)
            Native.assChunk("0,0,Default,,0,0,0,,$text".toByteArray(), 10000, 5000)
            assertEquals(left, paintedCenter(bitmap, 11000), 1.0)
            assertEquals(right, paintedCenter(bitmap, 14000), 1.0)
            Native.assRender(bitmap, 16000, true)
            val pixels = IntArray(640 * 360)
            bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 360)
            assertTrue("结束后清除字幕", pixels.all { it == 0 })
        } finally {
            Native.assClose()
            bitmap.recycle()
        }
    }

    private fun paintedCenter(bitmap: Bitmap, time: Long): Double {
        assertEquals(1, Native.assRender(bitmap, time, true))
        val pixels = IntArray(640 * 360)
        bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 360)
        val visible = pixels.indices.filter { pixels[it].ushr(24) > 0 }
        assertTrue("必须绘出文字像素，透明帧不算成功", visible.size > 100)
        return visible.map { it % 640 }.average()
    }
}
