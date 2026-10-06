package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PhoneEnginePlaybackTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun cleanup() {
        PlaybackService.stop(ApplicationProvider.getApplicationContext())
        scope.cancel()
    }

    @Test fun Media3换版本和选集继续通过页面起播并等待旧会话停止() {
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            on("player.play") { arguments -> buildJsonObject {
                put("media_source_id", arguments.str("media_source_id") ?: "ea")
                put("resume_secs", 0.0)
            } }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Player("sh6", "测试剧集", engine = "exo", ar = 16f / 9)) {
                        composable<Route.Player> { PlayerPage(nav, it) }
                    }
                }
            }
        }
        advance(rule, 800)
        assertEquals("初次起播请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithContentDescription("更多").performClick()
        advance(rule, 300)
        assertEquals("打开更多后的请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithText("版本与线路").performClick()
        advance(rule, 300)
        assertEquals("打开版本面板后的请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithText("1080p").performClick()
        advance(rule, 500)
        val plays = core.calls.filter { it.first == "player.play" }
        assertEquals(plays.map { listOf(it.second.str("item_id"), it.second.str("engine"), it.second.str("media_source_id")) }.toString(), 2, plays.size)
        assertEquals("exo", plays.last().second.str("engine"))
        assertEquals("eb", plays.last().second.str("media_source_id"))
        assertEquals(0.0, plays.last().second.dbl("resume_secs")!!, 0.0)
        assertTrue(plays.last().second.bool("from_start"))
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        val stop = core.calls.indexOfFirst { it.first == "player.stopPlayback" }
        val next = core.calls.indexOfLast { it.first == "player.play" }
        assertTrue(stop in 0 until next)
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel("episodes") }
        advance(rule, 300)
        rule.onNode(hasText("S1E7", substring = true)).performClick()
        advance(rule, 500)
        val episode = core.calls.last { it.first == "player.play" }.second
        assertEquals(3, core.calls.count { it.first == "player.play" })
        assertEquals(2, core.calls.count { it.first == "player.stopPlayback" })
        assertEquals("sh7", episode.str("item_id"))
        assertEquals("exo", episode.str("engine"))
        assertFalse(episode!!.containsKey("media_source_id"))
        assertFalse(episode.containsKey("resume_secs"))
        assertEquals(listOf("player.play", "player.stopPlayback", "player.play", "player.stopPlayback", "player.play"),
            core.calls.map { it.first }.filter { it == "player.play" || it == "player.stopPlayback" })
    }
}
