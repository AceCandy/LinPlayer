package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import xyz.linplayer.app.tv.account
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.ServersPage
import xyz.linplayer.app.ui.pages.SettingsPage
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.theme.LightColors
import xyz.linplayer.app.ui.theme.LpTheme

/** 服务器状态、管理动作和设置入口的真实页面回归。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneManagementUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private val longName = "这是一台名字很长的家庭电影与剧集服务器"

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun servers() = FakeCore().loggedIn().apply {
        ret("account.listAccounts", arr(
            account("management-a", longName, "客厅常用 · 电影与剧集", true),
            account("management-b", "备用服务器", "周末观看", false),
            account("management-c", "尚未检测的服务器", "", false),
        ))
        ret("account.probeAccounts", arr(
            buildJsonObject { put("server", "management-a"); put("ok", true) },
            buildJsonObject { put("server", "management-b"); put("ok", false) },
        ))
        ret("account.setActiveServer", JsonNull)
    }

    private fun open(core: FakeCore, route: Any = Route.Servers, scale: Float = 1.3f) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = route) {
                        composable<Route.Servers> { ServersPage(nav) }
                        composable<Route.Settings> { SettingsPage(nav) }
                        composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun successfulProbeShowsConnectedInsteadOfUnknown() {
        open(servers())
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("ok 探测必须呈现成功色", (0 until pixels.width).any { x ->
            (0 until pixels.height).any { y -> pixels[x, y] == LightColors.ok }
        })
        rule.onNodeWithText("可连接").assertIsDisplayed()
        rule.onNodeWithText("连接失败").assertIsDisplayed()
        rule.onNodeWithText("未检测").assertIsDisplayed()
        rule.onNodeWithText("当前").assertIsDisplayed()
        rule.onNodeWithText("客厅常用 · 电影与剧集").assertIsDisplayed()
        val titleBounds = rule.onNodeWithText(longName).fetchSemanticsNode().boundsInRoot
        val currentBounds = rule.onNodeWithText("当前").fetchSemanticsNode().boundsInRoot
        assertTrue("长名称不能覆盖当前标记", titleBounds.right <= currentBounds.left)
        rule.onRoot().captureRoboImage("build/management-ui/servers-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/management-ui/servers-dark-large.png")
    }

    @Test fun serverTapSwitchesAndDeleteStillNeedsConfirmation() {
        val core = servers()
        open(core)
        rule.onNodeWithText("备用服务器").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("management-b", core.calls.last { it.first == "account.setActiveServer" }.second.str("server_id"))
        rule.onNodeWithText("备用服务器").performTouchInput { longClick() }
        rule.onNodeWithText("编辑").assertIsDisplayed()
        rule.onNodeWithText("删除").performClick()
        rule.onNodeWithText("这台服务器的账号、备注、图标和线路都会一起删掉。已下载的文件不受影响。").assertIsDisplayed()
        assertEquals(0, core.calls.count { it.first == "account.removeAccount" })
        rule.onNodeWithText("取消").performClick()
        assertEquals(0, core.calls.count { it.first == "account.removeAccount" })
    }

    @Test fun settingsGroupsKeepNavigationAndBothThemes() {
        val core = FakeCore().loggedIn()
        core.ret("plugin.sidebar", arr(buildJsonObject {
            put("id", "test-plugin:entry"); put("page", "test-page"); put("title", "测试插件入口")
        }))
        open(core, Route.Settings)
        rule.onNodeWithText("播放与外观").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/management-ui/settings-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/management-ui/settings-dark-large.png")
        for (label in listOf("多线程加载", "测试插件入口", "已屏蔽的内容", "备份与还原", "文件浏览",
            "存储与数据目录", "Trakt / Bangumi 账号", "插件", "扩展组件", "更新", "关于")) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            rule.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
        }
        rule.onRoot().captureRoboImage("build/management-ui/settings-bottom-dark-large.png")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("外观"))
        rule.onNodeWithText("外观").performClick()
        rule.onNodeWithText("主题").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/management-ui/appearance-dark-large.png")
        rule.runOnIdle { dark.value = false }
        rule.onRoot().captureRoboImage("build/management-ui/appearance-light-large.png")
    }

    @Test fun playerSwitchKeepsCommandAndRollsBackOnFailure() {
        val core = FakeCore().loggedIn()
        core.ret("player.getPlaybackPrefs", buildJsonObject { put("skip_intro", false) })
        core.on("player.setPlaybackPrefs") { throw CoreException("E_NETWORK", "保存失败", true) }
        open(core, Route.SettingsSub("player"))
        rule.onRoot().captureRoboImage("build/management-ui/player-light-large.png")
        val toggle = rule.onNodeWithContentDescription("跳过片头")
        toggle.assertIsOff().performClick()
        rule.waitForIdle()
        assertEquals("true", core.calls.last { it.first == "player.setPlaybackPrefs" }.second?.get("skip_intro").toString())
        toggle.assertIsOff()
        core.ret("player.setPlaybackPrefs", buildJsonObject { put("skip_intro", true) })
        toggle.performClick()
        rule.waitForIdle()
        toggle.assertIsOn()
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/management-ui/player-dark-large.png")
    }
}
