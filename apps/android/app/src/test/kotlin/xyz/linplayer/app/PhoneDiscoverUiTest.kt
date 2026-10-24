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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import xyz.linplayer.app.data.activateMediaServer
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.tv.account
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.RankingPage
import xyz.linplayer.app.ui.pages.CalendarPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneDiscoverUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private val imageRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var navigation: androidx.navigation.NavHostController
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun open(core: FakeCore, route: Any = Route.Ranking, privateSpace: Boolean = false): AppState {
        xyz.linplayer.app.tv.FakeImages.install(androidx.test.core.app.ApplicationProvider.getApplicationContext()) { imageRequests.add(it) }
        val app = AppState(core, scope)
        runBlocking {
            app.boot()
            if (privateSpace) {
                app.enablePrivacyFiltering()
                app.enterPrivateSpace()
            }
        }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    val nav = rememberNavController()
                    navigation = nav
                    NavHost(nav, startDestination = route) {
                        composable<Route.Ranking> { RankingPage(nav) }
                        composable<Route.Calendar> { CalendarPage(nav) }
                        composable<Route.Search> { Text("搜索目标：" + it.toRoute<Route.Search>().q) }
                        composable<Route.Detail> { Text("详情目标：" + it.toRoute<Route.Detail>().itemId) }
                    }
                }
            }
        }
        rule.waitForIdle()
        return app
    }

    private fun rankings() = FakeCore().loggedIn().apply {
        ret("emby.extRankings", arr(buildJsonObject { put("id", "hot"); put("label", "热门榜") }))
        ret("emby.extRankingItems", arr(buildJsonObject {
            put("id", "external-1"); put("rank", 1); put("title", "远方的故事"); put("rating", 8.6)
        }))
    }
    @Test fun rankingRetryFetchesSelectedCategory() {
        val core = rankings()
        core.on("emby.extRankingItems") { throw CoreException("E_NETWORK", "榜单读取失败", true) }
        open(core)
        val before = core.calls.count { it.first == "emby.extRankingItems" }
        core.ret("emby.extRankingItems", arr(buildJsonObject {
            put("id", "external-1"); put("rank", 1); put("title", "远方的故事")
        }))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事").assertIsDisplayed()
        assertEquals(before + 1, core.calls.count { it.first == "emby.extRankingItems" && it.second.long("page") == 1L })
        assertEquals("hot", core.calls.last { it.first == "emby.extRankingItems" }.second.str("category_id"))
    }
    @Test fun rankingScrollAppendsAndPullRefreshRestartsAtFirstPage() {
        val core = rankings()
        var refreshed = false
        core.on("emby.extRankingItems") { a ->
            val page = a.long("page") ?: 1L
            if (page > 2) arr() else arr(*(1..20).map { offset -> buildJsonObject {
                val n = (page - 1) * 20 + offset
                put("id", "external-$n"); put("rank", n)
                put("title", if (refreshed) "刷新作品 $n" else "作品 $n")
            } }.toTypedArray())
        }
        open(core)
        rule.onNodeWithText("上一页").assertDoesNotExist()
        rule.onNodeWithText("下一页").assertDoesNotExist()
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        scroll.performScrollToNode(hasText("作品 20"))
        rule.waitForIdle()
        scroll.performScrollToNode(hasText("作品 40"))
        rule.onNodeWithText("作品 40").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(listOf(1L, 2L, 3L), core.calls.filter { it.first == "emby.extRankingItems" }.map { it.second.long("page") })
        scroll.performScrollToIndex(0)
        refreshed = true
        scroll.performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithText("刷新作品 1").assertIsDisplayed()
        assertEquals(1L, core.calls.last { it.first == "emby.extRankingItems" }.second.long("page"))
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-scroll.png")
    }

    @Test fun appendFailureKeepsRowsAndRetriesSamePage() {
        val core = rankings()
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "resolved"); put("item_type", "Movie") })
        var fail = true
        core.on("emby.extRankingItems") { a ->
            val page = a.long("page") ?: 1L
            if (page == 2L && fail) throw CoreException("E_NETWORK", "追加失败", true)
            if (page > 2) arr() else arr(*(1..20).map { offset -> buildJsonObject {
                val n = (page - 1) * 20 + offset
                put("id", "external-$n"); put("rank", n); put("title", "作品 $n")
            } }.toTypedArray())
        }
        open(core)
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        scroll.performScrollToNode(hasText("作品 20"))
        rule.waitForIdle()
        scroll.performScrollToNode(hasText("重试"))
        rule.onNodeWithText("作品 20").assertExists()
        rule.onNodeWithText("作品 20").performClick()
        rule.onNodeWithText("详情目标：resolved").assertIsDisplayed()
        val before = core.calls.count { it.first == "emby.extRankingItems" }
        rule.runOnIdle { navigation.popBackStack() }
        rule.onNodeWithText("重试").assertIsDisplayed()
        assertEquals(before, core.calls.count { it.first == "emby.extRankingItems" })
        fail = false
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        scroll.performScrollToNode(hasText("作品 40"))
        rule.onNodeWithText("作品 40").assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "emby.extRankingItems" && it.second.long("page") == 2L })
    }

    @Test fun cancelledFirstPageIsRetriedOnReturn() {
        val core = rankings()
        core.on("emby.extRankingItems") { throw kotlinx.coroutines.CancellationException("fixture cancellation") }
        open(core)
        assertEquals(1, core.calls.count { it.first == "emby.extRankingItems" })
        rule.runOnIdle { navigation.navigate(Route.Detail("temporary", "Movie")) }
        rule.onNodeWithText("详情目标：temporary").assertIsDisplayed()
        core.ret("emby.extRankingItems", arr())
        rule.runOnIdle { navigation.popBackStack() }
        rule.onNodeWithText("暂无榜单数据").assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "emby.extRankingItems" })
    }

    @Test fun rankingReturnKeepsCategoryPagesAndScrollButNewVisitReloads() {
        val core = rankings()
        core.ret("emby.extRankings", arr(*listOf(
            Triple("tmdb", "电影榜", "tmdb"), Triple("hongguo", "红果热榜", "hongguo")
        ).map { (id, label, source) -> buildJsonObject {
            put("id", id); put("label", label); put("source", source)
        } }.toTypedArray()))
        core.on("emby.extRankingItems") { a ->
            val page = a.long("page") ?: 1L
            val category = a.str("category_id")
            if (page > 3) arr() else arr(*(1..20).map { offset -> buildJsonObject {
                val n = (page - 1) * 20 + offset
                put("id", "$category-$n"); put("rank", n); put("title", "$category 作品 $n")
            } }.toTypedArray())
        }
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "resolved"); put("item_type", "Series") })
        open(core)
        rule.onNodeWithText("红果").performClick()
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        scroll.performScrollToNode(hasText("hongguo 作品 20"))
        scroll.performScrollToNode(hasText("hongguo 作品 25"))
        rule.onNodeWithText("hongguo 作品 25").performClick()
        rule.onNodeWithText("详情目标：resolved").assertIsDisplayed()
        val before = core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" }
        rule.runOnIdle { navigation.popBackStack() }
        rule.onNodeWithText("红果热榜").assertIsDisplayed()
        rule.onNodeWithText("hongguo 作品 25").assertIsDisplayed()
        assertEquals(before, core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" })
        scroll.performScrollToNode(hasText("hongguo 作品 40"))
        scroll.performScrollToNode(hasText("hongguo 作品 45"))
        rule.onNodeWithText("hongguo 作品 45").assertIsDisplayed()
        val pages = core.calls.filter {
            it.first == "emby.extRankingItems" && it.second.str("category_id") == "hongguo"
        }.map { it.second.long("page") }
        org.junit.Assert.assertTrue(pages.size >= 3)
        assertEquals((1L..pages.size.toLong()).toList(), pages)
        rule.runOnIdle {
            navigation.navigate(Route.Ranking) { popUpTo<Route.Ranking> { inclusive = true } }
        }
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "emby.extRankings" })
    }

    @Test fun repeatedRefreshFailureKeepsRowsAndRetry() {
        val core = rankings()
        open(core)
        core.on("emby.extRankingItems") { throw CoreException("E_NETWORK", "刷新失败", true) }
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        scroll.performTouchInput { swipeDown() }
        rule.waitForIdle()
        repeat(2) {
            rule.onNodeWithText("远方的故事").assertIsDisplayed()
            rule.onNodeWithText("重试").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test fun rankingThemes() {
        val core = rankings()
        core.ret("emby.extRankingItems", arr(*(1..5).map { n -> buildJsonObject {
            put("source", "tmdb"); put("external_id", "$n"); put("media_type", "movie"); put("id", "external-$n"); put("rank", n); put("title", "远方的故事 · 第 $n 部")
            put("image_url", "fake:poster-$n"); put("rating", 8.6); put("subtitle", "2026 · 剧情")
        } }.toTypedArray()))
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "movie-4"); put("item_type", "Movie") })
        open(core)
        rule.onNodeWithText("热门榜").assertIsDisplayed()
        rule.waitUntil(5000) { imageRequests.isNotEmpty() }
        org.junit.Assert.assertTrue(imageRequests.all { it.startsWith("fake:/img?src=") })
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-dark.png")
        rule.onAllNodesWithText("8.6").onFirst().assertIsDisplayed()
        rule.onNodeWithText("远方的故事 · 第 4 部").performClick()
        rule.onNodeWithText("详情目标：movie-4").assertIsDisplayed()
    }

    @Test fun hongGuoPodiumTitlesAndPostersOpenAlbumsOnceAndKeepRankingState() {
        val core = rankings()
        core.ret("emby.extRankings", arr(buildJsonObject {
            put("id", "hongguo_hot-drama"); put("label", "红果热榜"); put("source", "hongguo")
        }))
        core.on("emby.extRankingItems") { a ->
            if (a.long("page") != 1L) arr() else arr(*(1..4).map { n -> buildJsonObject {
                put("id", "hongguo:series:$n"); put("source", "hongguo"); put("external_id", "$n")
                put("media_type", "series"); put("rank", n); put("title", "红果作品 $n"); put("image_url", "fake:poster-$n")
            } }.toTypedArray())
        }
        core.on("emby.extResolve") { a -> buildJsonObject {
            put("item_id", "hg-group-" + a.str("external_id")); put("item_type", "Series")
        } }
        open(core)
        val loaded = core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" }
        for (n in 1..3) for (poster in listOf(false, true)) {
            val before = core.calls.count { it.first == "emby.extResolve" }
            val target = if (poster) rule.onNodeWithContentDescription("红果作品 $n", useUnmergedTree = true)
                else rule.onNodeWithText("红果作品 $n", useUnmergedTree = true)
            target.performTouchInput { click() }
            rule.onNodeWithText("详情目标：hg-group-$n").assertIsDisplayed()
            assertEquals(before + 1, core.calls.count { it.first == "emby.extResolve" })
            val request = core.calls.last { it.first == "emby.extResolve" }.second
            assertEquals("hongguo", request.str("source"))
            assertEquals("$n", request.str("external_id"))
            assertEquals("series", request.str("media_type"))
            assertEquals(null, request?.get("title"))
            rule.runOnIdle { navigation.popBackStack() }
            rule.onNodeWithText("红果热榜").assertIsDisplayed()
            assertEquals(loaded, core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" })
        }
        rule.onNodeWithText("红果作品 4").performClick()
        rule.onNodeWithText("详情目标：hg-group-4").assertIsDisplayed()
    }

    @Test fun rankingResolveFailuresNotifyWithoutNavigatingOrReloading() {
        val core = rankings()
        val app = open(core)
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        rule.runOnIdle {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                app.toasts.collect { messages.add(it.text) }
            }
        }
        val loaded = core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" }
        val errors = listOf(
            CoreException("E_NOTFOUND", "没有找到已入库作品", false),
            CoreException("E_INVALID", "找到多个入库作品，无法唯一匹配", false),
            CoreException("E_NETWORK", "匹配请求失败", true),
        )
        errors.forEachIndexed { index, error ->
            core.on("emby.extResolve") { throw error }
            rule.onNodeWithText("远方的故事", useUnmergedTree = true).performTouchInput { click() }
            rule.waitForIdle()
            assertEquals(index + 1, core.calls.count { it.first == "emby.extResolve" })
            rule.waitUntil(5000) { messages.size == index + 1 }
            assertEquals(error.advice, messages.last())
            rule.onNodeWithText("远方的故事").assertIsDisplayed()
            rule.onNodeWithText("详情目标：", substring = true).assertDoesNotExist()
        }
        assertEquals(3, core.calls.count { it.first == "emby.extResolve" })
        assertEquals(0, core.calls.count { it.first == "account.setActiveServer" })
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "retried"); put("item_type", "Movie") })
        rule.onNodeWithText("远方的故事").performClick()
        rule.onNodeWithText("详情目标：retried").assertIsDisplayed()
        assertEquals(loaded, core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" })
    }

    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun javRankingShowsCodesAndContributionScoresInBothThemes() {
        val core = rankings()
        core.ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
        core.ret("emby.extRankings", arr(buildJsonObject {
            put("id", "javranking_weekly"); put("label", "周榜"); put("source", "javranking")
        }))
        core.on("emby.extRankingItems") { a ->
            if (a.long("page") != 1L) arr() else arr(*(1..5).map { n -> buildJsonObject {
                val code = "TEST-%03d".format(n)
                put("id", "javranking:movie:$code"); put("source", "javranking"); put("external_id", code)
                put("media_type", "movie"); put("rank", n); put("title", "榜单长标题与关联作品 $n")
                put("image_url", "fake:poster-$n"); put("rating", 8.6)
                when (n) {
                    1 -> put("score", 0)
                    2 -> put("score", 12345678901L)
                    4 -> put("score", 876)
                    5 -> put("score", kotlinx.serialization.json.JsonNull)
                }
            } }.toTypedArray())
        }
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "nfo-work"); put("item_type", "Movie") })
        open(core, privateSpace = true)
        rule.onNodeWithText("TEST-001").assertIsDisplayed()
        rule.onNodeWithText("战力 0").assertIsDisplayed()
        rule.onNodeWithText("战力 12345678901").assertIsDisplayed()
        rule.onNodeWithText("TEST-003").assertIsDisplayed()
        rule.onNodeWithText("TEST-004").assertIsDisplayed()
        rule.onNodeWithText("战力 876").assertIsDisplayed()
        rule.onNodeWithText("战力 null").assertDoesNotExist()
        rule.onAllNodesWithText("战力 0").assertCountEquals(1)
        rule.onNodeWithText("8.6").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/discover-ui/javranking-light-narrow.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/javranking-dark-narrow.png")
        rule.onNodeWithText("战力 0", useUnmergedTree = true).performTouchInput { click() }
        rule.onNodeWithText("详情目标：nfo-work").assertIsDisplayed()
        val resolve = core.calls.last { it.first == "emby.extResolve" }.second
        assertEquals("TEST-001", resolve.str("external_id"))
        assertEquals(null, resolve?.get("score"))
    }
    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun calendarRetryAndNarrowThemes() {
        val core = FakeCore().loggedIn()
        core.on("emby.extCalendar") { throw CoreException("E_NETWORK", "日历读取失败", true) }
        open(core, Route.Calendar)
        val before = core.calls.count { it.first == "emby.extCalendar" }
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "series-1"); put("item_type", "Series") })
        core.ret("emby.extCalendar", arr(buildJsonObject {
            put("source", "bangumi"); put("external_id", "42"); put("media_type", "anime"); put("title", "远方的故事与漫长旅途"); put("subtitle", "第二季 · 第十二集")
            put("weekday", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).dayOfWeek.value); put("rating", 8.8); put("image_url", "fake:calendar")
        }))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("远方的故事与漫长旅途").assertIsDisplayed()
        rule.waitUntil(5000) { imageRequests.isNotEmpty() }
        org.junit.Assert.assertTrue(imageRequests.all { it.startsWith("fake:/img?src=") })
        rule.onNodeWithText("未提供").assertIsDisplayed()
        assertEquals(before + 1, core.calls.count { it.first == "emby.extCalendar" })
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-light-narrow.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-dark-narrow.png")
        rule.onNodeWithText("查看入库").performClick()
        rule.onNodeWithText("详情目标：series-1").assertIsDisplayed()
    }
    @Test fun calendarDisplaysProvidedTime() {
        val core = FakeCore().loggedIn()
        core.ret("emby.extCalendar", arr(buildJsonObject {
            put("source", "bangumi"); put("external_id", "42"); put("media_type", "anime"); put("title", "定时放送")
            put("weekday", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).dayOfWeek.value)
            put("broadcast_time", "01:30"); put("timezone", "Asia/Tokyo"); put("time_source", "bangumi-data")
        }))
        open(core, Route.Calendar)
        rule.onNodeWithText("01:30").assertIsDisplayed()
        rule.onNodeWithText("定时放送").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/discover-ui/calendar-time.png")
    }
    @Test fun calendarWithoutSponsorOrderFetchesImmediately() {
        val core = FakeCore().loggedIn()
        core.ret("emby.extCalendar", arr())
        open(core, Route.Calendar)
        rule.onNodeWithText("追剧日历 · 赞助解锁").assertDoesNotExist()
        rule.onNodeWithText("本周 0 部 · 日本时区周历").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "emby.extCalendar" })
        assertEquals(0, core.calls.count { it.first == "prefs.getPrefs" || it.first.startsWith("system.afdian") })
    }

    @Test fun rankingSystemsKeepAllCategoriesAndUsePrimaryServer() {
        val core = rankings()
        core.ret("account.listAccounts", arr(JsonObject(account("primary", "主服", "", false).obj()!! +
            ("aggregate_primary" to JsonPrimitive(true)))))
        core.ret("emby.extRankings", arr(*listOf(
            Triple("tmdb-hot", "热门电影", "tmdb"), Triple("tmdb-tv", "热门剧集", "tmdb"),
            Triple("douban-top", "豆瓣高分", "douban"), Triple("douban-new", "豆瓣新片", "douban"),
        ).map { (id, label, source) -> buildJsonObject {
            put("id", id); put("label", label); put("source", source); put("enabled", true)
        } }.toTypedArray()))
        open(core)
        rule.onNodeWithText("TMDb").assertIsDisplayed()
        rule.onNodeWithText("热门剧集").performClick()
        rule.waitForIdle()
        assertEquals("tmdb-tv", core.calls.last { it.first == "emby.extRankingItems" }.second.str("category_id"))
        rule.onNodeWithText("豆瓣").performClick()
        rule.onNodeWithText("豆瓣新片").performClick()
        rule.waitForIdle()
        assertEquals("douban-new", core.calls.last { it.first == "emby.extRankingItems" }.second.str("category_id"))
        core.calls.filter { it.first.startsWith("emby.ext") }.forEach { assertEquals("primary", it.second.str("server_id")) }
        rule.onRoot().captureRoboImage("build/discover-ui/ranking-systems.png")
    }

    @Test fun primaryMatchSwitchesToEffectiveLineBeforeDetail() {
        val core = rankings()
        val primaryAccount = buildJsonObject {
            put("server", "primary"); put("name", "主服"); put("line_url", "https://alternate.invalid")
            put("user_id", "primary-user"); put("aggregate_primary", true); put("active", true)
        }
        core.ret("account.listAccounts", arr(primaryAccount))
        core.ret("emby.extRankingItems", arr(buildJsonObject {
            put("id", "tmdb:movie:42"); put("source", "tmdb"); put("external_id", "42")
            put("media_type", "movie"); put("title", "主服作品"); put("rank", 1)
        }))
        core.ret("emby.extResolve", buildJsonObject { put("item_id", "primary-item"); put("item_type", "Movie") })
        core.on("account.setActiveServer") {
            core.ret("emby.currentSession", buildJsonObject {
                put("server", "https://alternate.invalid"); put("user_id", "primary-user"); put("token", "fixture")
            })
            arr(primaryAccount)
        }
        open(core)
        rule.onNodeWithText("主服作品").performClick()
        rule.onNodeWithText("详情目标：primary-item").assertIsDisplayed()
        assertEquals("primary", core.calls.last { it.first == "emby.extResolve" }.second.str("server_id"))
        assertEquals("primary", core.calls.last { it.first == "account.setActiveServer" }.second.str("server_id"))
        val before = core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" }
        rule.runOnIdle { navigation.popBackStack() }
        rule.onNodeWithText("主服作品").assertIsDisplayed()
        assertEquals(before, core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" })
    }

    @Test fun privateRankingMatchesCurrentServerWithoutSwitchingToPrimary() {
        val core = rankings()
        core.ret("account.listAccounts", arr(JsonObject(account("primary", "主服", "", false).obj()!! +
            ("aggregate_primary" to JsonPrimitive(true)))))
        core.ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
        core.ret("emby.extRankings", arr(*listOf("huangguoai", "javranking").map { source -> buildJsonObject {
            put("id", source); put("source", source); put("label", "$source 榜单")
        } }.toTypedArray()))
        core.on("emby.extRankingItems") { a ->
            val source = a.str("category_id")
            if (a.long("page") != 1L) arr() else arr(*(1..3).map { n -> buildJsonObject {
                val id = if (source == "huangguoai") "${41 + n}" else "TEST-%03d".format(n)
                put("id", "$source:movie:$id"); put("source", source); put("external_id", id)
                put("media_type", "movie"); put("title", "$source 作品 $n"); put("rank", n)
            } }.toTypedArray())
        }
        core.on("emby.extResolve") { a -> buildJsonObject {
            put("item_id", if (a.str("source") == "huangguoai") "hga-work-fixture" else "nfo-work")
            put("item_type", "Movie")
        } }
        open(core, privateSpace = true)
        for ((source, label, target) in listOf(
            Triple("huangguoai", "黄果 AI", "hga-work-fixture"), Triple("javranking", "JavRanking", "nfo-work"),
        )) {
            rule.onNodeWithText(label).performClick()
            rule.onNodeWithText("$source 作品 1", useUnmergedTree = true).performTouchInput { click() }
            rule.onNodeWithText("详情目标：$target").assertIsDisplayed()
            val resolve = core.calls.last { it.first == "emby.extResolve" }.second
            assertEquals(source, resolve.str("source"))
            assertEquals(if (source == "huangguoai") "42" else "TEST-001", resolve.str("external_id"))
            assertEquals("movie", resolve.str("media_type"))
            val before = core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" }
            rule.runOnIdle { navigation.popBackStack() }
            rule.onNodeWithText("$source 榜单").assertIsDisplayed()
            assertEquals(before, core.calls.count { it.first == "emby.extRankings" || it.first == "emby.extRankingItems" })
        }
        assertEquals(0, core.calls.count { it.first == "account.setActiveServer" })
        core.calls.filter { it.first.startsWith("emby.ext") }.forEach { (_, a) ->
            assertEquals(JsonPrimitive(true), a?.get("request_scope").obj()?.get("private"))
            assertEquals(a?.get("request_scope").obj().str("server"), a.str("server_id"))
        }
    }

    @Test fun failedSessionRefreshDoesNotOpenCrossServerDetail() = runBlocking {
        val core = rankings()
        core.ret("account.setActiveServer", arr(buildJsonObject {
            put("server", "primary"); put("line_url", "https://alternate.invalid")
            put("user_id", "primary-user"); put("active", true)
        }))
        val app = AppState(core, scope)
        app.boot()
        core.on("emby.currentSession") { throw CoreException("E_NETWORK", "会话读取失败", true) }
        org.junit.Assert.assertFalse(app.activateMediaServer("primary"))
        app.bg.cancel()
    }
}
