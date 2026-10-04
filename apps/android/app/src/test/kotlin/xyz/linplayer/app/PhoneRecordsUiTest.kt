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
import kotlinx.serialization.json.JsonNull
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
import xyz.linplayer.app.tv.account
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DownloadsPage
import xyz.linplayer.app.ui.pages.HistoryPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneRecordsUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private lateinit var navigation: androidx.navigation.NavHostController
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun task(id: String, status: String, bytes: Long = 52428800) = buildJsonObject {
        put("id", id); put("title", "远方的故事 · $id"); put("status", status)
        put("total_bytes", 104857600); put("received_bytes", bytes); put("progress", bytes / 104857600.0)
        if (status == "failed") put("error", "服务器连接中断，请稍后重试")
    }
    private fun downloads() = FakeCore().loggedIn().apply {
        ret("download.setThreads", buildJsonObject { put("threads", 2) })
        ret("download.list", arr(task("进行中", "downloading"), task("已暂停", "paused"), task("失败任务", "failed")))
    }
    private fun record() = buildJsonObject {
        put("record_id", "record-a"); put("title", "重逢"); put("series_title", "远方的故事")
        put("scope_key", "http://emby-b.invalid:8096:user-b"); put("last_emby_item_id", "episode-12")
        put("last_position_ticks", 18000000000L); put("run_time_ticks", 36000000000L)
        put("last_played_at", 1791072000000L); put("played", false)
    }
    private fun open(core: FakeCore, route: Any = Route.Downloads) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    val nav = rememberNavController()
                    navigation = nav
                    NavHost(nav, startDestination = route) {
                        composable<Route.Downloads> { DownloadsPage(nav) }
                        composable<Route.History> { HistoryPage(nav) }
                        composable<Route.Detail> { Text("详情目标：" + it.toRoute<Route.Detail>().itemId) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun downloadsUseCoreFieldsAndExposeFailure() {
        open(downloads())
        rule.onAllNodesWithText("50%").assertCountEquals(3)
        rule.onNodeWithText("服务器连接中断，请稍后重试").assertIsDisplayed()
        rule.onNodeWithContentDescription("继续下载").assertIsDisplayed()
        rule.onNodeWithContentDescription("重试下载").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/records-ui/downloads-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/records-ui/downloads-dark-large.png")
    }
    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun downloadActionsRemainVisibleOnNarrowScreen() {
        open(downloads())
        rule.onNodeWithContentDescription("暂停下载").assertIsDisplayed()
        rule.onNodeWithContentDescription("继续下载").assertIsDisplayed()
        rule.onNodeWithContentDescription("重试下载").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/records-ui/downloads-light-narrow.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/records-ui/downloads-dark-narrow.png")
    }
    @Test fun downloadReadFailureIsNotEmptyAndCanRetry() {
        val core = downloads()
        core.on("download.list") { throw CoreException("E_NETWORK", "读取下载失败", true) }
        open(core)
        rule.onNodeWithText("下载队列是空的").assertDoesNotExist()
        rule.onNodeWithText("重试").assertIsDisplayed()
        core.ret("download.list", arr(task("恢复的任务", "paused")))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事 · 恢复的任务").assertIsDisplayed()
    }
    @Test fun downloadActionsKeepDeletionSemantics() {
        val core = downloads()
        core.ret("download.resume", JsonNull)
        core.ret("download.remove", JsonNull)
        core.ret("download.clearCompleted", JsonNull)
        open(core)
        rule.onNodeWithContentDescription("重试下载").performClick()
        assertEquals("失败任务", core.calls.last { it.first == "download.resume" }.second.str("id"))
        rule.onNodeWithContentDescription("清除已完成").performClick()
        assertEquals(0, core.calls.count { it.first == "download.remove" })
        rule.onAllNodesWithContentDescription("删除任务与文件")[0].performClick()
        assertEquals("进行中", core.calls.last { it.first == "download.remove" }.second.str("id"))
        assertEquals(1, core.calls.count { it.first == "download.clearCompleted" })
    }
    @Test fun downloadsRefreshWithoutEventsAndKeepRowsOnFailure() {
        val core = downloads()
        open(core)
        rule.onAllNodesWithText("/s", substring = true).assertCountEquals(0)
        val before = core.calls.count { it.first == "download.list" }
        core.ret("download.list", arr(task("进行中", "downloading", 78643200)))
        rule.mainClock.advanceTimeBy(2100)
        rule.waitUntil(5000) { core.calls.count { it.first == "download.list" } > before }
        rule.onNodeWithText("75%").assertIsDisplayed()
        rule.onAllNodesWithText("/s", substring = true).assertCountEquals(1)
        core.on("download.list") { throw CoreException("E_NETWORK", "暂时读不到进度", true) }
        rule.mainClock.advanceTimeBy(2100)
        rule.waitForIdle()
        rule.onNodeWithText("远方的故事 · 进行中").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()
        rule.onAllNodesWithText("/s", substring = true).assertCountEquals(0)
        core.ret("source.history", arr())
        core.ret("emby.watchHistoryList", arr())
        rule.runOnIdle { navigation.navigate(Route.History) { popUpTo<Route.Downloads> { inclusive = true } } }
        rule.waitForIdle()
        val afterLeaving = core.calls.count { it.first == "download.list" }
        rule.mainClock.advanceTimeBy(4200)
        rule.waitForIdle()
        assertEquals("离页必须停止下载轮询", afterLeaving, core.calls.count { it.first == "download.list" })
    }
    @Test fun historyKeepsScopeSourceAndCrossServerNavigation() {
        val core = FakeCore().loggedIn()
        core.ret("source.history", arr())
        core.ret("emby.watchHistoryList", arr(record()))
        core.ret("account.setActiveServer", JsonNull)
        core.ret("account.listAccounts", arr(account("http://emby-b.invalid:8096", "服务器 B", "", false)))
        open(core, Route.History)
        rule.onNodeWithText("全部服务器").performClick()
        rule.waitForIdle()
        assertEquals("false", core.calls.last { it.first == "emby.watchHistoryList" }.second?.get("current_only").toString())
        rule.onNodeWithText("服务器 B").assertIsDisplayed()
        rule.onNodeWithText("已观看 30:00 / 1:00:00").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/records-ui/history-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/records-ui/history-dark-large.png")
        rule.onNodeWithText("远方的故事 · 重逢").performClick()
        rule.onNodeWithText("详情目标：episode-12").assertIsDisplayed()
        assertEquals("http://emby-b.invalid:8096", core.calls.last { it.first == "account.setActiveServer" }.second.str("server_id"))
    }
    @Test fun historyReadFailureDoesNotPretendToBeEmpty() {
        val core = FakeCore().loggedIn()
        core.ret("source.history", arr())
        core.on("emby.watchHistoryList") { throw CoreException("E_NETWORK", "读取历史失败", true) }
        open(core, Route.History)
        rule.onNodeWithText("还没有观看记录").assertDoesNotExist()
        core.ret("emby.watchHistoryList", arr(record()))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事 · 重逢").assertIsDisplayed()
    }
}
