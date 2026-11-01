package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.EmbySwitchSource
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class SwitchSourceTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nav: NavHostController
    private lateinit var app: AppState
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun fixture(sessionReady: Boolean = true) = FakeCore().loggedIn().apply {
        ret("emby.itemDetail", item("old-item", "同一部"))
        ret("source.switchCandidates", arr(buildJsonObject {
            put("server_id", "target"); put("server_name", "目标服"); put("tier", 0)
            put("emby_item", item("new-item", "候选作品"))
        }))
        on("account.setActiveServer") {
            if (sessionReady) ret("emby.currentSession", buildJsonObject {
                put("server", "target"); put("token", "fixture"); put("user_id", "target-user")
            }) else on("emby.currentSession") { error("读取目标会话失败") }
            val accounts = arr(buildJsonObject {
                put("server", "target"); put("line_url", "target"); put("user_id", "target-user"); put("active", true)
            })
            ret("account.listAccounts", accounts)
            accounts
        }
    }

    private fun open(core: CorePort) {
        app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Home) {
                        composable<Route.Home> { Text("服务器首页") }
                        composable<Route.Detail> { entry ->
                            val id = entry.toRoute<Route.Detail>().itemId
                            var show by remember { mutableStateOf(true) }
                            Text("详情：$id")
                            if (id == "old-item" && show) EmbySwitchSource(nav, id) { show = false }
                        }
                    }
                }
            }
        }
        rule.runOnIdle { nav.navigate(Route.Detail("old-item", "Movie")) }
    }

    @Test fun switchingServerReplacesOldBackStack() {
        open(fixture())
        rule.onNodeWithText("候选作品").performClick()
        rule.onNodeWithText("详情：new-item").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("target", app.session.value?.server)
            nav.popBackStack()
        }
        rule.onNodeWithText("服务器首页").assertIsDisplayed()
        rule.onNodeWithText("详情：old-item").assertDoesNotExist()
        rule.runOnIdle { assertFalse(nav.popBackStack()) }
    }

    @Test fun failedTargetSessionDoesNotOpenDetail() {
        open(fixture(sessionReady = false))
        rule.onNodeWithText("候选作品").performClick()
        rule.onNodeWithText("详情：new-item").assertDoesNotExist()
        rule.onNodeWithText("服务器首页").assertIsDisplayed()
        rule.onNodeWithText("详情：old-item").assertDoesNotExist()
    }

    @Test fun switchingCannotBeDismissedBeforeSessionHandoff() {
        val fake = fixture()
        val gate = CompletableDeferred<Unit>()
        var switching = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.currentSession" && switching) gate.await()
                val result = fake.callJson(command, args, onPartial)
                if (command == "account.setActiveServer") switching = true
                return result
            }
        }
        open(core)
        try {
            rule.onNodeWithText("候选作品").performClick()
            rule.onNodeWithText("正在切换…").assertIsDisplayed()
            rule.onNode(isDialog()).performTouchInput { click(Offset(1f, 1f)) }
            rule.onNodeWithText("正在切换…").assertIsDisplayed()
        } finally {
            gate.complete(Unit)
        }
        rule.onNodeWithText("详情：new-item").assertIsDisplayed()
        rule.runOnIdle { assertEquals("target", app.session.value?.server) }
    }
}
