package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.AggregatePage
import xyz.linplayer.app.ui.theme.LpTheme

/** 聚合页复用首页快照、后台刷新与多服务器隔离回归。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneAggregateCacheTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nav: NavHostController
    private lateinit var app: AppState
    private var cachedPartial: JsonElement? = null
    private var pending: CompletableDeferred<JsonElement>? = null
    private val core = FakeCore().loggedIn()
    private val imageRequests = java.util.concurrent.CopyOnWriteArrayList<String>()

    @After fun clean() { pending?.cancel(); PageCache.clear(); scope.cancel(); app.bg.cancel() }

    private fun seedHome(name: String) {
        PageCache.put("home.accounts", listOf(Account("a", "http://emby-a.invalid", "服务器 A",
            null, null, true, "emby", aggregate = true)))
        PageCache.put("home.resume", Block.Ok(Item.list(arr(item("home", name)))))
    }

    private fun overview(name: String, other: Boolean = false): JsonElement = arr(buildJsonObject {
        put("server_id", if (other) "http://emby-b.invalid" else "http://emby-a.invalid")
        put("server_name", if (other) "服务器 B" else "服务器 A")
        put("active", !other)
        put("counts", buildJsonObject { put("movie", 30) })
        put("resume", arr(item(if (other) "other" else "fresh", name)))
    })

    private fun open(seed: () -> Unit = {}) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext()) { imageRequests.add(it) }
        app = AppState(object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.aggregateOverview" && pending != null) {
                    core.calls += command to args
                    cachedPartial?.let { onPartial?.invoke(it) }
                    return pending!!.await()
                }
                return core.callJson(command, args, onPartial)
            }
        }, scope)
        runBlocking { app.boot() }
        seed()
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Aggregate) {
                        composable<Route.Aggregate> { AggregatePage(nav) }
                        composable<Route.Settings> { Text("其它页面") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun sharedCoreSnapshotIsVisibleBeforeRealtimeResponse() {
        pending = CompletableDeferred()
        cachedPartial = overview("核心持久快照")
        open()
        rule.onNodeWithText("核心持久快照").assertIsDisplayed()
        rule.runOnIdle { pending!!.complete(overview("实时刷新资料")) }
        rule.waitForIdle()
        rule.onNodeWithText("实时刷新资料").assertIsDisplayed()
        rule.onNodeWithText("核心持久快照").assertDoesNotExist()
    }

    @Test fun homeResumeIsVisibleBeforeOverviewReturnsWithoutAnotherResumeRequest() {
        pending = CompletableDeferred()
        open { seedHome("首页已取的续播") }
        rule.onNodeWithText("首页已取的续播").assertIsDisplayed()
        rule.onNodeWithText("服务器").assertDoesNotExist()
        rule.onNodeWithText("下载").assertIsDisplayed()
        assertEquals(0, core.calls.count { it.first == "emby.listResume" })
        assertEquals(1, core.calls.count { it.first == "emby.aggregateOverview" })
        rule.runOnIdle { pending!!.complete(overview("异步更新的续播")) }
        rule.waitForIdle()
        rule.onNodeWithText("异步更新的续播").assertIsDisplayed()
        rule.onNodeWithText("电影 30").assertIsDisplayed()
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.onNodeWithText("其它页面").assertIsDisplayed()
        pending = CompletableDeferred()
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("异步更新的续播").assertIsDisplayed()
        rule.onNodeWithText("首页已取的续播").assertDoesNotExist()
    }

    @Test fun returningShowsCachedCountsAndKeepsThemWhenRefreshFails() {
        core.ret("emby.aggregateOverview", overview("缓存续播"))
        open()
        rule.onNodeWithText("电影 30").assertIsDisplayed()
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.onNodeWithText("其它页面").assertIsDisplayed()
        pending = CompletableDeferred()
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "emby.aggregateOverview" })
        rule.onNodeWithText("电影 30").assertIsDisplayed()
        rule.onNodeWithText("缓存续播").assertIsDisplayed()
        rule.runOnIdle { pending!!.completeExceptionally(CoreException("E_NETWORK", "刷新失败", true)) }
        rule.waitForIdle()
        rule.onNodeWithText("缓存续播").assertIsDisplayed()
        rule.onNodeWithText("电影 30").assertIsDisplayed()
    }

    @Test fun newerHomeResumeOnlyReplacesCurrentServerIncludingEmptyList() {
        core.ret("emby.aggregateOverview", JsonArray(overview("旧续播").arr() + overview("其它服续播", true).arr()))
        open()
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.onNodeWithText("其它页面").assertIsDisplayed()
        seedHome("最新首页续播")
        pending = CompletableDeferred()
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("最新首页续播").assertIsDisplayed()
        rule.onNodeWithText("其它服续播").assertExists()
        rule.onNodeWithText("旧续播").assertDoesNotExist()
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.onNodeWithText("其它页面").assertIsDisplayed()
        PageCache.put("home.resume", Block.Ok(emptyList<Item>()))
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("最新首页续播").assertDoesNotExist()
        rule.onNodeWithText("其它服续播").assertIsDisplayed()
    }

    @Test fun failedResumeKeepsHomeSnapshotButAcceptsSuccessfulNewCounts() {
        pending = CompletableDeferred()
        open { seedHome("首页已取的续播") }
        val failed = overview("不应显示的失败结果").arr().single().obj()!!
        rule.runOnIdle { pending!!.complete(arr(JsonObject(failed + mapOf(
            "error" to JsonPrimitive("续播读取失败"),
            "counts" to buildJsonObject { put("movie", 42) },
            "resume" to arr(),
        )))) }
        rule.waitForIdle()
        rule.onNodeWithText("首页已取的续播").assertIsDisplayed()
        rule.onNodeWithText("电影 42").assertIsDisplayed()
        rule.onNodeWithText("现在连不上:续播读取失败").assertIsDisplayed()
    }

    @Test fun alternateLineStillReusesCanonicalAccountResume() {
        core.ret("emby.currentSession", buildJsonObject {
            put("server", "http://emby-alternate.invalid"); put("user_id", "u")
        })
        pending = CompletableDeferred()
        open { seedHome("备用线路的续播") }
        rule.onNodeWithText("备用线路的续播").assertIsDisplayed()
        rule.onNodeWithText("服务器 A").assertIsDisplayed()
        assertEquals(0, core.calls.count { it.first == "emby.listResume" })
    }

    @Test fun pullRefreshKeepsCountsAndCardsUntilSuccessfulReplacement() {
        core.ret("emby.aggregateOverview", overview("缓存续播"))
        open()
        rule.onNodeWithContentDescription("刷新").assertDoesNotExist()
        rule.onNodeWithText("继续观看").assertDoesNotExist()
        val before = rule.onNodeWithText("服务器 A").fetchSemanticsNode().boundsInRoot
        pending = CompletableDeferred()
        rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue(core.calls.last { it.first == "emby.aggregateOverview" }.second.bool("counts_only"))
        rule.onNodeWithText("电影 30").assertIsDisplayed()
        rule.onNodeWithText("正在刷新…").assertDoesNotExist()
        assertEquals(before, rule.onNodeWithText("服务器 A").fetchSemanticsNode().boundsInRoot)
        val failed = overview("不应替换").arr().single().obj()!!
        rule.runOnIdle { pending!!.complete(arr(JsonObject(failed + mapOf(
            "counts_error" to JsonPrimitive("统计失败"), "counts_known" to JsonPrimitive(false),
            "counts" to buildJsonObject { put("movie", 0) },
            "resume_unchanged" to JsonPrimitive(true), "resume" to arr(),
        )))) }
        rule.waitForIdle()
        rule.onNodeWithText("电影 30").assertIsDisplayed()
        rule.onNodeWithText("缓存续播").assertIsDisplayed()
        pending = CompletableDeferred()
        rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.runOnIdle { pending!!.complete(arr(JsonObject(failed + mapOf(
            "counts_known" to JsonPrimitive(true), "counts" to buildJsonObject { put("movie", 0) },
            "resume_unchanged" to JsonPrimitive(true), "resume" to arr(),
        )))) }
        rule.waitForIdle()
        rule.onNodeWithText("电影 0").assertIsDisplayed()
        rule.onNodeWithText("缓存续播").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/aggregate-ui/refresh.png")
    }

    @Test fun resumeInvalidationDuringRefreshRequestsFreshResume() {
        pending = CompletableDeferred()
        cachedPartial = overview("旧续播")
        open()
        assertTrue(core.calls.last { it.first == "emby.aggregateOverview" }.second.bool("counts_only"))
        rule.runOnIdle {
            core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("data.invalidate",
                buildJsonObject { put("scope", "resume") }))
        }
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "emby.aggregateOverview" })
        assertEquals(false, core.calls.last { it.first == "emby.aggregateOverview" }.second.bool("counts_only"))
        rule.runOnIdle { pending!!.complete(overview("更新后的续播")) }
        rule.waitForIdle()
        rule.onNodeWithText("更新后的续播").assertIsDisplayed()
        rule.onNodeWithText("旧续播").assertDoesNotExist()
    }

    @Test fun otherServerImageUsesItsEffectiveLine() {
        val row = overview("其它服续播", true).arr().single().obj()!!
        core.ret("emby.aggregateOverview", arr(JsonObject(row +
            ("line_url" to JsonPrimitive("https://alternate.invalid/emby")))))
        open()
        rule.waitUntil(5000) { imageRequests.isNotEmpty() }
        assertTrue(imageRequests.any { android.net.Uri.decode(it).contains("src=https://alternate.invalid/emby/Items/other/Images/Primary") })
        assertTrue(imageRequests.none { android.net.Uri.decode(it).contains("emby-a.invalid/Items/other") })
    }

    @Test fun homeSeedDoesNotHideOtherServersPersistentResumeDuringCountsRefresh() {
        pending = CompletableDeferred()
        cachedPartial = JsonArray(overview("旧首页续播").arr() + overview("其它服持久续播", true).arr())
        open { seedHome("最新首页续播") }
        rule.onNodeWithText("最新首页续播").assertIsDisplayed()
        rule.onNodeWithText("其它服持久续播").assertIsDisplayed()
        val countsOnly = cachedPartial!!.arr().map { row -> JsonObject(row.obj()!! + mapOf(
            "resume_unchanged" to JsonPrimitive(true), "resume" to arr(),
        )) }
        rule.runOnIdle { pending!!.complete(JsonArray(countsOnly)) }
        rule.waitForIdle()
        rule.onNodeWithText("最新首页续播").assertIsDisplayed()
        rule.onNodeWithText("其它服持久续播").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/aggregate-ui/servers-light.png")
    }
}
