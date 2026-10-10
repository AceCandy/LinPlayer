package xyz.linplayer.app

import com.github.takahirom.roborazzi.captureRoboImage
import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.navigation.toRoute
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeDown
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.theme.LpTheme

/** 真实手机首页的导航恢复、下拉手势与请求次数回归。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneHomeRefreshTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nav: NavHostController
    private var imageCache: HttpServer? = null
    private val cacheRequests = CopyOnWriteArrayList<String>()

    @After fun clean() {
        imageCache?.stop(0)
        PageCache.clear()
        scope.cancel()
        xyz.linplayer.app.data.UiPrefs.hideHomeLibraries.value = false
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun openHome(core: CorePort, fontScale: Float = 1f, dark: Boolean = false, missingImages: Boolean = false,
        cachedImages: Boolean = true, expectHero: Boolean = cachedImages, beforeHome: suspend (AppState) -> Unit = {}) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        if (missingImages) coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(
            ApplicationProvider.getApplicationContext<android.content.Context>())
            .components { add(coil3.fetch.Fetcher.Factory<coil3.Uri> { _, _, _ ->
                coil3.fetch.Fetcher { error("测试缺少封面") }
            }) }.build())
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        imageCache = local
        local.createContext("/") { exchange ->
            cacheRequests.add(exchange.requestURI.path)
            assertEquals("/img-cache", exchange.requestURI.path)
            assertEquals("HEAD", exchange.requestMethod)
            if (cachedImages) exchange.responseHeaders.set("X-LP-Cache", "hit")
            exchange.sendResponseHeaders(if (cachedImages) 200 else 404, -1)
            exchange.close()
        }
        local.start()
        val app = AppState(object : CorePort by core {
            override val localBaseUrl = "http://127.0.0.1:${local.address.port}"
        }, scope)
        runBlocking { app.boot(); beforeHome(app) }
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(
                    LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Home) {
                        composable<Route.Home> { HomePage(nav) }
                        composable<Route.Settings> { Text("其它页面") }
                        composable<Route.Detail> { Text("作品详情") }
                        composable<Route.Library> { Text("媒体库目标：" + it.toRoute<Route.Library>().viewId) }
                        composable<Route.Servers> { Text("服务器管理目标") }
                    }
                }
            }
        }
        rule.waitForIdle()
        if (expectHero) rule.waitUntil(5000) {
            rule.onAllNodesWithTag("home.banners").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun uncachedImagesDoNotCreateHeroOrFetchOrdinaryImageRoute() {
        val core = FakeCore().loggedIn().apply { ret("emby.listResume", arr(item("cold", "未缓存作品"))) }
        openHome(core, cachedImages = false)
        rule.waitUntil(5000) { cacheRequests.isNotEmpty() }
        rule.onNodeWithTag("home.banners").assertDoesNotExist()
        assertTrue(cacheRequests.all { it == "/img-cache" })
    }

    @Test fun heroUsesPreviouslyBrowsedLibraryOutsideHomeWithoutExtraServerQuery() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.listResume", arr())
            ret("emby.listLatest", arr())
            ret("emby.listCollections", arr())
            ret("emby.listItemsPage", arr(item("outside-home", "本地缓存的库作品"), item("newly-seen", "刚标记已看")))
            ret("emby.setPlayed", buildJsonObject { put("item_id", "newly-seen"); put("played", true) })
        }
        openHome(core, beforeHome = { it.call("emby.listItemsPage"); it.call("emby.setPlayed") })
        rule.onNodeWithText("本地缓存的库作品").assertIsDisplayed()
        rule.onNodeWithText("刚标记已看").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "emby.listItemsPage" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

    @Test fun resumeShowsEpisodeAndRemainingTime() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listResume", arr(item("resume-ui", "重逢", "Episode",
            series = "远方的故事", season = 1, episode = 12, runtime = 3600.0, resume = 1801.0)))
        openHome(core)
        rule.onNodeWithText("S1E12", useUnmergedTree = true).performScrollTo()
        rule.onNodeWithText("剩余 29:59", useUnmergedTree = true).assertExists()
        rule.onRoot().captureRoboImage("build/resume-ui/home.png")
        assertEquals(1, core.calls.count { it.first == "emby.listResume" })
    }

    @Test fun hiddenLibrariesAlsoHideResumeTitleButKeepCards() {
        xyz.linplayer.app.data.UiPrefs.hideHomeLibraries.value = true
        val core = FakeCore().loggedIn()
        core.ret("emby.listResume", arr(item("hidden-row", "仍然显示的续播")))
        openHome(core)
        rule.onNodeWithTag("home.libraries").assertDoesNotExist()
        rule.onNodeWithText("继续观看").assertDoesNotExist()
        rule.onNode(hasText("仍然显示的续播") and hasAnyAncestor(hasTestTag("home.resume"))).assertIsDisplayed()
        val hiddenTop = rule.onNodeWithTag("home.resume").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { xyz.linplayer.app.data.UiPrefs.hideHomeLibraries.value = false }
        rule.onNodeWithTag("home.libraries").assertIsDisplayed()
        rule.onNodeWithText("继续观看").assertIsDisplayed()
        val shown = rule.onNode(hasContentDescription("仍然显示的续播") and
            hasAnyAncestor(hasTestTag("home.resume")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("默认开启隐藏时也应保持同一续播位置", hiddenTop, shown.top, 1f)
    }

    @Test fun heroBackgroundEndsAtResumeImageMiddleWithEitherLibrarySetting() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.listResume", arr(item("resume", "续播作品")))
        }
        openHome(core)
        fun check() {
            val image = rule.onNode(hasContentDescription("续播作品") and
                hasAnyAncestor(hasTestTag("home.resume")), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val background = rule.onNodeWithTag("home.hero.background").fetchSemanticsNode().boundsInRoot
            assertEquals("背景下沿必须跟随续播图片中点", image.center.y, background.bottom, 1f)
        }
        check()
        val resumeBefore = rule.onNode(hasContentDescription("续播作品") and
            hasAnyAncestor(hasTestTag("home.resume")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val playBefore = rule.onNode(hasText("播放") and hasAnyAncestor(hasTestTag("home.banners")))
            .fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { xyz.linplayer.app.data.UiPrefs.hideHomeLibraries.value = true }
        check()
        val resumeAfter = rule.onNode(hasContentDescription("续播作品") and
            hasAnyAncestor(hasTestTag("home.resume")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("隐藏封面不能上移续播", resumeBefore.top, resumeAfter.top, 1f)
        val playAfter = rule.onNode(hasText("播放") and hasAnyAncestor(hasTestTag("home.banners")))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("播放入口应移入隐藏封面腾出的空间", playAfter.top > playBefore.top + 60f)
        rule.runOnIdle { xyz.linplayer.app.data.UiPrefs.hideHomeLibraries.value = false }
        check()
        rule.onRoot().captureRoboImage("build/home-cinema/linked-background.png")
    }

    @Test fun homeDoesNotRequestNextUp() {
        val core = FakeCore().loggedIn()
        openHome(core)
        assertEquals(0, core.calls.count { it.first == "emby.listNextUp" })
        assertEquals(1, core.calls.count { it.first == "emby.listResume" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

    @Test fun 附加请求未返回时首页仍可进入媒体库且核心请求不重复() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.permissions", buildJsonObject {})
            ret("plugin.homeSections", arr())
        }
        val release = CompletableDeferred<Unit>()
        val pending = mutableSetOf<String>()
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "emby.permissions" || command == "plugin.homeSections") {
                    pending += command
                    release.await()
                }
                return result
            }
        }
        try {
            openHome(port)
            assertEquals(setOf("emby.permissions", "plugin.homeSections"), pending)
            assertTrue(!release.isCompleted)
            assertEquals(1, core.calls.count { it.first == "emby.views" })
            assertEquals(1, core.calls.count { it.first == "emby.listResume" })
            assertEquals(0, core.calls.count { it.first == "emby.listNextUp" || it.first == "emby.listRandom" })
            rule.onNode(hasText("电影") and hasClickAction()).performClick()
            rule.onNodeWithText("媒体库目标：lib-movie").assertIsDisplayed()
        } finally { release.complete(Unit) }
    }

    @Test fun returningHomeKeepsContentAndRefreshesRecommendations() {
        val core = FakeCore().loggedIn()
        openHome(core)
        val latestCalls = core.calls.count { it.first == "emby.listLatest" }
        val viewCalls = core.calls.count { it.first == "emby.views" }
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.waitForIdle()
        core.ret("emby.listResume", arr(item("new-resume", "更新后的续播", runtime = 3600.0, resume = 1800.0)))
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNode(hasText("更新后的续播") and hasAnyAncestor(hasTestTag("home.resume"))).assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.listResume" })
        assertTrue(core.calls.count { it.first == "emby.listLatest" } > latestCalls)
        assertEquals(viewCalls + 1, core.calls.count { it.first == "emby.views" })
    }

    @Test fun detailReturnKeepsPartiallyScrolledHeroPosition() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.listResume", arr(item("resume", "续播作品")))
        }
        openHome(core)
        val initial = rule.onNodeWithTag("home.banners").fetchSemanticsNode().boundsInRoot
        rule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput {
            swipeUp(startY = height * .4f, endY = height * .3f, durationMillis = 600)
        }
        val before = rule.onNodeWithTag("home.banners").fetchSemanticsNode().boundsInRoot
        assertTrue("复现须保留部分Hero并有滚动偏移", before.bottom < initial.bottom && before.bottom > 0)
        rule.runOnIdle { nav.navigate(Route.Detail("resume", "Movie")) }
        rule.onNodeWithText("作品详情").assertIsDisplayed()
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        val after = rule.onNodeWithTag("home.banners").fetchSemanticsNode().boundsInRoot
        assertEquals("返回不能删除再插入首项而带走滚动位置", before.bottom, after.bottom, 1f)
    }

    @Test fun pullDownRefreshesHome() {
        val core = FakeCore().loggedIn()
        openHome(core)
        core.ret("emby.listResume", arr(item("fresh", "下拉后的续播", runtime = 3600.0, resume = 900.0)))
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
        rule.onNode(hasText("下拉后的续播") and hasAnyAncestor(hasTestTag("home.resume"))).assertExists()
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
        assertEquals(2, core.calls.count { it.first == "emby.listResume" })
        assertEquals(2, core.calls.count { it.first == "emby.views" })
        assertEquals(0, core.calls.count { it.first == "emby.listNextUp" })
    }
    @Test fun pullDownCanRetryAfterFailedResumeRequest() {
        val core = FakeCore().loggedIn()
        openHome(core)
        core.on("emby.listResume") { throw CoreException("E_NETWORK", "测试网络失败", true) }
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "emby.listResume" })
        core.ret("emby.listResume", arr(item("retry", "重试后的续播", runtime = 3600.0, resume = 900.0)))
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
        rule.onNode(hasText("重试后的续播") and hasAnyAncestor(hasTestTag("home.resume"))).assertExists()
        assertEquals(3, core.calls.count { it.first == "emby.listResume" })
    }

    @Test fun libraryAndResumeFitBelowToolbar() {
        val core = FakeCore().loggedIn()
        openHome(core)
        val library = rule.onNodeWithTag("home.libraries").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val resume = rule.onNodeWithText("继续观看").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = rule.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot
        assertTrue("媒体库被顶栏盖住", library.top >= settings.bottom)
        assertTrue("媒体库应在续播之前", library.bottom < resume.top)
        assertTrue("续播没有进入首屏上半部", resume.top < 440f)
        rule.onRoot().captureRoboImage("build/resume-ui/compact-home.png")
    }

    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun largeFontKeepsFirstRowsAndToolbarVisible() {
        openHome(FakeCore().loggedIn(), fontScale = 1.3f)
        rule.onNodeWithTag("home.libraries").assertIsDisplayed()
        rule.onNodeWithText("继续观看").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索").assertDoesNotExist()
        rule.onNodeWithContentDescription("设置").assertIsDisplayed()
        val libraryTop = rule.onNodeWithTag("home.libraries").fetchSemanticsNode().boundsInRoot.top
        val toolbarBottom = rule.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("大字号下媒体库被顶栏遮住", libraryTop >= toolbarBottom)
        rule.onRoot().captureRoboImage("build/resume-ui/compact-home-large-font.png")
    }

    @Test fun offscreenLibrariesLoadOnceWhenVisible() {
        val core = FakeCore().loggedIn()
        core.ret("emby.views", arr(*(1..10).map { index ->
            buildJsonObject {
                put("id", "lib-$index"); put("name", "媒体库 $index"); put("collection_type", "movies")
            }
        }.toTypedArray()))
        core.on("emby.listLatest") { a -> arr(item("item-${a.str("parent_id")}", "最新影片")) }
        openHome(core)
        fun lastLibraryCalls() = core.calls.count {
            it.first == "emby.listLatest" && it.second.str("parent_id") == "lib-10"
        }
        assertEquals(0, lastLibraryCalls())
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToKey("latest-lib-10")
        rule.waitForIdle()
        assertEquals(1, lastLibraryCalls())
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToKey("views")
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToKey("latest-lib-10")
        rule.waitForIdle()
        assertEquals(1, lastLibraryCalls())
        rule.runOnIdle { nav.navigate(Route.Settings) }
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        assertEquals(2, lastLibraryCalls())
    }

    @Test fun emptyLatestLibraryDoesNotBlockNextVisibleLibrary() {
        val core = FakeCore().loggedIn()
        core.on("emby.listLatest") { a ->
            if (a.str("parent_id") == "lib-movie") arr()
            else arr(item("latest-series", "最新剧集", "Series"))
        }
        openHome(core)
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToKey("latest-lib-tv")
        rule.waitForIdle()
        rule.onNode(hasText("最新剧集") and !hasAnyAncestor(hasTestTag("home.banners"))).assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.listLatest" })
    }

    @Test fun failedLatestLibraryDoesNotBlockNextVisibleLibrary() {
        val core = FakeCore().loggedIn()
        core.on("emby.listLatest") { a ->
            if (a.str("parent_id") == "lib-movie") throw CoreException("E_NETWORK", "测试网络失败", true)
            else arr(item("fallback", "后续库的最新条目", "Series"))
        }
        openHome(core)
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToKey("latest-lib-tv")
        rule.waitForIdle()
        rule.onNode(hasText("后续库的最新条目") and !hasAnyAncestor(hasTestTag("home.banners"))).assertExists()
        assertEquals(2, core.calls.count { it.first == "emby.listLatest" })
    }

    @Test fun emptyLibrariesKeepResumeBelowToolbar() {
        val core = FakeCore().loggedIn()
        core.ret("emby.views", arr())
        openHome(core, expectHero = false)
        rule.onNodeWithText("这个账号下没有媒体库").assertIsDisplayed()
        rule.onNodeWithText("继续观看").assertIsDisplayed()
        assertEquals(0, core.calls.count { it.first == "emby.listLatest" })
        assertEquals(0, core.calls.count { it.first == "emby.listRandom" })
    }

    @Test fun libraryCoversHaveNoCaptionsAndTitleOpensLibrary() {
        openHome(FakeCore().loggedIn())
        rule.onNodeWithText("媒体库").assertDoesNotExist()
        rule.onAllNodes(hasText("电影") and hasAnyAncestor(hasTestTag("home.libraries")),
            useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("更多").assertCountEquals(0)
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToKey("latest-lib-movie")
        rule.onNode(hasText("电影") and hasClickAction()).performClick()
        rule.onNodeWithText("媒体库目标：lib-movie").assertIsDisplayed()
    }

    @Test fun missingCoverCentersNameAndKeepsLibraryClickable() {
        openHome(FakeCore().loggedIn(), missingImages = true)
        val name = rule.onNode(hasText("电影") and hasAnyAncestor(hasTestTag("home.libraries")),
            useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("home.libraries").fetchSemanticsNode().boundsInRoot
        assertTrue("缺图库名应居中", kotlin.math.abs(name.center.y - row.center.y) < 1f)
        rule.onRoot().captureRoboImage("build/home-feedback/missing-cover.png")
        rule.onNode(hasText("电影") and hasClickAction() and hasAnyAncestor(hasTestTag("home.libraries"))).performClick()
        rule.onNodeWithText("媒体库目标：lib-movie").assertIsDisplayed()
    }

    @Test fun noPrimaryFlagShowsNameEvenWhenImageServiceWouldSucceed() {
        val core = FakeCore().loggedIn()
        core.ret("emby.views", arr(buildJsonObject {
            put("id", "no-cover"); put("name", "无封面媒体库"); put("has_primary", false)
        }))
        openHome(core)
        rule.onNode(hasText("无封面媒体库") and hasAnyAncestor(hasTestTag("home.libraries")),
            useUnmergedTree = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/global-nav/no-primary.png")
    }

    private fun serverMenu(dark: Boolean) {
        openHome(FakeCore().loggedIn(), fontScale = 1.3f, dark = dark)
        rule.onNodeWithText("服务器 A").performClick()
        rule.onNodeWithText("管理服务器…").assertIsDisplayed()
        val pixels = rule.onNode(isPopup()).captureToImage().toPixelMap()
        val expected = (if (dark) xyz.linplayer.app.ui.theme.DarkColors else xyz.linplayer.app.ui.theme.LightColors)
            .mediaPanel.copy(alpha = 1f)
        val actual = pixels[pixels.width / 2, 3]
        assertTrue("菜单应为实色面板", kotlin.math.abs(actual.red - expected.red) < .01f &&
            kotlin.math.abs(actual.green - expected.green) < .01f && kotlin.math.abs(actual.blue - expected.blue) < .01f)
        rule.onNode(isPopup()).captureRoboImage("build/home-feedback/menu-${if (dark) "dark" else "light"}.png")
        rule.onNodeWithText("管理服务器…").performClick()
        rule.onNodeWithText("服务器管理目标").assertIsDisplayed()
    }
    @Test fun lightServerMenu() = serverMenu(false)
    @Test fun darkServerMenu() = serverMenu(true)

}
