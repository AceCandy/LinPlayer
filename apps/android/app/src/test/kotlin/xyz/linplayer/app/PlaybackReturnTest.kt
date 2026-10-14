package xyz.linplayer.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.PlayerLaunchPage
import xyz.linplayer.app.ui.theme.LpTheme

/** 真实详情与 Activity 返回接线：停止完成后才刷新，连播末集优先于原入口。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PlaybackReturnTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var activity: ComponentActivity
    private val core = FakeCore().loggedIn().player()
    private var progress = 0.0
    private val stops = mutableListOf<CompletableDeferred<Unit>>()

    @After fun cleanup() {
        stops.forEach { it.complete(Unit) }
        scope.cancel()
        PageCache.clear()
    }

    private fun open(series: Boolean) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        fun episode(id: String) = buildJsonObject {
            item(id, "分集$id", "Episode", season = 1, episode = id.removePrefix("e").toInt(),
                runtime = 1800.0, resume = if (id == "e5") progress else 0.0).forEach { (k, v) -> put(k, v) }
            put("season_id", "s1"); put("series_id", "series")
        }
        core.on("emby.itemDetail") { a ->
            val id = a.str("item_id")!!
            if (id == "series") item(id, "测试剧集", "Series") else episode(id)
        }
        core.ret("emby.seriesSeasons", arr(buildJsonObject {
            put("id", "s1"); put("name", "第1季"); put("index_no", 1)
        }))
        core.on("emby.seasonEpisodes") {
            buildJsonObject {
                put("total", 8); put("items", arr(*(1..8).map { episode("e$it") }.toTypedArray()))
            }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            val context = LocalContext.current
            SideEffect { activity = context as ComponentActivity }
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = if (series) Route.Detail("series", "Series") else Route.Detail("e3", "Episode")) {
                        composable<Route.Detail> { DetailPage(nav, it) }
                        composable<Route.Player> { PlayerLaunchPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun returnFromFifth(position: Double) {
        val readsBefore = core.calls.count { it.first == "emby.itemDetail" && it.second.str("item_id") == "e5" }
        rule.onNodeWithTag("detail.play").performScrollTo().performClick()
        rule.waitForIdle()
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(launch)
        val stop = CompletableDeferred<Unit>().also { stops += it }
        rule.runOnIdle {
            PlayerController("mpv") { _, _ -> stop.await(); progress = position; JsonNull }
                .stopIn(scope, position, { throw it })
            activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK,
                Intent().putExtra(PlayerActivity.PLAYED_ITEM, "e5"))
        }
        rule.waitForIdle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        val before = core.calls.count { it.first == "emby.itemDetail" && it.second.str("item_id") == "e5" }
        assertEquals("刷新不能越过尚未完成的停止上报", readsBefore, before)
        rule.runOnIdle { stop.complete(Unit) }
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play").performScrollTo()
        val range = rule.onNodeWithTag("detail.play").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals((position / 1800).toFloat(), range.current, .001f)
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        rule.onNodeWithTag("detail.ep.e5").assertIsDisplayed()
    }

    @Test fun episodeReturnsToFifthAndSameEpisodeRefreshesAgain() {
        open(series = false)
        returnFromFifth(900.0)
        returnFromFifth(1350.0)
    }

    @Test fun seriesTargetsFifthEvenWhenEarlierEpisodesAreUnwatched() {
        open(series = true)
        returnFromFifth(900.0)
        rule.onNodeWithTag("detail.play").performScrollTo().performClick()
        rule.waitForIdle()
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertEquals("e5", PlayerActivity.readRoute(launch.intent)?.itemId)
    }
}
