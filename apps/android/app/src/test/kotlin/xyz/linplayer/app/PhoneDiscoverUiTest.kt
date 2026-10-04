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
        ret("emby.rankingCategories", arr(buildJsonObject { put("id", "hot"); put("label", "热门榜") }))
        ret("emby.rankingFetch", arr(buildJsonObject {
            put("id", "external-1"); put("rank", 1); put("title", "远方的故事"); put("rating", 8.6)
        }))
    }
    @Test fun rankingRetryFetchesSelectedCategory() {
        val core = rankings()
        core.on("emby.rankingFetch") { throw CoreException("E_NETWORK", "榜单读取失败", true) }
        open(core)
        val before = core.calls.count { it.first == "emby.rankingFetch" }
        core.ret("emby.rankingFetch", arr(buildJsonObject {
            put("id", "external-1"); put("rank", 1); put("title", "远方的故事")
        }))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事").assertIsDisplayed()
        assertEquals(before + 1, core.calls.count { it.first == "emby.rankingFetch" })
        assertEquals("hot", core.calls.last { it.first == "emby.rankingFetch" }.second.str("category_id"))
    }
    @Test fun rankingThemes() {
        val core = rankings()
        core.ret("emby.rankingFetch", arr(*(1..5).map { n -> buildJsonObject {
            put("id", "external-$n"); put("rank", n); put("title", "远方的故事 · 第 $n 部")
            put("image_url", "fake:poster-$n"); put("rating", 8.6); put("subtitle", "2026 · 剧情")
        } }.toTypedArray()))
        open(core)
        rule.onNodeWithText("热门榜").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-dark.png")
        rule.onNodeWithText("远方的故事 · 第 4 部").performClick()
        rule.onNodeWithText("搜索目标：远方的故事 · 第 4 部").assertIsDisplayed()
    }
    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun calendarRetryAndNarrowThemes() {
        val core = FakeCore().loggedIn()
        core.ret("prefs.getPrefs", buildJsonObject { put("calendar_unlock_order", "test-order") })
        core.on("sync.bangumiCalendar") { throw CoreException("E_NETWORK", "日历读取失败", true) }
        open(core, Route.Calendar)
        val before = core.calls.count { it.first == "sync.bangumiCalendar" }
        core.ret("sync.calendarLibrary", buildJsonObject {
            put("0", buildJsonObject { put("playable", false); put("series_id", "series-1") })
        })
        core.ret("sync.bangumiCalendar", arr(buildJsonObject {
            put("title", "远方的故事与漫长旅途"); put("subtitle", "第二季 · 第十二集")
            put("weekday", java.time.LocalDate.now().dayOfWeek.value); put("rating", 8.8); put("image_url", "fake:calendar")
        }))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事与漫长旅途").assertIsDisplayed()
        assertEquals(before + 1, core.calls.count { it.first == "sync.bangumiCalendar" })
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-light-narrow.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-dark-narrow.png")
        rule.onNodeWithText("已入库").performClick()
        rule.onNodeWithText("详情目标：series-1").assertIsDisplayed()
    }
    @Test fun lockedCalendarDoesNotFetch() {
        val core = FakeCore().loggedIn()
        open(core, Route.Calendar)
        rule.onNodeWithText("追剧日历 · 赞助解锁").assertIsDisplayed()
        assertEquals(0, core.calls.count { it.first == "sync.bangumiCalendar" })
    }
}
