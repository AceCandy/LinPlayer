package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
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
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.page
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.BrowsePage
import xyz.linplayer.app.ui.pages.ExtensionsPage
import xyz.linplayer.app.ui.pages.FacetPage
import xyz.linplayer.app.ui.pages.LinesPage
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpTheme

private const val PHONE = "w393dp-h873dp-mdpi"
private const val TABLET = "w1280dp-h800dp-mdpi"

/** 此前没有截图基线的次级页面：筛选结果、线路、本机文件夹、扩展组件。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class PhoneMinorPagesTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun shoot(name: String, route: Any, core: FakeCore, ready: String? = null) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                Box(Modifier.fillMaxSize().background(Lp.colors.bg)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = route) {
                        composable<Route.Facet> { FacetPage(nav, it) }
                        composable<Route.Lines> { LinesPage(nav, it) }
                        composable<Route.Browse> { BrowsePage(nav) }
                        composable<Route.Extensions> { ExtensionsPage(nav) }
                    }
                }
            } }
        }
        rule.waitForIdle()
        ready?.let { t -> rule.waitUntil(5000) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() } }
        rule.onRoot().captureRoboImage("build/minor-pages/$name.png")
    }

    private fun facet() = FakeCore().loggedIn().apply {
        ret("emby.listItemsPage", page(*(0 until 9).map { item("f$it", "科幻片 $it", year = 2015 + it) }.toTypedArray()))
    }

    private fun lines() = FakeCore().loggedIn().apply {
        ret("account.listAccounts", arr(buildJsonObject {
            put("server", "srv"); put("name", "家庭影音服务器"); put("active_line", 1)
            putJsonArray("lines") {
                addJsonObject { put("name", "家宽直连"); put("url", "http://a.example") }
                addJsonObject { put("name", "公网中转"); put("url", "http://b.example") }
                addJsonObject { put("url", "http://c.example") }
            }
        }))
    }

    private fun browse() = FakeCore().loggedIn().apply {
        ret("source.currentSource", buildJsonObject { put("server_id", "/storage/Movies"); put("server_name", "电影") })
        ret("account.listAccounts", arr())
        ret("source.listDir", arr(
            buildJsonObject { put("id", "d1"); put("name", "漫威系列"); put("is_dir", true) },
            buildJsonObject { put("id", "d2"); put("name", "纪录片"); put("is_dir", true) },
            buildJsonObject { put("id", "v1"); put("name", "沙丘2.2160p.mkv"); put("is_video", true); put("size", 66_120_000_000L) },
            buildJsonObject { put("id", "v2"); put("name", "奥本海默.1080p.mp4"); put("is_video", true); put("size", 8_400_000_000L) },
        ))
    }

    private fun browseEmpty() = FakeCore().loggedIn().apply {
        // 真实核心在没有文件源时回 null，不是空对象
        ret("source.currentSource", kotlinx.serialization.json.JsonNull)
        ret("account.listAccounts", arr())
    }

    private fun extensions() = FakeCore().loggedIn().apply {
        ret("plugin.list", buildJsonObject {
            put("plugins", arr(buildJsonObject { put("id", "a"); put("name", "影视聚合"); putJsonArray("components") { add(kotlinx.serialization.json.JsonPrimitive("jar-runtime")) } }))
        })
    }

    @Test @Config(qualifiers = PHONE) fun facetPhone() = shoot("facet-phone", Route.Facet("genre", "科幻", "科幻"), facet(), "科幻片 0")
    @Test @Config(qualifiers = TABLET) fun facetTablet() = shoot("facet-tablet", Route.Facet("genre", "科幻", "科幻"), facet(), "科幻片 0")
    @Test @Config(qualifiers = PHONE) fun linesPhone() = shoot("lines-phone", Route.Lines("srv", "家庭影音服务器"), lines(), "公网中转")
    @Test @Config(qualifiers = TABLET) fun linesTablet() = shoot("lines-tablet", Route.Lines("srv", "家庭影音服务器"), lines(), "公网中转")
    @Test @Config(qualifiers = PHONE) fun browsePhone() = shoot("browse-phone", Route.Browse, browse(), "纪录片")
    @Test @Config(qualifiers = TABLET) fun browseTablet() = shoot("browse-tablet", Route.Browse, browse(), "纪录片")
    @Test @Config(qualifiers = PHONE) fun browseEmptyPhone() = shoot("browse-empty-phone", Route.Browse, browseEmpty(), "还没有添加本机文件夹")
    @Test @Config(qualifiers = PHONE) fun extensionsPhone() = shoot("extensions-phone", Route.Extensions, extensions(), "影视聚合")
}
