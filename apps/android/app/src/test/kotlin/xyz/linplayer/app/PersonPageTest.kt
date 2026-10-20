package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.LocalTvMode
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.PersonPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PersonPageTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var nav: androidx.navigation.NavHostController
    private lateinit var appState: AppState
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun works(start: Int, count: Int, total: Int, type: String = "Movie") = buildJsonObject {
        put("items", JsonArray((start until start + count).map {
            item("$type-$it", "${if (type == "Movie") "电影" else "剧集"}作品$it", type, year = 2026 - it % 10)
        }))
        put("total", total); put("next_index", start + count); put("has_more", start + count < total)
    }

    private fun fake() = FakeCore().loggedIn().apply {
        ret("emby.personDetail", buildJsonObject {
            put("id", "person-1"); put("name", "人物甲"); put("has_primary", true)
            put("overview", "演员简介。".repeat(60)); put("birthday", "1986-01-02")
        })
        on("emby.personItemsPage") { args ->
            val start = args.long("start_index")!!.toInt()
            val type = args.str("type")!!
            works(start, (65 - start).coerceAtMost(30).coerceAtLeast(0), 65, type)
        }
    }

    private fun open(core: CorePort, dark: Boolean = true, fontScale: Float = 1f, tv: Boolean = false) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        appState = app
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalApp provides app, LocalTvMode provides tv,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val controller = rememberNavController()
                    nav = controller
                    NavHost(controller, startDestination = Route.Person("person-1", "备用姓名")) {
                        composable<Route.Person> { PersonPage(controller, it) }
                        composable<Route.Detail> { Text("详情目标：${it.toRoute<Route.Detail>().itemId}") }
                        composable<Route.Home> { Text("已离开人物页") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun paginationAndReturnKeepPosition() {
        val core = fake()
        open(core)
        rule.onNodeWithText("人物甲").assertIsDisplayed()
        rule.onNodeWithText("电影 65").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/person/phone-dark.png")
        rule.onNodeWithTag("person.works").performScrollToIndex(29)
        rule.waitUntil { core.calls.count { it.first == "emby.personItemsPage" } >= 2 }
        rule.onNodeWithTag("person.works").performScrollToIndex(38)
        rule.onNodeWithText("电影作品37").assertIsDisplayed().performClick()
        rule.onNodeWithText("详情目标：Movie-37").assertIsDisplayed()
        rule.runOnIdle { nav.popBackStack() }
        rule.onNodeWithText("电影作品37").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "emby.personItemsPage" && it.second.long("start_index") == 0L })
        rule.onNodeWithTag("person.works").performScrollToIndex(58)
        rule.waitUntil { core.calls.count { it.first == "emby.personItemsPage" } == 3 }
        rule.onNodeWithTag("person.works").performScrollToIndex(65)
        rule.onNodeWithText("电影作品64").assertIsDisplayed()
        assertEquals(listOf(0L, 30L, 60L), core.calls.filter { it.first == "emby.personItemsPage" }.map { it.second.long("start_index") })
        assertTrue(core.calls.filter { it.first == "emby.personItemsPage" }.all { it.second.str("person_id") == "person-1" })
    }

    @Test fun lightLargeTextAndCategory() {
        val core = fake()
        open(core, dark = false, fontScale = 1.3f)
        rule.onNodeWithTag("person.overview").performClick()
        rule.onNodeWithTag("person.works").performScrollToNode(hasText("剧集"))
        rule.onNodeWithText("剧集").performClick()
        rule.onNodeWithText("剧集 65").assertIsDisplayed()
        rule.onNodeWithTag("person.works").performScrollToNode(hasText("剧集作品0"))
        rule.onNodeWithText("剧集作品0").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/person/phone-light-large.png")
        assertEquals("Series", core.calls.last { it.first == "emby.personItemsPage" }.second.str("type"))
    }

    @Test fun userStateRefreshKeepsLoadedRangeAndReturnPosition() {
        val core = fake()
        open(core)
        rule.onNodeWithTag("person.works").performScrollToIndex(29)
        rule.waitUntil { core.calls.count { it.first == "emby.personItemsPage" } == 2 }
        rule.onNodeWithTag("person.works").performScrollToIndex(38)
        rule.onNodeWithText("电影作品37").performClick()
        rule.onNodeWithText("详情目标：Movie-37").assertIsDisplayed()
        rule.runOnIdle {
            core.events.tryEmit(CoreEvent("data.invalidate", buildJsonObject { put("scope", "all") }))
        }
        rule.waitForIdle()
        rule.waitUntil { appState.userStateRevision.value > 0 }
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("电影作品37").assertIsDisplayed()
        assertEquals(listOf(0L, 30L, 0L, 30L), core.calls.filter { it.first == "emby.personItemsPage" }.map { it.second.long("start_index") })
    }

    @Test fun filteredPageAdvancesAndFailureRetriesSameOffset() {
        val core = fake()
        var fail = true
        core.on("emby.personItemsPage") { args ->
            when (args.long("start_index")) {
                0L -> buildJsonObject { put("items", JsonArray(emptyList())); put("next_index", 30); put("has_more", true); put("total", 31) }
                else -> if (fail) throw CoreException("E_NETWORK", "作品暂时不可用", true) else works(30, 1, 31)
            }
        }
        open(core)
        rule.onNodeWithText("作品暂时不可用", substring = true).assertExists()
        rule.onNodeWithText("没有相关作品").assertDoesNotExist()
        fail = false
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("电影作品30").assertIsDisplayed()
        assertEquals(listOf(0L, 30L, 30L), core.calls.filter { it.first == "emby.personItemsPage" }.map { it.second.long("start_index") })
    }

    @Test fun cachedPageIsReplacedWithoutAdvancingCursor() {
        val fake = fake()
        val release = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.personItemsPage") {
                    onPartial?.invoke(works(100, 3, 103))
                    release.await()
                    return works(0, 3, 3)
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(core)
        rule.onNodeWithText("电影作品100").assertIsDisplayed()
        release.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("电影作品0").assertIsDisplayed()
        rule.onNodeWithText("电影作品100").assertDoesNotExist()
        rule.onNodeWithText("电影 3").assertIsDisplayed()
    }

    @Test fun repeatedUpstreamPageReportsErrorInsteadOfSkippingWorks() {
        val core = fake()
        core.on("emby.personItemsPage") { args ->
            val next = args.long("start_index")!!.toInt() + 30
            JsonObject(works(0, 30, 65) + mapOf(
                "next_index" to JsonPrimitive(next), "has_more" to JsonPrimitive(next < 65)))
        }
        open(core)
        rule.onNodeWithTag("person.works").performScrollToIndex(29)
        rule.waitUntil { core.calls.count { it.first == "emby.personItemsPage" } >= 2 }
        rule.onNodeWithTag("person.works").performScrollToIndex(0)
        rule.onNodeWithText("服务器没有推进人物作品分页", substring = true).assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.personItemsPage" })
    }

    @Test fun categoryRejectsLateResponse() {
        val fake = fake()
        val release = CompletableDeferred<Unit>()
        var completed = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.personItemsPage" && args.str("type") == "Movie") {
                    withContext(NonCancellable) { release.await() }
                    completed = true
                    onPartial?.invoke(works(100, 3, 103))
                    return works(0, 3, 3)
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(core)
        rule.onNodeWithText("剧集").performClick()
        rule.onNodeWithText("剧集作品0").assertIsDisplayed()
        release.complete(Unit)
        rule.waitUntil { completed }
        rule.waitForIdle()
        rule.onNodeWithText("剧集作品0").assertIsDisplayed()
        rule.onNodeWithText("电影作品0").assertDoesNotExist()
        rule.onNodeWithText("电影作品100").assertDoesNotExist()
    }

    @Test fun leavingRejectsLateResponseAndReturningRetries() {
        val fake = fake()
        val release = CompletableDeferred<Unit>()
        var count = 0
        var completed = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.personItemsPage" && ++count == 1) {
                    withContext(NonCancellable) { release.await() }
                    completed = true
                    return works(100, 3, 103)
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(core)
        rule.runOnIdle { nav.navigate(Route.Home) }
        rule.onNodeWithText("已离开人物页").assertIsDisplayed()
        release.complete(Unit)
        rule.waitUntil { completed }
        rule.waitForIdle()
        rule.runOnIdle { nav.popBackStack() }
        rule.onNodeWithText("电影作品0").assertIsDisplayed()
        rule.onNodeWithText("电影作品100").assertDoesNotExist()
    }

    @Test fun deniedCachedPageDoesNotRemainVisible() {
        val fake = fake()
        val release = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.personItemsPage") {
                    onPartial?.invoke(works(0, 3, 3)); release.await()
                    throw CoreException("E_AUTH", "请重新登录", false)
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(core)
        rule.onNodeWithText("电影作品0").assertIsDisplayed()
        release.complete(Unit); rule.waitForIdle()
        rule.onNodeWithText("电影作品0").assertDoesNotExist()
        rule.onNodeWithText("请重新登录", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvPersonPageRenders() {
        open(fake(), tv = true)
        rule.onNodeWithText("人物甲").assertIsDisplayed()
        rule.onNodeWithText("剧集").performClick()
        rule.onNodeWithText("剧集作品0").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/person/tv.png")
    }
}
