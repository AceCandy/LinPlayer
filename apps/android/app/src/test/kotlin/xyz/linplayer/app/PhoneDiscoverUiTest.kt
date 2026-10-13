package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.RankingPage
import xyz.linplayer.app.ui.pages.CalendarPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneDiscoverUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun open(core: FakeCore, route: Any = Route.Ranking) {
        xyz.linplayer.app.tv.FakeImages.install(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = route) {
                        composable<Route.Ranking> { RankingPage(nav) }
                        composable<Route.Calendar> { CalendarPage(nav) }
                        composable<Route.Search> { Text("搜索目标：" + it.toRoute<Route.Search>().q) }
                        composable<Route.Detail> { Text("详情目标：" + it.toRoute<Route.Detail>().itemId) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rankings() = FakeCore().loggedIn().apply {
        ret("emby.extRankings", arr(buildJsonObject { put("id", "hot"); put("label", "热门榜") }))
        ret("emby.extRankingItems", arr(buildJsonObject {
            put("id", "external-1"); put("rank", 1); put("title", "远方的故事"); put("rating", 8.6)
        }))
    }
    @Test fun rankingRetryFetchesSelectedCategory() {
        val core = rankings()
        core.on("emby.extRankingItems") { throw CoreException("E_NETWORK", "榜单读取失败", true) }
        open(core)
        val before = core.calls.count { it.first == "emby.extRankingItems" }
        core.ret("emby.extRankingItems", arr(buildJsonObject {
            put("id", "external-1"); put("rank", 1); put("title", "远方的故事")
        }))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事").assertIsDisplayed()
        assertEquals(before + 1, core.calls.count { it.first == "emby.extRankingItems" })
        assertEquals("hot", core.calls.last { it.first == "emby.extRankingItems" }.second.str("category_id"))
    }
    @Test fun rankingPaginationAndUnsupportedState() {
        val core = rankings()
        core.on("emby.extRankingItems") { a ->
            if (a.long("page") == 2L) arr() else arr(buildJsonObject {
                put("id", "external-1"); put("rank", 1); put("title", "第一页作品")
            })
        }
        open(core)
        rule.onNodeWithText("下一页").performClick()
        rule.onNodeWithText("下一页").assertIsNotEnabled()
        assertEquals(2L, core.calls.last { it.first == "emby.extRankingItems" }.second.long("page"))
        core.on("emby.extRankingItems") { throw CoreException("E_UNSUPPORTED", "扩展未开启", false) }
        rule.onNodeWithText("上一页").performClick()
        rule.onNodeWithText("扩展未开启", substring = true).assertIsDisplayed()
    }

    @Test fun rankingThemes() {
        val core = rankings()
        core.ret("emby.extRankingItems", arr(*(1..5).map { n -> buildJsonObject {
            put("source", "tmdb"); put("external_id", "$n"); put("media_type", "movie"); put("id", "external-$n"); put("rank", n); put("title", "远方的故事 · 第 $n 部")
            put("image_url", "fake:poster-$n"); put("rating", 8.6); put("subtitle", "2026 · 剧情")
        } }.toTypedArray()))
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "movie-4"); put("item_type", "Movie") })
        open(core)
        rule.onNodeWithText("热门榜").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-dark.png")
        rule.onNodeWithText("远方的故事 · 第 4 部").performClick()
        rule.onNodeWithText("详情目标：movie-4").assertIsDisplayed()
    }
    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun calendarRetryAndNarrowThemes() {
        val core = FakeCore().loggedIn()
        core.on("emby.extCalendar") { throw CoreException("E_NETWORK", "日历读取失败", true) }
        open(core, Route.Calendar)
        val before = core.calls.count { it.first == "emby.extCalendar" }
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "series-1"); put("item_type", "Series") })
        core.ret("emby.extCalendar", arr(buildJsonObject {
            put("source", "bangumi"); put("external_id", "42"); put("media_type", "anime"); put("title", "远方的故事与漫长旅途"); put("subtitle", "第二季 · 第十二集")
            put("weekday", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).dayOfWeek.value); put("rating", 8.8); put("image_url", "fake:calendar")
        }))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事与漫长旅途").assertIsDisplayed()
        assertEquals(before + 1, core.calls.count { it.first == "emby.extCalendar" })
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-light-narrow.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-dark-narrow.png")
        rule.onNodeWithText("查看入库").performClick()
        rule.onNodeWithText("详情目标：series-1").assertIsDisplayed()
    }
    @Test fun calendarWithoutSponsorOrderFetchesImmediately() {
        val core = FakeCore().loggedIn()
        core.ret("emby.extCalendar", arr())
        open(core, Route.Calendar)
        rule.onNodeWithText("追剧日历 · 赞助解锁").assertDoesNotExist()
        rule.onNodeWithText("本周 0 部 · 日本时区周历").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "emby.extCalendar" })
        assertEquals(0, core.calls.count { it.first == "prefs.getPrefs" || it.first.startsWith("system.afdian") })
    }
}
