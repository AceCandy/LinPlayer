package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.player.NextUp
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-port-mdpi", sdk = [36], application = Application::class)
class PhoneNextUpTest {
    @get:Rule val rule = createComposeRule()
    private var clicks = 0

    private fun open(episodeNo: Long? = 29, width: Int = 150, fontScale: Float = 1f) {
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize()) {
                        NextUp(Item("next", "一个很长的单集标题".repeat(8), "Episode", episodeNo = episodeNo),
                            15.0, 300.0, width.dp, Modifier) { clicks++ }
                    }
                }
            }
        }
    }

    @Test fun 集数胶囊视觉36dp且透明边缘仍可点击() {
        open()
        rule.onNodeWithText("接下来 · 第29集").assertIsDisplayed()
        val hit = rule.onNodeWithTag("player.nextUp").fetchSemanticsNode().boundsInRoot
        val face = rule.onNodeWithTag("player.nextUp.face", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(150f, face.width, .1f)
        assertEquals(36f, face.height, .1f)
        assertEquals(48f, hit.height, .1f)
        rule.onNodeWithTag("player.nextUp").performTouchInput { click(Offset(width / 2f, 1f)) }
        assertEquals(1, clicks)
        rule.onRoot().captureRoboImage("build/player-osd/next-up-capsule.png")
    }

    @Test fun 缺集数长标题保持单行且大字不裁高度() {
        open(episodeNo = null, fontScale = 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("接下来", substring = true, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        val face = rule.onNodeWithTag("player.nextUp.face", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(150f, face.width, .1f)
        assertTrue("大字胶囊须容纳完整文字高度与留白", face.height >= layouts.single().size.height + 16f)
        rule.onRoot().captureRoboImage("build/player-osd/next-up-capsule-large-font.png")
    }

    @Test fun 小横屏大字优先完整显示集数() {
        open(width = 120, fontScale = 1.3f)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("接下来 · 29集", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("窄胶囊不能把集数省略掉", layouts.single().hasVisualOverflow)
    }

    @Test fun 最窄安全区保留点击和完整读屏标签() {
        open(width = 48)
        rule.onNodeWithContentDescription("接下来 · 第29集").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        assertEquals(48f, rule.onNodeWithTag("player.nextUp").fetchSemanticsNode().boundsInRoot.width, .1f)
    }
}
