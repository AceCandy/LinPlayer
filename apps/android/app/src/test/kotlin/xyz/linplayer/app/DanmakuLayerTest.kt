package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.player.DanmakuLayer
import xyz.linplayer.app.ui.player.DmItem
import xyz.linplayer.app.ui.player.DmLayout

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
class DanmakuLayerTest {
    @get:Rule val rule = createComposeRule()
    private val position = mutableStateOf(11.0)
    private val paused = mutableStateOf(false)
    private val buffering = mutableStateOf(false)
    private val layout = DmLayout(640.0, 360.0, 40.0, 8.0, 5.0, 30.0, 1.0, false,
        listOf(DmItem(10.0, 1, 0, 100.0, 0xFFFFFF, "MMMM")))

    private fun open() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            DanmakuLayer(layout, position.value, paused.value, 1.0,
                Modifier.fillMaxSize().background(Color.Black).testTag("danmaku"), buffering = buffering.value)
        }
        rule.waitForIdle()
    }

    private fun textLeft(): Int {
        val pixels = rule.onNodeWithTag("danmaku").captureToImage().toPixelMap()
        for (x in 0 until pixels.width) for (y in 0 until minOf(80, pixels.height)) {
            if (pixels[x, y].red > 0.5f) return x
        }
        error("弹幕未绘制")
    }

    @Test fun framesKeepMovingBetweenPlaybackSamples() {
        open()
        var left = textLeft()
        repeat(3) {
            rule.mainClock.advanceTimeBy(250)
            rule.waitForIdle()
            val next = textLeft()
            assertTrue("250ms移动量=${left - next}，应约24px", left - next in 18..28)
            left = next
        }
    }

    @Test fun pauseFreezesDrawingAndPausedSeekMovesIt() {
        paused.value = true
        open()
        val left = textLeft()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(left, textLeft())
        rule.runOnIdle { position.value = 12.0 }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        assertTrue("暂停seek应移动弹幕，实际移动=${left - textLeft()}", textLeft() < left - 80)
    }

    @Test fun bufferingWithoutPauseFreezesDrawingAndResumeMovesIt() {
        buffering.value = true
        open()
        val left = textLeft()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(left, textLeft())
        rule.runOnIdle { buffering.value = false }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(250)
        rule.waitForIdle()
        assertTrue("缓冲恢复后应继续移动", textLeft() < left - 15)
    }
}
