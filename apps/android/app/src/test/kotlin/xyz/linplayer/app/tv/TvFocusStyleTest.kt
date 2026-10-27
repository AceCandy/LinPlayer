package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.tv.kit.TopNavigation
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvFocusStyleTest {
    @get:Rule val rule = createComposeRule()

    @Composable private fun Remote(content: @Composable () -> Unit) {
        val input = androidx.compose.ui.platform.LocalInputModeManager.current
        LaunchedEffect(input) { input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        CompositionLocalProvider(LocalTvMode provides true, content = content)
    }

    @Test fun sharedFallbackHighlightsSurfaceInsteadOfDrawingAFrame() {
        rule.setContent { LpTheme(darkOverride = true) {
            Remote {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.size(120.dp, 48.dp).testTag("target").pressable({}))
                }
            }
        } }
        rule.onNodeWithTag("target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("target").assertIsFocused()
        val pixels = rule.onNodeWithTag("target").captureToImage().toPixelMap()
        assertTrue("聚焦应突出整个表面而不只画四边", pixels[pixels.width / 2, pixels.height / 2].luminance() > .008f)
        assertEquals("左侧不能额外叠一条亮框",
            pixels[pixels.width / 2, pixels.height / 2].luminance(),
            pixels[2, pixels.height / 2].luminance(), .01f)
    }

    @Test fun sharedButtonUsesInvertedFillInDarkTheme() = checkButton(true)
    @Test fun sharedButtonUsesInvertedFillInLightTheme() = checkButton(false)

    private fun checkButton(dark: Boolean) {
        rule.setContent { LpTheme(darkOverride = dark) {
            Remote {
                Box(Modifier.fillMaxSize()) {
                    LpButton("播放", {}, Modifier.width(140.dp).testTag("button"), kind = BtnKind.Secondary)
                }
            }
        } }
        rule.onNodeWithTag("button").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("button").assertIsFocused()
        val pixels = rule.onNodeWithTag("button").captureToImage().toPixelMap()
        val fill = pixels[pixels.width / 4, pixels.height / 2].luminance()
        assertTrue("焦点填充应随主题反色", if (dark) fill > .8f else fill < .05f)
        rule.onRoot().captureRoboImage("build/shared-tv/focus-button-$dark.png")
    }

    @Test fun navigationBackgroundFadesOutWithoutASolidBottomEdge() {
        rule.setContent { LpTheme(darkOverride = true) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TopNavigation(current = 1, modifier = Modifier.testTag("navigation"))
            }
        } }
        val pixels = rule.onNodeWithTag("navigation").captureToImage().toPixelMap()
        assertTrue("顶栏下沿应透出后方内容，不是一块实底",
            pixels[pixels.width - 2, pixels.height - 2].luminance() > .9f)
    }

    @Test fun disabledTvButtonDoesNotRunMenuActionAndCanBeEnabledAgain() {
        val enabled = mutableStateOf(true)
        var actions = 0
        rule.setContent { LpTheme(darkOverride = true) {
            Remote {
                TvButton("操作", enabled = enabled.value, focused = true,
                    onLongClick = { actions++ }, modifier = Modifier.testTag("button"))
            }
        } }
        rule.onNodeWithTag("button").assertIsFocused()
        press(rule, Key.Menu)
        assertEquals(1, actions)
        rule.runOnIdle { enabled.value = false }
        rule.onNodeWithTag("button").assertIsNotEnabled().assertIsFocused()
        press(rule, Key.Menu)
        assertEquals("禁用时菜单键不能绕过按钮状态", 1, actions)
        rule.runOnIdle { enabled.value = true }
        press(rule, Key.Menu)
        assertEquals(2, actions)
    }
}
