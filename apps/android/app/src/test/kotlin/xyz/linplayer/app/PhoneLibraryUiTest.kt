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
import xyz.linplayer.app.tv.arr
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
    private lateinit var navigation: androidx.navigation.NavHostController
    private lateinit var appState: AppState
    private val entries = (1..30).map {
        item("film-$it", "影片 $it", year = 2024, rating = if (it == 1) 9.2 else null,
            runtime = 3600.0, resume = 1800.0)
    }

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun open(core: CorePort, fontScale: Float = 1f, dark: Boolean = false) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        appState = app
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val nav = rememberNavController()
                    navigation = nav
                    NavHost(nav, startDestination = Route.Library("lib-0", title)) {
                        composable<Route.Library> { LibraryPage(nav, it) }
                        composable<Route.Search> { Text("搜索范围：${it.toRoute<Route.Search>().viewId}") }
                        composable<Route.Detail> { Text("详情目标：${it.toRoute<Route.Detail>().itemId}") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun core() = FakeCore().loggedIn().library().apply {
        ret("emby.listItemsPage", page(*entries.toTypedArray()))
    }

    @Test fun detailReturnKeepsSortedPagesAndScrollWithoutRequests() {
        val fake = core()
        fake.on("emby.listItemsPage") { a ->
            val offset = a?.get("query").obj()?.get("start_index").toString().toInt()
            buildJsonObject {
                put("items", JsonArray((1..90).drop(offset).take(30).map { item("film-$it", "影片 $it") }))
                put("total", 90)
            }
        }
        open(fake)
        rule.onNodeWithTag("library.sort").performClick()
        rule.onNodeWithText("名称").performScrollTo().performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        val top = rule.onNodeWithText("影片 31").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithText("影片 31").performClick()
        rule.onNodeWithText("详情目标：film-31").assertIsDisplayed()
        val requests = fake.calls.count { it.first in listOf("emby.listItemsPage", "emby.getFilters") }
        rule.runOnIdle { navigation.popBackStack() }
        rule.waitForIdle()
        assertEquals(requests, fake.calls.count { it.first in listOf("emby.listItemsPage", "emby.getFilters") })
        rule.onNodeWithText("60+ 部").assertIsDisplayed()
        assertEquals(top, rule.onNodeWithText("影片 31").fetchSemanticsNode().boundsInRoot.top, .1f)
        rule.onNodeWithTag("library.refreshing").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(59)
        rule.waitForIdle()
        val query = fake.calls.last { it.first == "emby.listItemsPage" }.second?.get("query").obj()
        assertEquals("60", query?.get("start_index").toString())
        assertEquals("SortName", query.str("sort_by"))
    }

    @Test fun detailStateChangeRefreshesOnReturn() {
        val fake = core().apply { ret("emby.setPlayed", buildJsonObject { put("item_id", "film-1"); put("played", true) }) }
        open(fake)
        rule.onNodeWithText("影片 1").performClick()
        rule.onNodeWithText("详情目标：film-1").assertIsDisplayed()
        val requests = fake.calls.count { it.first == "emby.listItemsPage" }
        rule.runOnIdle {
            runBlocking { appState.call("emby.setPlayed", buildJsonObject { put("item_id", "film-1"); put("played", true) }) }
        }
        fake.ret("emby.listItemsPage", page(item("updated", "更新后的影片")))
        rule.runOnIdle { navigation.popBackStack() }
        rule.waitForIdle()
        assertEquals(requests + 1, fake.calls.count { it.first == "emby.listItemsPage" })
        rule.onNodeWithText("更新后的影片").assertIsDisplayed()
    }

    @Test fun emptyLibraryReturnDoesNotReload() {
        val fake = core().apply { ret("emby.listItemsPage", page()) }
        open(fake)
        rule.onNodeWithText("这个库还没有内容").assertIsDisplayed()
        rule.runOnIdle { navigation.navigate(Route.Detail("film-1", "Movie")) }
        rule.onNodeWithText("详情目标：film-1").assertIsDisplayed()
        val requests = fake.calls.count { it.first == "emby.listItemsPage" }
        rule.runOnIdle { navigation.popBackStack() }
        rule.waitForIdle()
        assertEquals(requests, fake.calls.count { it.first == "emby.listItemsPage" })
        rule.onNodeWithText("这个库还没有内容").assertIsDisplayed()
    }

    @Test fun newLibraryEntryStillRefreshesCachedResults() {
        val fake = core()
        open(fake)
        val requests = fake.calls.count { it.first == "emby.listItemsPage" }
        fake.ret("emby.listItemsPage", page(item("new-film", "新入库影片")))
        rule.runOnIdle { navigation.navigate(Route.Library("lib-0", "重新进入媒体库")) }
        rule.waitForIdle()
        assertEquals(requests + 1, fake.calls.count { it.first == "emby.listItemsPage" })
        rule.onNodeWithText("新入库影片").assertIsDisplayed()
    }

    @Test fun cancelledInitialRequestLoadsAgainOnReturn() {
        val fake = core()
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.listItemsPage") { requests++; gate.await() }
                return fake.callJson(command, args, onPartial)
            }
        }
        try {
            open(port)
            rule.onNodeWithTag("library.loading").assertIsDisplayed()
            rule.runOnIdle { navigation.navigate(Route.Detail("film-1", "Movie")) }
            rule.onNodeWithText("详情目标：film-1").assertIsDisplayed()
            rule.runOnIdle { navigation.popBackStack() }
            rule.waitForIdle()
            assertEquals(2, requests)
            rule.runOnIdle { gate.complete(Unit) }
            rule.onNodeWithText("影片 1").assertIsDisplayed()
        } finally { gate.complete(Unit) }
    }

    @Test fun cachedGridShowsInsertedPostersAtTheStart() {
        val directory = java.nio.file.Files.createTempDirectory("library-insert-test").toFile()
        val fake = core()
        val gate = CompletableDeferred<Unit>()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.listItemsPage") {
                    onPartial?.invoke(page(*entries.toTypedArray()))
                    gate.await()
                    val inserted = (1..6).map { item("new-film-$it", if (it == 1) "新入库影片" else "新影片 $it") }
                    return page(*(inserted + entries.take(24)).toTypedArray())
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        try {
            open(port)
            rule.waitUntil(5000) { rule.onAllNodesWithText("影片 1").fetchSemanticsNodes().isNotEmpty() }
            val firstTop = rule.onNodeWithContentDescription("影片 1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            rule.runOnIdle { gate.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithText("新入库影片").assertIsDisplayed()
            assertEquals(firstTop, rule.onNodeWithContentDescription("新入库影片", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.top, .1f)
        } finally { gate.complete(Unit); directory.deleteRecursively() }
    }

    @Test fun coreSnapshotAppearsWhileRefreshIsPendingAndFailureKeepsPosters() {
        val directory = java.nio.file.Files.createTempDirectory("library-cache-test").toFile()
        val fake = core()
        val gate = CompletableDeferred<Unit>()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.listItemsPage") {
                    onPartial?.invoke(page(*entries.toTypedArray()))
                    gate.await()
                    throw xyz.linplayer.app.core.CoreException("E_NETWORK", "刷新失败", true)
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        try {
            open(port)
            rule.waitUntil(5000) { rule.onAllNodesWithText("影片 1").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("影片 1").assertIsDisplayed()
            rule.onNodeWithTag("library.refreshing").assertIsDisplayed()
            rule.runOnIdle { gate.complete(Unit) }
            rule.onNodeWithText("刷新失败", substring = true).assertExists()
            rule.onNodeWithText("影片 1").assertIsDisplayed()
            rule.onNodeWithTag("library.refreshing").assertDoesNotExist()
        } finally { gate.complete(Unit); directory.deleteRecursively() }
    }

    @Test fun unknownTotalShortPageKeepsPlusUntilEmptyPage() {
        val fake = core()
        fake.on("emby.listItemsPage") { a ->
            val offset = a?.get("query").obj()?.get("start_index").toString().toInt()
            JsonArray(if (offset == 0) entries.take(3) else emptyList())
        }
        open(fake)
        rule.waitForIdle()
        rule.onNodeWithText("3 部").assertIsDisplayed()
        val requests = fake.calls.filter { it.first == "emby.listItemsPage" }
        assertEquals(2, requests.size)
        assertEquals("3", requests.last().second?.get("query").obj()?.get("start_index").toString())
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
        rule.onNodeWithText("30+ 部").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        rule.waitUntil(3000) { fake.calls.count { it.first == "emby.listItemsPage" } >= 2 }
        val next = fake.calls.filter { it.first == "emby.listItemsPage" }[1].second?.get("query").obj()
        assertEquals("30", next?.get("start_index").toString())
        assertEquals("30", next?.get("limit").toString())
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        rule.onNodeWithText("影片 31").assertIsDisplayed()
        for (last in listOf(59, 89, 119, 149, 159)) {
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(last)
            rule.waitForIdle()
        }
        rule.onNodeWithText("160 部").assertIsDisplayed()
        assertTrue(fake.calls.filter { it.first == "emby.listItemsPage" }.all {
            it.second?.get("query").obj()?.get("limit").toString() == "30"
        })
    }

    @Test fun compactGridKeepsControlsAndLastRowVisibleWithLargeFont() {
        open(core(), fontScale = 1.3f)
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        rule.onNodeWithContentDescription("在这个库里搜").assertDoesNotExist()
        val poster = rule.onNodeWithText("影片 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("首屏仍被大库头占用", poster.top < 180f)
        rule.onNodeWithText("9.2", useUnmergedTree = true).assertIsDisplayed()
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

    @Test fun realShellTabsAlwaysLandOnTopLevelPages() {
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
        rule.onNode(hasText("搜片名、剧名或演员") and hasAnyAncestor(hasTestTag("search.field")),
            useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("影片 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.onNodeWithContentDescription("聚合视界").performClick()
        rule.onNodeWithContentDescription("首页").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search.field").assertDoesNotExist()
        rule.onNodeWithText("影片 1").assertDoesNotExist()
        rule.onNodeWithText("继续观看").assertExists()
        rule.onNodeWithTag("phone.tabs").assertExists()
    }

    @Test fun privateShellHasOnlyCurrentServerBrowsing() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = core().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "private"); put("name", "私密库"); put("is_adult", true) }))
        }
        val app = AppState(core, scope)
        rule.setContent { LpTheme(darkOverride = false) { PhoneRoot(app) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("聚合视界").assertExists()
        rule.onNodeWithText("服务器 A").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(app.privateSpace.value)
        val privateCallsStart = core.calls.size
        rule.onNodeWithContentDescription("聚合视界").performClick()
        rule.onNodeWithText("流影").assertIsDisplayed()
        rule.onNodeWithText("下载").assertDoesNotExist()
        rule.onNodeWithText("排行榜").assertDoesNotExist()
        rule.onNodeWithText("日历").assertDoesNotExist()
        rule.onNodeWithContentDescription("观看历史").assertDoesNotExist()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.onNodeWithTag("search.aggregate").assertDoesNotExist()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("search.field"))).performTextInput("作品")
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        val request = core.calls.last { it.first == "emby.search" }.second
        val requestScope = request?.get("request_scope").obj()
        assertEquals("true", requestScope?.get("private").toString())
        assertEquals("true", requestScope?.get("filter").toString())
        assertEquals(app.session.value!!.server, requestScope.str("server"))
        assertEquals(app.session.value!!.userId, requestScope.str("user_id"))
        assertTrue(core.calls.drop(privateCallsStart).none {
            it.first in listOf("emby.aggregateOverview", "source.aggregateSearch", "emby.watchHistoryList")
        })
        rule.onNodeWithContentDescription("首页").performClick()
        rule.onNodeWithText("服务器 A").performClick()
        rule.waitForIdle()
        assertTrue(!app.privateSpace.value)
        rule.onNodeWithContentDescription("聚合视界").assertExists()
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
