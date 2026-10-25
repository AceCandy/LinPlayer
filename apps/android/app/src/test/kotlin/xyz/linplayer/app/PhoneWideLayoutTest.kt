package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
import xyz.linplayer.app.tv.library
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.pages.LibraryPage
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

    private fun shoot(name: String, route: Any, core: FakeCore = FakeCore().loggedIn()) {
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
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/wide-ui/$name.png")
    }

    @Test @Config(qualifiers = LANDSCAPE) fun homeLandscape() = shoot("home-landscape", Route.Home)
    @Test @Config(qualifiers = TABLET) fun homeTablet() = shoot("home-tablet", Route.Home)
    @Test @Config(qualifiers = LANDSCAPE) fun libraryLandscape() = shoot("library-landscape", Route.Library("lib-0", "电影"), FakeCore().loggedIn().library())
    @Test @Config(qualifiers = TABLET) fun libraryTablet() = shoot("library-tablet", Route.Library("lib-0", "电影"), FakeCore().loggedIn().library())
    @Test @Config(qualifiers = LANDSCAPE) fun detailLandscape() = shoot("detail-landscape", Route.Detail("m1", "Movie"), FakeCore().loggedIn().movie())
    @Test @Config(qualifiers = TABLET) fun detailTablet() = shoot("detail-tablet", Route.Detail("m1", "Movie"), FakeCore().loggedIn().movie())
    @Test @Config(qualifiers = LANDSCAPE) fun settingsLandscape() = shoot("settings-landscape", Route.Settings)
    @Test @Config(qualifiers = TABLET) fun settingsTablet() = shoot("settings-tablet", Route.Settings)
}
