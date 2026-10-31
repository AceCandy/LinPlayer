package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
import xyz.linplayer.app.ui.player.parseDmLayout
import xyz.linplayer.app.ui.pages.args

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
class DanmakuLayerTest {
    @get:Rule val rule = createComposeRule()
    private val position = mutableStateOf(11.0)
    private val paused = mutableStateOf(false)
    private val buffering = mutableStateOf(false)
    private val speed = mutableStateOf(1.0)
    private val layout = DmLayout(640.0, 360.0, 40.0, 8.0, 5.0, 30.0, 1.0, false,
        listOf(DmItem(10.0, 1, 0, 100.0, 0xFFFFFF, "MMMM")))

    private fun open(layout: DmLayout = this.layout) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            DanmakuLayer(layout, position.value, paused.value, speed.value,
                Modifier.fillMaxSize().background(Color.Black).testTag("danmaku"), buffering = buffering.value)
        }
        rule.waitForIdle()
    }

    private fun visibleColumns(): List<Int> {
        val pixels = rule.onNodeWithTag("danmaku").captureToImage().toPixelMap()
        return (0 until pixels.width).filter { x ->
            (0 until pixels.height).any { y -> pixels[x, y].red > .5f }
        }
    }

    @Test fun fixedTextUsesActualWidthForCentering() {
        paused.value = true
        open(DmLayout(1920.0, 1080.0, 54.0, 8.0, 5.0, 40.0, 1.0, false,
            listOf(DmItem(10.0, 5, 0, 260.0, 0xFFFFFF, "这是一条测试用的弹幕"))))
        val columns = visibleColumns()
        assertEquals(320.0, (columns.first() + columns.last()) / 2.0, 2.0)
    }

    @Test fun rollingTailLeavesScreenBeforeLifetimeEnds() {
        paused.value = true
        position.value = 17.99
        open(DmLayout(1920.0, 1080.0, 54.0, 8.0, 5.0, 40.0, 1.0, false,
            listOf(DmItem(10.0, 1, 0, 260.0, 0xFFFFFF, "这是一条测试用的弹幕"))))
        assertTrue("寿命结束前尾部应已接近完全离屏", visibleColumns().size <= 2)
    }

    @Test fun bottomTextStaysInsideSelectedArea() {
        paused.value = true
        val parsed = parseDmLayout(args("area_height" to 270.0, "items" to kotlinx.serialization.json.JsonArray(listOf(
            args("t" to 10.0, "m" to 4, "l" to 0, "w" to 260.0, "u" to "底部固定弹幕"),
        ))))!!
        open(parsed)
        val pixels = rule.onNodeWithTag("danmaku").captureToImage().toPixelMap()
        val rows = (0 until pixels.height).filter { y -> (0 until pixels.width).any { x -> pixels[x, y].red > .5f } }
        val bottom = pixels.height * .25
        val top = bottom - (4 + 54) * pixels.height / 1080.0
        assertTrue("底部应位于区域最后一行: ${rows.first()}..${rows.last()}", rows.first() >= top - 2)
        assertTrue("不得越出1/4区域", rows.last() < bottom)
    }

    @Test @Config(qualifiers = "w640dp-h640dp-mdpi")
    fun measuredWidthFollowsViewportHeightAndBold() {
        val height = mutableStateOf(360.dp)
        val bold = mutableStateOf(false)
        val layouts = listOf(false, true).associateWith { b ->
            DmLayout(1920.0, 1080.0, 54.0, 8.0, 5.0, 40.0, 1.0, b,
                listOf(DmItem(10.0, 5, 0, 104.0, 0xFFFFFF, "MMMMMMMM")))
        }
        rule.setContent {
            DanmakuLayer(layouts.getValue(bold.value),
                11.0, true, 1.0, Modifier.width(640.dp).height(height.value).background(Color.Black).testTag("danmaku"))
        }
        for ((h, b) in listOf(360.dp to false, 600.dp to false, 600.dp to true, 360.dp to true)) {
            rule.runOnIdle { height.value = h; bold.value = b }
            rule.waitForIdle()
            val cols = visibleColumns()
            assertEquals("尺寸/粗体变化后仍须居中", 320.0, (cols.first() + cols.last()) / 2.0, 2.0)
        }
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

    @Test fun idleSeekWakesFramesAndSpeedChangeDoesNotJumpBackToOldSample() {
        position.value = 50.0
        open()
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertTrue(visibleColumns().isEmpty())
        rule.runOnIdle { position.value = 11.0 }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(64); rule.waitForIdle()
        val first = textLeft()
        rule.mainClock.advanceTimeBy(250); rule.waitForIdle()
        val moving = textLeft()
        assertTrue(moving < first - 15)
        rule.runOnIdle { speed.value = 2.0 }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        assertTrue("变速不应退回旧采样", textLeft() <= moving)
        rule.runOnIdle { position.value = 50.0 }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(64); rule.waitForIdle()
        assertTrue("最后一帧应清除旧字", visibleColumns().isEmpty())
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
