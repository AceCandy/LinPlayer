package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.player.Osd
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PhonePlayerOsdTest {
    @get:Rule val rule = createComposeRule()
    private val dark = mutableStateOf(false)
    private val panel = mutableStateOf(false)
    private val speed = mutableStateOf(1.5)
    private var seek = 0.0
    private var action = ""

    private fun open(portrait: Boolean = false) {
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                        listOf(Color(0xFF455F48), Color(0xFF181D26))))) {
                        Osd(portrait, "1.13 MB/s", panelOpen = panel.value,
                            title = "测试剧集", episodeLabel = "S1E7：一段用于核查横屏布局的单集标题",
                            position = 1517.0, duration = 2729.0, paused = false, speed = speed.value,
                            heat = emptyList(), pluginPanels = emptyList(),
                            onBack = { action = "返回" }, onToggle = { action = "暂停" },
                            onSeek = { seek = it }, onSpeed = { speed.value = it },
                            onLock = { action = "锁屏" }, onShot = { action = "截屏" },
                            onPanel = { action = it })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun landscapeControlsHaveSeparateZonesAndDispatchActions() {
        open()
        val pause = rule.onNodeWithContentDescription("暂停").fetchSemanticsNode().boundsInRoot
        val rewind = rule.onNodeWithContentDescription("后退 10 秒").fetchSemanticsNode().boundsInRoot
        val faster = rule.onNodeWithContentDescription("加速").fetchSemanticsNode().boundsInRoot
        val shot = rule.onNodeWithContentDescription("截屏").fetchSemanticsNode().boundsInRoot
        assertTrue(rewind.right < pause.left && pause.right < faster.left && shot.right < rewind.left)
        rule.onNodeWithText("25:17").assertIsDisplayed()
        rule.onNodeWithText("45:29").assertIsDisplayed()
        rule.onNodeWithText("S1E7", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-light.png")
        rule.runOnIdle { dark.value = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-dark.png")
        rule.onNodeWithContentDescription("后退 10 秒").performClick()
        assertEquals(1507.0, seek, 0.001)
        rule.onNodeWithContentDescription("前进 10 秒").performClick()
        assertEquals(1527.0, seek, 0.001)
        rule.onNodeWithContentDescription("加速").performClick()
        rule.onNodeWithText("1.75×").assertIsDisplayed()
        rule.onNodeWithContentDescription("选集").assertDoesNotExist()
        rule.onNodeWithContentDescription("音轨").performClick()
        assertEquals("audio", action)
        rule.onNodeWithContentDescription("截屏").performClick()
        assertEquals("截屏", action)
        rule.runOnIdle { panel.value = true }
        rule.onNodeWithContentDescription("暂停").assertDoesNotExist()
        rule.onNodeWithContentDescription("加速").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun compactLandscapeHasNoOverlappingButtonsAtLargeFont() {
        open()
        val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        buttons.forEachIndexed { i, a -> buttons.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("按钮命中区不能重叠", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onNodeWithContentDescription("音轨").assertIsDisplayed()
        rule.onNodeWithContentDescription("字幕").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-compact.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun portraitKeepsExistingControls() {
        open(portrait = true)
        rule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        rule.onNodeWithContentDescription("锁屏").assertIsDisplayed()
        rule.onNodeWithText("字幕").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/portrait.png")
    }
}
