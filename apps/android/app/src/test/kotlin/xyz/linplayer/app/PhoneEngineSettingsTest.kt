package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
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
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneEngineSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val original = UiPrefs.engine.value

    @After fun cleanup() {
        UiPrefs.setEngine(ApplicationProvider.getApplicationContext(), original)
        scope.cancel()
    }

    @Test fun 三种内核显示并将自动选择持久化() {
        UiPrefs.engine.value = "mpv"
        val app = AppState(FakeCore(), scope)
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.SettingsSub("player")) {
                        composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                    }
                }
            }
        }
        rule.onNodeWithText("播放内核").assertIsDisplayed()
        rule.onNodeWithText("Media3").assertIsDisplayed()
        rule.onNodeWithText("MPV").assertIsDisplayed()
        rule.onAllNodesWithText("自动").onFirst().performClick()
        rule.runOnIdle {
            assertEquals("auto", UiPrefs.engine.value)
            UiPrefs.engine.value = "mpv"
            UiPrefs.load(ApplicationProvider.getApplicationContext())
            assertEquals("auto", UiPrefs.engine.value)
        }
        rule.onNodeWithText("Media3").performClick()
        rule.runOnIdle { assertEquals("exo", UiPrefs.engine.value) }
    }
}
