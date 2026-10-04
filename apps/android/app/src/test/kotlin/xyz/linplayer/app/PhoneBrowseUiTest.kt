package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
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
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.page
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.AggregatePage
import xyz.linplayer.app.ui.pages.FavoritesPage
import xyz.linplayer.app.ui.pages.SearchPage
import xyz.linplayer.app.ui.theme.LpTheme

/** 浏览页的状态恢复与固定条件区回归，页面和导航均为真实 Compose。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneBrowseUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private val serverName = "这是一台名字非常长的电影与剧集服务器"

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun overview() = arr(buildJsonObject {
        put("server_id", "server-a"); put("server_name", serverName); put("active", true)
        put("counts", buildJsonObject { put("movie", 128); put("series", 42) })
        put("resume", arr(item("resume-1", "重逢", "Episode", series = "远方的故事",
            season = 1, episode = 12, runtime = 3600.0, resume = 1801.0)))
    })

    private fun open(core: FakeCore, route: Any = Route.Aggregate, fontScale: Float = 1f) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = route) {
                        composable<Route.Aggregate> { AggregatePage(nav) }
                        composable<Route.Favorites> { FavoritesPage(nav) }
                        composable<Route.Search> { SearchPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun aggregateInitialFailureIsNotEmptyAndCanRetry() {
        val core = FakeCore().loggedIn()
        core.on("emby.aggregateOverview") { throw CoreException("E_NETWORK", "聚合读取失败", true) }
        open(core)
        rule.onNodeWithText("还没有添加服务器").assertDoesNotExist()
        rule.onNodeWithText("重试").assertIsDisplayed()
        core.ret("emby.aggregateOverview", overview())
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(serverName).assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "emby.aggregateOverview" })
    }

    @Test fun aggregateRefreshFailureKeepsExistingContent() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", overview())
        open(core)
        core.on("emby.aggregateOverview") { throw CoreException("E_NETWORK", "聚合刷新失败", true) }
        rule.onNodeWithContentDescription("刷新").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(serverName).assertIsDisplayed()
        rule.onNodeWithText("还没有添加服务器").assertDoesNotExist()
        rule.onNodeWithText("重试").assertIsDisplayed()
    }
    @Test fun aggregateShowsResumeAndCurrentServerInBothThemes() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", overview())
        open(core, fontScale = 1.3f)
        rule.onNodeWithText("当前").assertIsDisplayed()
        rule.onNodeWithText("电影 128  ·  剧集 42").assertIsDisplayed()
        rule.onNodeWithText("S1E12 · 重逢", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("剩余 29:59", useUnmergedTree = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/browse-ui/aggregate-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/aggregate-dark-large.png")
    }

    @Test fun successfulEmptyOverviewStillOffersAddingServer() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", arr())
        open(core)
        rule.onNodeWithText("还没有添加服务器").assertIsDisplayed()
        rule.onNodeWithText("去添加服务器").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun favoriteSortStaysVisibleAndKeepsCommand() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(*(1..30).map {
            item("fav-$it", "收藏 $it", year = 2024, runtime = 3600.0, resume = 1000.0)
        }.toTypedArray()))
        open(core, Route.Favorites, fontScale = 1.3f)
        rule.onNodeWithText("更新时间").assertIsSelected()
        val top = rule.onNodeWithText("更新时间").fetchSemanticsNode().boundsInRoot.top
        rule.onRoot().captureRoboImage("build/browse-ui/favorites-light-large.png")
        rule.onNode(hasScrollToIndexAction()).performScrollToKey("fav-30")
        rule.onNodeWithText("更新时间").assertIsDisplayed()
        assertEquals(top, rule.onNodeWithText("更新时间").fetchSemanticsNode().boundsInRoot.top, .1f)
        rule.onNodeWithText("评分").performClick()
        rule.waitForIdle()
        assertEquals("评分", core.calls.last { it.first == "emby.listFavorites" }.second.str("sort"))
        rule.onNodeWithText("评分").assertIsSelected()
        rule.onAllNodesWithText("剩余 ", substring = true).assertCountEquals(0)
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/favorites-dark-large.png")
    }

    @Test fun searchConditionsKeepScopeAndManualAggregateBehavior() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page(*(1..12).map {
            item("search-$it", "结果 $it", year = 2024, rating = if (it == 1) 8.6 else null)
        }.toTypedArray()))
        core.ret("source.aggregateSearch", arr())
        open(core, Route.Search(q = "故事"), fontScale = 1.3f)
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.waitForIdle()
        rule.onNodeWithText("包括集").assertIsNotSelected()
        rule.onNodeWithText("结果 1").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/browse-ui/search-light-large.png")
        rule.onNodeWithText("包括集").performClick()
        rule.waitUntil(5000) { core.calls.count { it.first == "emby.search" } == 2 }
        assertTrue(core.calls.last { it.first == "emby.search" }.second?.get("types").toString().contains("Episode"))
        rule.onNodeWithText("聚合(含数据源)").performClick()
        rule.waitForIdle()
        assertEquals(0, core.calls.count { it.first == "source.aggregateSearch" })
        rule.onNode(hasText("搜索") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(5000) { core.calls.any { it.first == "source.aggregateSearch" } }
        assertEquals("故事", core.calls.last { it.first == "source.aggregateSearch" }.second.str("query"))
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/search-dark-large.png")
    }

    @Test fun librarySearchNeverOffersCrossSourceToggle() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page(item("scoped", "库内条目")))
        open(core, Route.Search(viewId = "lib-movie", q = "条目"))
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.onNodeWithText("聚合(含数据源)").assertDoesNotExist()
        assertEquals("lib-movie", core.calls.last { it.first == "emby.search" }.second.str("parent_id"))
    }

}
