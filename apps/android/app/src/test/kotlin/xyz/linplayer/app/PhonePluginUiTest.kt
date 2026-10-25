package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.PluginDetailPage
import xyz.linplayer.app.ui.pages.PluginsPage
import xyz.linplayer.app.ui.theme.LpTheme

/** 插件页四个标签的版式截图与基础可见性。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhonePluginUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun core() = FakeCore().loggedIn().apply {
        ret("plugin.pendingRestart", JsonPrimitive(false))
        ret("plugin.list", buildJsonObject {
            put("plugins", arr(
                buildJsonObject { put("id", "a"); put("name", "豆瓣评分"); put("version", "1.2.0"); put("author", "linplayer"); put("want", true) },
                buildJsonObject { put("id", "b"); put("name", "弹幕增强"); put("version", "0.9.1"); put("author", "someone"); put("want", false); put("status", "update"); put("update", "1.0.0") },
            ))
        })
        ret("plugin.market", buildJsonObject {
            put("entries", arr(
                buildJsonObject { put("id", "a"); put("name", "豆瓣评分"); put("author", "linplayer"); put("repoName", "官方市场"); put("officialMark", true); put("installed", "1.2.0"); put("best", buildJsonObject { put("version", "1.2.0") }) },
                buildJsonObject { put("id", "c"); put("name", "字幕下载"); put("author", "subs"); put("repoName", "社区仓库"); put("best", buildJsonObject { put("version", "2.0.0") }) },
            ))
        })
        ret("plugin.takeovers", arr(buildJsonObject {
            put("slot", "page:home"); put("current", "")
            put("candidates", buildJsonArray { add(buildJsonObject { put("plugin_id", "a"); put("name", "豆瓣评分") }) })
        }))
        ret("plugin.repos", buildJsonObject {
            put("repos", arr(
                buildJsonObject { put("url", "https://official.invalid/index.json"); put("name", "官方市场"); put("official", true) },
                buildJsonObject { put("url", "https://community.invalid/index.json"); put("name", "社区仓库") },
            ))
            put("github_prefix", ""); put("auto_update", true)
        })
        ret("plugin.detail", buildJsonObject {
            put("info", buildJsonObject { put("name", "豆瓣评分"); put("version", "1.2.0"); put("loadMs", 120); put("prev", "1.1.0"); put("update", "1.3.0") })
            put("settings", arr(
                buildJsonObject { put("key", "show"); put("type", "toggle"); put("title", "在详情页显示评分"); put("default", true) },
                buildJsonObject { put("key", "src"); put("type", "select"); put("title", "评分来源")
                    put("options", arr(buildJsonObject { put("value", "db"); put("label", "豆瓣") }, buildJsonObject { put("value", "imdb"); put("label", "IMDb") })) },
                buildJsonObject { put("key", "token"); put("type", "text"); put("title", "接口令牌"); put("description", "可留空") },
            ))
            put("values", buildJsonObject { put("src", "db") })
            put("manifest", buildJsonObject { put("contributes", buildJsonObject {
                put("pages", arr(buildJsonObject { put("id", "rank"); put("title", "豆瓣榜单") })) }) })
            put("contributes", buildJsonArray { add(JsonPrimitive("详情页评分")); add(JsonPrimitive("榜单页面")) })
            put("usage", buildJsonObject { put("kv", 2048); put("data", 10240); put("cache", 524288) })
        })
    }

    private fun open(tab: Int, core: FakeCore = core(), start: Any = Route.Plugins(tab)) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = start) {
                        composable<Route.Plugins> { PluginsPage(nav, it) }
                        composable<Route.PluginDetail> { PluginDetailPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shoot(name: String) {
        rule.onRoot().captureRoboImage("build/plugin-ui/$name-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/plugin-ui/$name-dark.png")
    }

    @Test fun installedTab() { open(0); rule.onNodeWithText("弹幕增强").assertIsDisplayed(); shoot("installed") }
    @Test fun marketTab() { open(1); rule.onNodeWithText("字幕下载").assertIsDisplayed(); shoot("market") }
    @Test fun takeoverTab() { open(2); rule.onNodeWithText("首页").assertIsDisplayed(); shoot("takeover") }
    @Test fun repoTab() { open(3); rule.onNodeWithText("社区仓库").assertIsDisplayed(); shoot("repo") }
    @Test fun detailPage() {
        open(0, start = Route.PluginDetail("a"))
        rule.onNodeWithText("在详情页显示评分").assertIsDisplayed()
        shoot("detail")
        rule.onRoot().performTouchInput { swipeUp(startY = bottom * .9f, endY = top, durationMillis = 300) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/plugin-ui/detail-bottom-dark.png")
    }
    @Test fun selectSettingKeepsSingleChoice() {
        val core = core().apply { ret("plugin.setSetting", kotlinx.serialization.json.JsonNull) }
        open(0, core, start = Route.PluginDetail("a"))
        rule.onNode(hasText("豆瓣") and hasClickAction()).assertIsSelected()
        rule.onNode(hasText("IMDb") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("IMDb") and hasClickAction()).assertIsSelected()
        rule.onNode(hasText("豆瓣") and hasClickAction()).assertIsNotSelected()
    }

    @Test fun settingsSectionsUseGroupedPanel() {
        val core = core().apply {
            ret("plugin.settingsSections", arr(buildJsonObject {
                put("plugin_id", "a"); put("name", "豆瓣评分"); put("anchor", "settings"); put("title", "评分显示")
                put("settings", arr(
                    buildJsonObject { put("key", "show"); put("type", "toggle"); put("title", "在详情页显示评分"); put("default", true) },
                    buildJsonObject { put("key", "token"); put("type", "button"); put("title", "刷新评分缓存"); put("action", "refresh") },
                ))
            }))
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()
                        .background(xyz.linplayer.app.ui.theme.Lp.colors.bg)) {
                        xyz.linplayer.app.ui.plugin.PhonePluginSections("settings")
                    }
                }
            }
        }
        rule.waitUntil(5000) { rule.onAllNodesWithText("在详情页显示评分").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("评分显示 · 来自 豆瓣评分").assertIsDisplayed()
        shoot("settings-sections")
    }
    @Test fun onlyThirdPartyRepoHasDeleteAndRowTapDoesNotDelete() {
        val core = core().apply { ret("plugin.removeRepo", kotlinx.serialization.json.JsonNull) }
        open(3, core)
        rule.onNodeWithText("社区仓库").performClick()
        org.junit.Assert.assertEquals(0, core.calls.count { it.first == "plugin.removeRepo" })
        rule.onAllNodesWithContentDescription("删除仓库").assertCountEquals(1)
        rule.onNodeWithContentDescription("删除仓库").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("https://community.invalid/index.json",
            core.calls.last { it.first == "plugin.removeRepo" }.second.str("url"))
    }
}
