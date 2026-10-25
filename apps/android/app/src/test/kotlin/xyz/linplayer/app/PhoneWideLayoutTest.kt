package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.account
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.library
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.tv.page
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.pages.LibraryPage
import xyz.linplayer.app.ui.pages.PluginsPage
import xyz.linplayer.app.ui.pages.SearchPage
import xyz.linplayer.app.ui.pages.ServersPage
import xyz.linplayer.app.ui.pages.SettingsPage
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpTheme

private const val LANDSCAPE = "w873dp-h393dp-mdpi"
private const val TABLET = "w1280dp-h800dp-mdpi"

/** 横屏手机与平板宽屏下主要页面的截图基线，用于复核网格列数、留白与居中。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class PhoneWideLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun shoot(name: String, route: Any, core: FakeCore = FakeCore().loggedIn(), ready: String? = null) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = false) {
                CompositionLocalProvider(LocalApp provides app) {
                    Box(Modifier.fillMaxSize().background(Lp.colors.bg)) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = route) {
                            composable<Route.Home> { HomePage(nav) }
                            composable<Route.Library> { LibraryPage(nav, it) }
                            composable<Route.Detail> { DetailPage(nav, it) }
                            composable<Route.Settings> { SettingsPage(nav) }
                            composable<Route.Servers> { ServersPage(nav) }
                            composable<Route.Plugins> { PluginsPage(nav, it) }
                            composable<Route.Search> { SearchPage(nav, it) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        ready?.let { t -> rule.waitUntil(5000) { rule.onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().isNotEmpty() } }
        rule.onRoot().captureRoboImage("build/wide-ui/$name.png")
    }

    private fun manage() = FakeCore().loggedIn().apply {
        ret("account.listAccounts", arr(
            account("w-a", "家庭影音服务器", "客厅常用", true),
            account("w-b", "备用服务器", "周末观看", false),
        ))
        ret("account.probeAccounts", arr(buildJsonObject { put("server", "w-a"); put("ok", true) }))
        ret("plugin.pendingRestart", JsonPrimitive(false))
        ret("plugin.list", buildJsonObject {
            put("plugins", arr(
                buildJsonObject { put("id", "a"); put("name", "豆瓣评分"); put("version", "1.2.0"); put("author", "linplayer"); put("want", true) },
                buildJsonObject { put("id", "b"); put("name", "弹幕增强"); put("version", "0.9.1"); put("author", "someone"); put("want", false) },
            ))
        })
    }

    private fun searchCore() = FakeCore().loggedIn().apply {
        ret("emby.search", page(*(0 until 8).map { item("s$it", "结果 $it", year = 2020 + it) }.toTypedArray()))
    }

    @Test @Config(qualifiers = LANDSCAPE) fun homeLandscape() = shoot("home-landscape", Route.Home)
    @Test @Config(qualifiers = TABLET) fun homeTablet() = shoot("home-tablet", Route.Home)
    @Test @Config(qualifiers = LANDSCAPE) fun libraryLandscape() = shoot("library-landscape", Route.Library("lib-0", "电影"), FakeCore().loggedIn().library())
    @Test @Config(qualifiers = TABLET) fun libraryTablet() = shoot("library-tablet", Route.Library("lib-0", "电影"), FakeCore().loggedIn().library())
    @Test @Config(qualifiers = LANDSCAPE) fun detailLandscape() = shoot("detail-landscape", Route.Detail("m1", "Movie"), FakeCore().loggedIn().movie())
    @Test @Config(qualifiers = TABLET) fun detailTablet() = shoot("detail-tablet", Route.Detail("m1", "Movie"), FakeCore().loggedIn().movie())
    @Test @Config(qualifiers = LANDSCAPE) fun settingsLandscape() = shoot("settings-landscape", Route.Settings)
    @Test @Config(qualifiers = TABLET) fun settingsTablet() = shoot("settings-tablet", Route.Settings)
    @Test @Config(qualifiers = TABLET) fun serversTablet() = shoot("servers-tablet", Route.Servers, manage())
    @Test @Config(qualifiers = TABLET) fun pluginsTablet() = shoot("plugins-tablet", Route.Plugins(0), manage())
    @Test @Config(qualifiers = LANDSCAPE) fun searchLandscape() = shoot("search-landscape", Route.Search(q = "结果"), searchCore(), ready = "结果 0")

    /** 平板上搜索结果按屏宽加列，不能固定 3 列把海报放大到 400dp 宽。 */
    @Test @Config(qualifiers = TABLET) fun searchTabletAddsColumns() {
        shoot("search-tablet", Route.Search(q = "结果"), searchCore(), ready = "结果 3")
        assertEquals(rule.onNodeWithText("结果 0").getBoundsInRoot().top, rule.onNodeWithText("结果 3").getBoundsInRoot().top)
    }
}
