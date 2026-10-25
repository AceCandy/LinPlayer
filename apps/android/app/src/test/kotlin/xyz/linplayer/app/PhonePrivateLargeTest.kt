package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.PrivateNeonHost
import xyz.linplayer.app.ui.pages.GatePage
import xyz.linplayer.app.ui.pages.PluginsPage
import xyz.linplayer.app.ui.pages.ServersPage
import xyz.linplayer.app.ui.pages.SettingsPage
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.pages.SourceCategoryPage
import xyz.linplayer.app.ui.pages.SourceHomePage
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.LpTheme

/** 隐私空间 + 1.3 倍字号下管理类页面的截图基线，用于复核截断、挤压与霓虹描边冲突。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhonePrivateLargeTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(true)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun core() = FakeCore().loggedIn().apply {
        ret("account.listAccounts", arr(
            account("p-a", "这是一台名字很长的家庭电影与剧集服务器", "客厅常用", true),
            account("p-b", "备用服务器", "周末观看", false),
        ))
        ret("account.probeAccounts", arr(buildJsonObject { put("server", "p-a"); put("ok", true) }))
        ret("plugin.pendingRestart", JsonPrimitive(false))
        ret("plugin.list", buildJsonObject {
            put("plugins", arr(
                buildJsonObject { put("id", "a"); put("name", "豆瓣评分"); put("version", "1.2.0"); put("author", "linplayer"); put("want", true) },
                buildJsonObject { put("id", "b"); put("name", "弹幕增强"); put("version", "0.9.1"); put("author", "someone"); put("want", false); put("status", "update"); put("update", "1.0.0") },
            ))
        })
        ret("source.continueWatching", JsonArray(emptyList()))
        ret("source.caps", buildJsonObject { put("home", true) })
        ret("source.home", buildJsonObject {
            put("categories", arr(*listOf("电影", "剧集", "动漫", "综艺").map { n -> buildJsonObject { put("id", n); put("name", n) } }.toTypedArray()))
            put("recommended", arr(*(1..6).map { i -> buildJsonObject {
                put("id", "v$i"); put("title", "一部名字相当长的推荐影片 $i"); put("year", 2020 + i)
                put("poster", buildJsonObject { put("url", "fake:poster-$i") })
            } }.toTypedArray()))
        })
        ret("source.category", buildJsonObject { put("items", arr()); put("next", "") })
        ret("player.getPlaybackPrefs", buildJsonObject { put("skip_intro", true) })
    }

    private fun shoot(name: String, start: Any) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core(), scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value, privateSpace = true) {
                CompositionLocalProvider(LocalApp provides app, LocalMotionScale provides 0f,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    PrivateNeonHost {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = start) {
                            composable<Route.Home> { SourceHomePage(nav, "src-a", "测试源") }
                            composable<Route.SourceCategory> { SourceCategoryPage(nav, it) }
                            composable<Route.Servers> { ServersPage(nav) }
                            composable<Route.Settings> { SettingsPage(nav) }
                            composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                            composable<Route.Plugins> { PluginsPage(nav, it) }
                            composable<Route.AddServer> { GatePage({}, embedded = true) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/private-large/$name-dark.png")
        rule.runOnIdle { dark.value = false }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/private-large/$name-light.png")
    }

    @Test fun settings() = shoot("settings", Route.Settings)
    @Test fun playerPrefs() = shoot("player", Route.SettingsSub("player"))
    @Test fun servers() = shoot("servers", Route.Servers)
    @Test fun plugins() = shoot("plugins", Route.Plugins(0))
    @Test fun sourceHome() = shoot("source", Route.Home)
    @Test fun sourceCategory() = shoot("category", Route.SourceCategory("src-a", buildJsonObject {
        put("id", "movie"); put("name", "电影")
        put("filters", arr(buildJsonObject {
            put("key", "area"); put("name", "地区")
            put("options", arr(*listOf("" to "全部", "cn" to "大陆", "us" to "欧美", "jp" to "日本", "kr" to "韩国").map { (v, n) ->
                buildJsonObject { put("value", v); put("name", n) } }.toTypedArray()))
        }))
    }.toString()))
    @Test fun addServer() = shoot("gate", Route.AddServer)
}
