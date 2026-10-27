package xyz.linplayer.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import org.junit.Assert.*
import org.junit.After
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.ui.pages.SourceDetailPage
import xyz.linplayer.app.ui.pages.SourceSwitchTarget
import xyz.linplayer.app.ui.player.PlayerController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlayerLaunchPageTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val core = FakeCore()
    private val app = AppState(core, scope)
    private lateinit var sourceNav: NavHostController
    @After fun clean() { scope.cancel(); app.bg.cancel() }

    @Test fun routeLaunchesOnceAndActivityResultReturnsToOriginalPage() {
        lateinit var activity: ComponentActivity
        val target = Route.Player("m1", "标题", "v1", "exo", 9f / 16,
            fromStart = true, subtitle = "{\"sourceId\":\"v1\",\"index\":4}")
        rule.setContent {
            val context = LocalContext.current
            SideEffect { activity = context as ComponentActivity }
            CompositionLocalProvider(LocalApp provides app) { LpTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.Home) {
                    composable<Route.Home> {
                        LpButton("播放", { PlaybackClickTimes.navigate(nav, target) })
                        Text("原页面")
                    }
                    composable<Route.Player> { PlayerLaunchPage(nav, it) }
                    composable<Route.Settings> { Text("设置页面") }
                }
            } }
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

    private fun mountSource(privateSpace: Boolean = false): ComponentActivity {
        lateinit var activity: ComponentActivity
        core.loggedIn()
        core.ret("source.linkSwitch", JsonNull)
        core.ret("source.detail", Json.parseToJsonElement(
            """{"id":"alternate","title":"备用影片","lines":[{"id":"new-line","name":"备用线路","episodes":[{"id":"new-ep4","name":"第4集","index":4}]}]}"""))
        runBlocking { app.boot() }
        rule.setContent {
            activity = LocalContext.current as ComponentActivity
            CompositionLocalProvider(LocalApp provides app) { LpTheme {
                val nav = rememberNavController()
                sourceNav = nav
                NavHost(nav, startDestination = Route.Home) {
                    composable<Route.Home> {
                        Text("原源页面")
                        LpButton("启动源播放", { nav.navigate(Route.Player("old", "原片")) })
                    }
                    composable<Route.Player> { PlayerLaunchPage(nav, it, privateSpace = privateSpace, tv = true) }
                    composable<Route.SourceDetail> { SourceDetailPage(nav, it) }
                    composable<Route.Detail> { Text("Emby目标") }
                }
            } }
        }
        rule.onNodeWithText("启动源播放").performClick()
        rule.waitForIdle()
        return activity
    }

    private val sourceTarget: SourceSwitchTarget = SourceSwitchTarget.Source(
        Route.SourceDetail("plugin:test/tvbox/b", "alternate", autoPos = 125.0, autoIndex = 4),
        "plugin:test/tvbox/a", "original")

    private fun deliverSource(activity: ComponentActivity, raw: String = Json.encodeToString(sourceTarget)) {
        rule.waitForIdle()
        val launch = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        rule.runOnIdle {
            activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK,
                Intent().putExtra(PlayerActivity.SOURCE_SWITCH, raw))
        }
        rule.waitForIdle()
    }

    @Test fun crossSourceResultAutoplaysMatchingEpisodeAndDoesNotRelaunchOnReturn() {
        val activity = mountSource()
        deliverSource(activity)
        val linked = core.calls.single { it.first == "source.linkSwitch" }.second
        assertEquals("plugin:test/tvbox/a", linked.str("from_server"))
        assertEquals("alternate", linked.str("to_item"))
        val launch = shadowOf(activity).nextStartedActivityForResult
        val next = PlayerActivity.readRoute(launch.intent)!!
        val src = Json.parseToJsonElement(next.src!!).obj()
        assertEquals("plugin:test/tvbox/b", src.str("server_id"))
        assertEquals("new-line", src.str("line_id"))
        assertEquals("new-ep4", src.str("episode_id"))
        assertEquals(125.0, src.dbl("resume_secs")!!, 0.0)
        assertTrue(launch.intent.getBooleanExtra("player.tv", false))
        rule.runOnIdle {
            activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent())
        }
        rule.waitForIdle()
        assertEquals("alternate", sourceNav.currentBackStackEntry!!.toRoute<Route.SourceDetail>().itemId)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        rule.runOnIdle { sourceNav.popBackStack() }
        rule.onNodeWithText("原源页面").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun sourceResultWaitsForStopBeforeWritingLinkOrOpeningDetail() {
        val stopped = CompletableDeferred<Unit>()
        val activity = mountSource()
        rule.runOnIdle {
            PlayerController("mpv") { command, _ ->
                if (command == "player.stopPlayback") stopped.await()
                JsonNull
            }.stopIn(app.bg, 0.0, app::report)
        }
        try {
            deliverSource(activity)
            assertFalse(core.calls.any { it.first == "source.linkSwitch" || it.first == "source.detail" })
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            rule.runOnIdle { stopped.complete(Unit) }
            rule.waitForIdle()
            assertEquals(1, core.calls.count { it.first == "source.linkSwitch" })
            assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        } finally { stopped.complete(Unit) }
    }

    @Test fun embySwitchResultChangesAccountAndReturnsToOriginalBrowsePage() {
        val activity = mountSource()
        core.ret("account.setActiveServer", JsonNull)
        val choice: SourceSwitchTarget = SourceSwitchTarget.Emby("http://emby-b.invalid", Route.Detail("show", "Series"))
        deliverSource(activity, Json.encodeToString(choice))
        assertEquals("http://emby-b.invalid", core.calls.single { it.first == "account.setActiveServer" }.second.str("server_id"))
        assertEquals(Route.Detail("show", "Series"), sourceNav.currentBackStackEntry!!.toRoute<Route.Detail>())
        assertFalse(core.calls.any { it.first == "source.linkSwitch" })
        assertTrue(core.calls.count { it.first == "emby.currentSession" } >= 2)
        rule.runOnIdle { sourceNav.popBackStack() }
        rule.onNodeWithText("原源页面").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun failedAccountSwitchDoesNotNavigateToWrongServerDetail() {
        val activity = mountSource()
        core.on("account.setActiveServer") { throw xyz.linplayer.app.core.CoreException("E_NETWORK", "切服测试失败", true) }
        val choice: SourceSwitchTarget = SourceSwitchTarget.Emby("http://emby-b.invalid", Route.Detail("show", "Series"))
        deliverSource(activity, Json.encodeToString(choice))
        rule.onNodeWithText("原源页面").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun privatePlayerRejectsSourceResultWithoutSideEffects() {
        val activity = mountSource(privateSpace = true)
        deliverSource(activity)
        rule.onNodeWithText("原源页面").assertIsDisplayed()
        assertFalse(core.calls.any { it.first in setOf("source.linkSwitch", "source.detail", "account.setActiveServer") })
    }

    @Test fun malformedOrSameLineActivityResultCannotTriggerNavigation() {
        val activity = mountSource()
        val line: SourceSwitchTarget = SourceSwitchTarget.Line(Route.Player("unexpected", "错误交接"))
        for ((index, raw) in listOf("invalid", Json.encodeToString(line)).withIndex()) {
            if (index > 0) rule.onNodeWithText("启动源播放").performClick()
            deliverSource(activity, raw)
            rule.onNodeWithText("原源页面").assertIsDisplayed()
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
        assertFalse(core.calls.any { it.first in setOf("source.linkSwitch", "source.detail", "account.setActiveServer") })
    }

    @Test fun changedSpaceRejectsOldNormalSourceResultEvenAfterReturningToNormal() {
        app.enablePrivacyFiltering()
        val activity = mountSource()
        core.ret("emby.views", Json.parseToJsonElement("""[{"id":"classified","is_adult":false}]"""))
        rule.runOnIdle {
            runBlocking { app.enterPrivateSpace() }
            assertTrue(app.privateSpace.value)
            app.leavePrivateSpace()
        }
        deliverSource(activity)
        rule.onNodeWithText("原源页面").assertIsDisplayed()
        assertFalse(core.calls.any { it.first == "source.linkSwitch" || it.first == "source.detail" })
    }
}
