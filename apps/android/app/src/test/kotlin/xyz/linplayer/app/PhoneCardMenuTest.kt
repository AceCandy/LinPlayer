package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.components.CardAction
import xyz.linplayer.app.ui.components.CardMenu
import xyz.linplayer.app.ui.components.PosterMenuPositionProvider
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.LpTheme

/** 使用实际Popup渲染验证菜单，定位另覆盖边缘、窄窗口和RTL。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneCardMenuTest {
    @get:Rule val rule = createComposeRule()

    @Test fun positionAvoidsEveryPosterColumnAndFallsAboveNearBottom() {
        val provider = PosterMenuPositionProvider(8)
        val window = IntSize(393, 873)
        val popup = IntSize(140, 132)
        for (direction in LayoutDirection.entries) for (anchor in listOf(
            IntRect(16, 100, 126, 265), IntRect(141, 100, 251, 265), IntRect(267, 100, 377, 265),
            IntRect(141, 700, 251, 865),
        )) {
            val position = provider.calculatePosition(anchor, window, direction, popup)
            val panel = IntRect(position, popup)
            assertTrue(panel.left >= 0 && panel.right <= window.width && panel.top >= 0 && panel.bottom <= window.height)
            assertFalse("menu overlaps $anchor at $position", panel.overlaps(anchor))
        }
        assertEquals(IntOffset(10, 100), provider.calculatePosition(IntRect(16, 100, 126, 265),
            IntSize(150, 300), LayoutDirection.Ltr, popup))
    }

    @Test fun actualPopupIsNarrowAndAvoidsAnchorAndDispatchesOnce() {
        val open = mutableStateOf(true)
        var clicks = 0
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalMotionScale provides 0f) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(141.dp, 180.dp).size(110.dp, 165.dp).testTag("anchor")) {
                        CardMenu(open.value, { open.value = false }, listOf(CardAction("收藏") { clicks++ }))
                    }
                }
            } }
        }
        val anchor = rule.onNodeWithTag("anchor").fetchSemanticsNode().boundsInWindow
        val panel = rule.onNodeWithTag("poster.menu").fetchSemanticsNode().boundsInWindow
        assertEquals(140f, panel.width, .5f)
        assertFalse(panel.overlaps(anchor))
        rule.onNodeWithText("收藏").performClick()
        rule.onNodeWithTag("poster.menu").assertDoesNotExist()
        assertEquals(1, clicks)
    }

    @Test fun popupShowsSpringOvershootBeforeSettling() {
        val open = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme { Box(Modifier.fillMaxSize().testTag("menu.host")) {
                Box(Modifier.offset(16.dp, 180.dp).size(110.dp, 165.dp)) {
                    CardMenu(open.value, { open.value = false }, listOf(CardAction("收藏") {}))
                }
            } }
        }
        rule.runOnIdle { open.value = true }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("menu.host").captureToImage()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val first = rule.onNodeWithText("收藏", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.width
        var maximum = first
        repeat(12) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            maximum = maxOf(maximum, rule.onNodeWithText("收藏", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.width)
        }
        rule.mainClock.advanceTimeBy(1000)
        val settled = rule.onNodeWithText("收藏", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.width
        assertTrue("initial=$first final=$settled", first < settled)
        assertTrue("spring must overshoot: max=$maximum final=$settled", maximum > settled + .2f)
        assertEquals(140f, rule.onNodeWithTag("poster.menu").fetchSemanticsNode().boundsInWindow.width, .5f)
    }
}
