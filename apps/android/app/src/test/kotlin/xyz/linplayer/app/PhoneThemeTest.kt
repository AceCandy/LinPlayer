package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.theme.DarkColors
import xyz.linplayer.app.ui.theme.LightColors
import xyz.linplayer.app.ui.theme.LpTheme

/** 手机浅色文字对比度及切换主题后的真实外观页渲染回归。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneThemeTest {
    @get:Rule val rule = createComposeRule()
    private val originalTheme = UiPrefs.theme.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun resetTheme() {
        UiPrefs.setTheme(ApplicationProvider.getApplicationContext(), originalTheme)
        scope.cancel()
    }

    @Test fun lightTextContrast() {
        val c = LightColors
        for (background in listOf(c.bg, c.s1.compositeOver(c.bg),
            c.s2.compositeOver(c.bg), c.s3.compositeOver(c.bg))) {
            for (foreground in listOf(c.fg, c.fg2, c.fg3)) {
                assertTrue("浅色文字对比度不足: $foreground / $background",
                    contrast(foreground, background) >= 4.5f)
            }
        }
    }

    @Test fun appearanceFollowsThemeSwitch() {
        UiPrefs.theme.value = "dark"
        val app = AppState(FakeCore(), scope)
        rule.setContent {
            LpTheme(darkOverride = UiPrefs.theme.value == "dark") {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.SettingsSub("appearance")) {
                        composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertBackground(DarkColors.bg)
        rule.onRoot().captureRoboImage("build/phone-theme/dark.png")
        rule.onNodeWithText("浅色").performClick()
        rule.waitForIdle()
        assertBackground(LightColors.bg)
        rule.onRoot().captureRoboImage("build/phone-theme/light.png")
        rule.onNodeWithText("深色").performClick()
        rule.waitForIdle()
        assertBackground(DarkColors.bg)
    }

    private fun assertBackground(expected: Color) {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(expected, pixels[2, pixels.height / 2])
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
