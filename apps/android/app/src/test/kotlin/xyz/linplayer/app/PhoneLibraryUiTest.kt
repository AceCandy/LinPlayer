package xyz.linplayer.app

import android.app.Application
import androidx.compose.ui.graphics.toPixelMap
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.library
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.page
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.LibraryPage
import xyz.linplayer.app.ui.theme.LpTheme

/** 真实媒体库布局与操作回归：顶部控件固定，筛选仍走服务端。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneLibraryUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val title = "这是一个名字很长的电影媒体库"
    private val entries = (1..30).map {
        item("film-$it", "影片 $it", year = 2024, rating = if (it == 1) 9.2 else null,
            runtime = 3600.0, resume = 1800.0)
    }

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun open(core: CorePort, fontScale: Float = 1f, dark: Boolean = false) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Library("lib-0", title)) {
                        composable<Route.Library> { LibraryPage(nav, it) }
                        composable<Route.Search> { Text("搜索范围：${it.toRoute<Route.Search>().viewId}") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun core() = FakeCore().loggedIn().library().apply {
        ret("emby.listItemsPage", page(*entries.toTypedArray()))
    }

    @Test fun firstRequestIsSmallAndColdLoadingDoesNotFillTheScreenWithSkeletons() {
        val fake = core()
        val gate = CompletableDeferred<Unit>()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.listItemsPage") gate.await()
                return fake.callJson(command, args, onPartial)
            }
        }
        open(port, dark = true)
        rule.onNodeWithTag("library.loading").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/library-ui/loading.png")
        rule.onAllNodes(hasScrollToIndexAction()).assertCountEquals(0)
        rule.runOnIdle { gate.complete(Unit) }
        rule.onNodeWithText("影片 1").assertIsDisplayed()
        rule.onNodeWithTag("library.loading").assertDoesNotExist()
        val query = fake.calls.first { it.first == "emby.listItemsPage" }.second?.get("query").obj()
        assertEquals("30", query?.get("limit").toString())
    }

    @Test fun smallFirstPageContinuesFromItsActualOffsetWithNormalPageSize() {
        val fake = core()
        val all = (1..160).map { item("film-$it", "影片 $it", year = 2024) }
        fake.on("emby.listItemsPage") { a ->
            val query = a?.get("query").obj()
            val offset = query?.get("start_index").toString().toInt()
            val limit = query?.get("limit").toString().toInt()
            buildJsonObject {
                put("items", JsonArray(all.drop(offset).take(limit)))
                put("total", all.size)
            }
        }
        open(fake)
        val first = fake.calls.first { it.first == "emby.listItemsPage" }.second?.get("query").obj()
        assertEquals("30", first?.get("limit").toString())
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        rule.waitUntil(3000) { fake.calls.count { it.first == "emby.listItemsPage" } >= 2 }
        val next = fake.calls.filter { it.first == "emby.listItemsPage" }[1].second?.get("query").obj()
        assertEquals("30", next?.get("start_index").toString())
        assertEquals("120", next?.get("limit").toString())
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        rule.onNodeWithText("影片 31").assertIsDisplayed()
    }

    @Test fun compactGridKeepsControlsAndLastRowVisibleWithLargeFont() {
        open(core(), fontScale = 1.3f)
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        rule.onNodeWithContentDescription("在这个库里搜").assertDoesNotExist()
        val poster = rule.onNodeWithText("影片 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("首屏仍被大库头占用", poster.top < 180f)
        rule.onNodeWithText("9.2", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("剩余 ", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("1小时0分", substring = true).assertCountEquals(0)
        rule.onRoot().captureRoboImage("build/library-ui/large-font.png")
        val titleBounds = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val sortBounds = rule.onNodeWithTag("library.sort").fetchSemanticsNode().boundsInRoot
        assertTrue("排序应与库名同行且在右侧", sortBounds.center.y >= titleBounds.top && sortBounds.center.y <= titleBounds.bottom + 12f && sortBounds.left >= titleBounds.right)
        val filterTop = rule.onNodeWithTag("library.sort").fetchSemanticsNode().boundsInRoot.top
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        rule.onNodeWithTag("library.sort").assertIsDisplayed()
        assertEquals(filterTop, rule.onNodeWithTag("library.sort").fetchSemanticsNode().boundsInRoot.top, .1f)
        val last = rule.onNodeWithText("影片 30").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("末行没有底部安全留白", last.bottom <= root.bottom - 12f)
        rule.onRoot().captureRoboImage("build/library-ui/bottom.png")
    }

    @Test fun sortKeepsOriginalCommands() {
        val core = core()
        open(core)
        rule.onNodeWithTag("library.sort").performClick()
        rule.onNodeWithText("名称").performScrollTo().performClick()
        rule.waitForIdle()
        val request = core.calls.last { it.first == "emby.listItemsPage" }.second
        assertEquals("lib-0", request.str("parent_id"))
        assertEquals("SortName", request?.get("query").obj().str("sort_by"))
        assertEquals("Ascending", request?.get("query").obj().str("sort_order"))
    }

    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun supportedSortsAndDirectionRefreshTheServerQuery() {
        val core = core()
        open(core, fontScale = 1.3f)
        rule.onRoot().captureRoboImage("build/library-sort/compact-light.png")
        for ((label, key) in listOf("更新日期" to "DateLastContentAdded", "上映日期" to "PremiereDate",
            "名称" to "SortName", "评分" to "CommunityRating")) {
            rule.onNodeWithTag("library.sort").performClick()
            rule.onNodeWithText("加入时间").assertDoesNotExist()
            rule.onNodeWithText("年份").assertDoesNotExist()
            rule.onNode(hasText(label) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(key, core.calls.last { it.first == "emby.listItemsPage" }.second?.get("query").obj().str("sort_by"))
            for (order in listOf("升序" to "Ascending", "降序" to "Descending")) {
                rule.onNodeWithTag("library.sort").performClick()
                rule.onNodeWithText(order.first).performScrollTo().performClick()
                rule.waitForIdle()
                assertEquals(order.second, core.calls.last { it.first == "emby.listItemsPage" }.second?.get("query").obj().str("sort_order"))
            }
        }
    }

    @Test fun darkSortHeaderAndPanel() {
        open(core(), dark = true)
        rule.onRoot().captureRoboImage("build/library-sort/dark.png")
        rule.onNodeWithTag("library.sort").performClick()
        rule.onNode(isDialog()).captureRoboImage("build/library-sort/panel-dark.png")
    }

    @Test fun emptyFilteredResultsKeepControlsAndCanClearRating() {
        val core = core()
        core.on("emby.listItemsPage") { a ->
            if (a?.get("query").obj()?.containsKey("rating_min") == true) page()
            else page(*entries.toTypedArray())
        }
        open(core)
        rule.onNodeWithTag("library.sort").performClick()
        rule.onNodeWithText("8 分以上").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("当前筛选没有结果").assertIsDisplayed()
        rule.onNodeWithTag("library.sort").assertIsDisplayed()
        rule.onNodeWithText("8 分以上 ×").assertIsDisplayed()
        val query = core.calls.last { it.first == "emby.listItemsPage" }.second?.get("query").obj()
        assertEquals("8", query?.get("rating_min").toString())
        rule.onNodeWithText("清除筛选").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("影片 1").assertIsDisplayed()
        rule.onNodeWithText("8 分以上 ×").assertDoesNotExist()
        val cleared = core.calls.last { it.first == "emby.listItemsPage" }.second?.get("query").obj()
        assertTrue(cleared?.containsKey("rating_min") == false)
    }

    @Test fun realShellKeepsTabsAndScopedSearchInsideLibrary() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core(), scope)
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        rule.setContent {
            back = checkNotNull(androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            LpTheme(darkOverride = false) { PhoneRoot(app) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("phone.tabs").assertExists()
        rule.onNode(hasText("电影") and hasAnyAncestor(hasTestTag("home.libraries"))).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("phone.tabs").assertExists()
        rule.onNodeWithText("影片 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.onNodeWithTag("phone.tabs").assertExists()
        rule.onNodeWithTag("search.field").assertIsDisplayed()
        rule.onNodeWithText("在这个库里搜").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("影片 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.onNodeWithContentDescription("聚合视界").performClick()
        rule.onNodeWithContentDescription("首页").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search.field").assertDoesNotExist()
        rule.onNodeWithText("影片 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("首页").performClick()
        rule.onNodeWithText("继续观看").assertExists()
        rule.onNodeWithTag("phone.tabs").assertExists()
    }

    @Test fun realShellPushAndPopHaveIntermediateRenderedFrames() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core(), scope)
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        rule.mainClock.autoAdvance = false
        rule.setContent {
            back = checkNotNull(androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            LpTheme(darkOverride = false) { PhoneRoot(app) }
        }
        fun frame(): FloatArray {
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            return (20 until 180 step 4).flatMap { y ->
                (20 until pixels.width - 20 step 4).map { x -> pixels[x, y].red }
            }.toFloatArray()
        }
        fun distance(a: FloatArray, b: FloatArray) = a.indices.sumOf { kotlin.math.abs(a[it] - b[it]).toDouble() } / a.size
        rule.mainClock.advanceTimeBy(1000)
        val home = frame()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.mainClock.advanceTimeBy(64)
        val early = frame()
        rule.mainClock.advanceTimeBy(96)
        val middle = frame()
        rule.mainClock.advanceTimeBy(500)
        val search = frame()
        assertTrue("push must visibly interpolate", distance(early, search) > distance(middle, search) + .002)
        assertTrue("middle frame must not already be final", distance(middle, search) > .002)
        rule.runOnIdle { back.onBackPressed() }
        rule.mainClock.advanceTimeBy(80)
        val returning = frame()
        rule.mainClock.advanceTimeBy(500)
        assertTrue("pop must also have a visible intermediate frame", distance(returning, home) > .002)
        rule.onNodeWithTag("search.field").assertDoesNotExist()
    }
}
