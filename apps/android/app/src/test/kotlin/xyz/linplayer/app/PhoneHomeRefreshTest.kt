package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeDown
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.View
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.pages.latestHeroItems
import xyz.linplayer.app.ui.theme.LpTheme

/** 真实手机首页的导航恢复、下拉手势与请求次数回归。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneHomeRefreshTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nav: NavHostController

    @After fun clean() {
        PageCache.clear()
        scope.cancel()
    }

    private fun openHome(core: FakeCore) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = false) {
                CompositionLocalProvider(LocalApp provides app) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Home) {
                        composable<Route.Home> { HomePage(nav) }
                        composable<Route.Settings> { Text("其它页面") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun homeDoesNotRequestNextUp() {
        val core = FakeCore().loggedIn()
        openHome(core)
        assertEquals(0, core.calls.count { it.first == "emby.listNextUp" })
        assertEquals(1, core.calls.count { it.first == "emby.listResume" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

    @Test fun returningHomeUpdatesResumeWithoutReloadingRecommendations() {
        val core = FakeCore().loggedIn()
        openHome(core)
        val heroCalls = core.calls.count { it.first == "emby.listRandom" }
        val viewCalls = core.calls.count { it.first == "emby.views" }
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.waitForIdle()
        core.ret("emby.listResume", arr(item("new-resume", "更新后的续播", runtime = 3600.0, resume = 1800.0)))
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("更新后的续播").assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.listResume" })
        assertEquals(heroCalls, core.calls.count { it.first == "emby.listRandom" })
        assertEquals(viewCalls, core.calls.count { it.first == "emby.views" })
    }

    @Test fun pullDownRefreshesHome() {
        val core = FakeCore().loggedIn()
        openHome(core)
        core.ret("emby.listResume", arr(item("fresh", "下拉后的续播", runtime = 3600.0, resume = 900.0)))
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
        rule.onNodeWithText("下拉后的续播").assertExists()
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
        assertEquals(2, core.calls.count { it.first == "emby.listResume" })
        assertEquals(2, core.calls.count { it.first == "emby.views" })
        assertEquals(0, core.calls.count { it.first == "emby.listNextUp" })
    }
    @Test fun pullDownCanRetryAfterFailedResumeRequest() {
        val core = FakeCore().loggedIn()
        openHome(core)
        core.on("emby.listResume") { throw CoreException("E_NETWORK", "测试网络失败", true) }
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "emby.listResume" })
        core.ret("emby.listResume", arr(item("retry", "重试后的续播", runtime = 3600.0, resume = 900.0)))
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
        rule.onNodeWithText("重试后的续播").assertExists()
        assertEquals(3, core.calls.count { it.first == "emby.listResume" })
    }

    @Test fun heroReusesLatestRequestWhenLibraryScrollsIntoView() {
        val core = FakeCore().loggedIn()
        openHome(core)
        rule.onNodeWithContentDescription("沙丘 2").assertExists()
        assertEquals(1, core.calls.count { it.first == "emby.listLatest" && it.second.str("parent_id") == "lib-movie" })
        rule.onNodeWithText("媒体库").performScrollTo()
        rule.waitForIdle()
        assertEquals(1, core.calls.count { it.first == "emby.listLatest" && it.second.str("parent_id") == "lib-movie" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

    @Test fun heroUsesNextLibraryWhenFirstIsEmpty() {
        val core = FakeCore().loggedIn()
        core.on("emby.listLatest") { a ->
            if (a.str("parent_id") == "lib-movie") arr()
            else arr(item("latest-series", "最新剧集", "Series"))
        }
        openHome(core)
        rule.onNodeWithContentDescription("最新剧集").assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.listLatest" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

    @Test fun heroKeepsLibraryOrderDeduplicatesAndCapsAtFive() {
        val entries = (1..7).map { Item("id-$it", "影片 $it", "Movie") }
        val views = listOf(View("a", "A", "movies"), View("b", "B", "movies"))
        val result = latestHeroItems(views, mapOf("b" to entries.drop(1), "a" to entries.take(3)))
        assertEquals(entries.take(5), result)
    }

    @Test fun heroUsesNextLibraryWhenFirstRequestFails() {
        val core = FakeCore().loggedIn()
        core.on("emby.listLatest") { a ->
            if (a.str("parent_id") == "lib-movie") throw CoreException("E_NETWORK", "测试网络失败", true)
            else arr(item("fallback", "后续库的最新条目", "Series"))
        }
        openHome(core)
        rule.onNodeWithContentDescription("后续库的最新条目").assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.listLatest" })
    }

    @Test fun emptyLatestListsDoNotLeaveHeroSkeleton() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listLatest", arr())
        openHome(core)
        val libraryTop = rule.onNodeWithText("媒体库").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("空库的轮播仍占着大块骨架", libraryTop < 500f)
        assertEquals(2, core.calls.count { it.first == "emby.listLatest" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

}
