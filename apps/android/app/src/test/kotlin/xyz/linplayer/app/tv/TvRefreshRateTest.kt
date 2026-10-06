package xyz.linplayer.app.tv

import android.app.Activity
import android.app.Application
import android.view.Display
import android.view.WindowManager
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import java.lang.reflect.Proxy

internal fun tvMode(id: Int, hz: Float, width: Int = 1920, height: Int = 1080): Display.Mode =
    Display.Mode::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
        .newInstance(id, width, height, hz)

internal fun installTvModes(display: Display): Array<Display.Mode> {
    val current = display.mode
    val modes = arrayOf(current, tvMode(101, 24f, current.physicalWidth, current.physicalHeight),
        tvMode(102, 50f, current.physicalWidth, current.physicalHeight))
    ShadowDisplayManager.setSupportedModes(display.displayId, *modes)
    return modes
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TvRefreshRateTest {
    @Test fun 常见整数与分数帧率分别选对应模式() {
        val current = tvMode(1, 60f)
        val modes = (listOf(23.976f, 24f, 25f, 29.97f, 50f, 59.94f, 60f)).mapIndexed { i, hz -> tvMode(i + 2, hz) }.toTypedArray()
        for (fps in listOf(23.976f, 24f, 25f, 29.97f, 50f, 59.94f)) {
            assertEquals("$fps", fps, matchingTvMode(current, modes, fps)!!.refreshRate, 0.001f)
        }
    }

    @Test fun 当前已经是整倍频则保留否则选最低整倍频() {
        val sixty = tvMode(1, 60f)
        assertEquals(1, matchingTvMode(sixty, arrayOf(sixty, tvMode(2, 30f)), 30f)!!.modeId)
        assertEquals(3, matchingTvMode(sixty, arrayOf(sixty, tvMode(2, 120f), tvMode(3, 48f)), 24f)!!.modeId)
        assertEquals(4, matchingTvMode(sixty, arrayOf(sixty, tvMode(4, 59.94f)), 29.97f)!!.modeId)
        assertEquals(5, matchingTvMode(sixty, arrayOf(sixty, tvMode(5, 47.952f)), 23.976f)!!.modeId)
    }

    @Test fun 不跨分辨率也不以近似整数频率代替分数频率() {
        val current = tvMode(1, 60f)
        assertNull(matchingTvMode(current, arrayOf(current, tvMode(2, 24f, 3840, 2160)), 24f))
        for (fps in listOf(23.976f, 29.97f, 59.94f)) assertNull(matchingTvMode(current, arrayOf(current, tvMode(3, 24f)), fps))
    }

    @Test fun 无效帧率或不支持的模式不选取() {
        val current = tvMode(1, 60f)
        for (fps in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 27f)) {
            assertNull(matchingTvMode(current, arrayOf(current), fps))
        }
        assertNull(matchingTvMode(current, emptyArray(), 30f))
    }

    @Test fun 窗口只改模式离页恢复非零原偏好并拒绝迟到写入() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        val display = activity.windowManager.defaultDisplay
        installTvModes(display)
        window.attributes = window.attributes.apply { preferredDisplayModeId = 102; preferredRefreshRate = 60f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val owner = TvRefreshRate(window, display)
        owner.match(24f)
        assertEquals(101, window.attributes.preferredDisplayModeId)
        owner.close()
        assertEquals(102, window.attributes.preferredDisplayModeId)
        owner.match(24f)
        owner.restore()
        assertEquals(102, window.attributes.preferredDisplayModeId)
        assertEquals(60f, window.attributes.preferredRefreshRate, 0f)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test @Config(sdk = [24]) fun 最低系统版本匹配且恢复无偏好() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val display = activity.windowManager.defaultDisplay
        installTvModes(display)
        val owner = TvRefreshRate(activity.window, display)
        owner.match(24f)
        assertEquals(101, activity.window.attributes.preferredDisplayModeId)
        owner.match(27f)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        owner.match(30f)
        assertEquals("当前60Hz已匹配，不必添加窗口偏好", 0, activity.window.attributes.preferredDisplayModeId)
        owner.close()
    }

    @Test fun 系统尚未切换时保留已请求的匹配模式() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val display = activity.windowManager.defaultDisplay
        installTvModes(display)
        val owner = TvRefreshRate(activity.window, display)
        owner.match(24f)
        owner.match(24f)
        assertEquals(101, activity.window.attributes.preferredDisplayModeId)
        owner.close()
    }

    @Test fun Media3未就绪时不消费旧格式且就绪后使用实际格式() {
        var state = Player.STATE_BUFFERING
        var format = Format.Builder().setFrameRate(23.976f).build()
        var tracks = Tracks.EMPTY
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "isReleased" -> false
                "getPlaybackState" -> state
                "getVideoFormat" -> format
                "getCurrentTracks" -> tracks
                else -> error("未预期的方法 ${method.name}")
            }
        } as ExoPlayer
        assertNull(tvVideoFrameRate(player))
        state = Player.STATE_READY
        assertEquals(23.976f, tvVideoFrameRate(player)!!, 0.001f)
        format = Format.Builder().build()
        assertNull(tvVideoFrameRate(player))
        tracks = Tracks(listOf(Tracks.Group(TrackGroup(
            Format.Builder().setSampleMimeType("video/avc").setFrameRate(24f).build(),
            Format.Builder().setSampleMimeType("video/avc").setFrameRate(59.94f).build(),
        ), false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(false, true))))
        assertEquals("回落时只读已选中的视频轨", 59.94f, tvVideoFrameRate(player)!!, 0.001f)
    }
}
