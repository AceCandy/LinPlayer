package xyz.linplayer.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.player.PlaybackClickTimes
import xyz.linplayer.app.ui.player.PlayerLaunchPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlayerLaunchPageTest {
    @get:Rule val rule = createComposeRule()

    @Test fun routeLaunchesOnceAndActivityResultReturnsToOriginalPage() {
        lateinit var activity: ComponentActivity
        val target = Route.Player("m1", "标题", "v1", "exo", 9f / 16,
            fromStart = true, subtitle = "{\"sourceId\":\"v1\",\"index\":4}")
        rule.setContent {
            val context = LocalContext.current
            SideEffect { activity = context as ComponentActivity }
            LpTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.Home) {
                    composable<Route.Home> {
                        LpButton("播放", { PlaybackClickTimes.navigate(nav, target) })
                        Text("原页面")
                    }
                    composable<Route.Player> { PlayerLaunchPage(nav, it) }
                    composable<Route.Settings> { Text("设置页面") }
                }
            }
        }
        rule.onNodeWithText("播放").performClick()
        rule.waitForIdle()
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(launch)
        assertEquals(PortraitPlayerActivity::class.java.name, launch.intent.component?.className)
        assertEquals(target, PlayerActivity.readRoute(launch.intent))
        assertTrue(launch.intent.hasExtra("player.clickedAt"))
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        rule.runOnIdle {
            activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent())
        }
        rule.onNodeWithText("原页面").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        rule.onNodeWithText("播放").performClick()
        rule.waitForIdle()
        val second = shadowOf(activity).nextStartedActivityForResult
        rule.runOnIdle {
            activity.activityResultRegistry.dispatchResult(second.requestCode, Activity.RESULT_OK,
                Intent().putExtra(PlayerActivity.NAV_ROUTE, "settings")
                    .putExtra(PlayerActivity.NAV_PARAMS, "{}"))
        }
        rule.onNodeWithText("设置页面").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        val returned = shadowOf(activity).nextStartedActivityForResult
        assertEquals(target, PlayerActivity.readRoute(returned.intent))
        rule.runOnIdle {
            activity.activityResultRegistry.dispatchResult(returned.requestCode, Activity.RESULT_OK,
                Intent().putExtra(PlayerActivity.NAV_ROUTE, "settings")
                    .putExtra(PlayerActivity.NAV_PARAMS, "{}").putExtra(PlayerActivity.NAV_REPLACE, true))
        }
        rule.onNodeWithText("设置页面").assertIsDisplayed()
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("原页面").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }
}
