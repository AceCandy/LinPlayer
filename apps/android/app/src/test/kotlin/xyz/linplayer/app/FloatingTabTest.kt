package xyz.linplayer.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.tv.series
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.page
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.components.LpTabBar
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpTheme

/** 悬浮图标导航的选中语义、触摸范围与主题渲染。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class FloatingTabTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun shellWithMovie() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = FakeCore().loggedIn().movie()
        core.ret("emby.views", arr())
        core.ret("emby.listResume", arr(item("m1", "沙丘 2", runtime = 9960.0, resume = 1788.0)))
        val app = AppState(core, scope)
        rule.setContent { LpTheme(darkOverride = true) { PhoneRoot(app) } }
        rule.waitForIdle()
    }

    @Test fun realShellDetailSlidesAndReturnsToOriginalPosterPosition() {
        shellWithMovie()
        val source = rule.onNodeWithText("沙丘 2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithContentDescription("沙丘 2").fetchSemanticsNode().boundsInRoot
        val x = (card.left + 8f).toInt()
        val y = (card.top + card.height / 4).toInt()
        val sourcePixel = rule.onRoot().captureToImage().toPixelMap()[x, y]
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("沙丘 2").performClick()
        rule.mainClock.advanceTimeBy(32)
        val first = rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot.left
        rule.mainClock.advanceTimeBy(80)
        val middle = rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot.left
        assertTrue("详情应从屏幕右侧滑入：$first → $middle", first > middle && middle > 0f)
        rule.onNode(hasText("沙丘 2") and !hasAnyAncestor(hasTestTag("detail.hero")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("滑入时来源图片不能淡出", sourcePixel, rule.onRoot().captureToImage().toPixelMap()[x, y])
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0f, rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot.left, .1f)
        rule.onNodeWithContentDescription("返回").performClick()
        rule.mainClock.advanceTimeBy(96)
        assertTrue("返回时详情应向右滑出", rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot.left > 0f)
        rule.onNode(hasText("沙丘 2") and !hasAnyAncestor(hasTestTag("detail.hero")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("滑出时来源图片应在下层直接可见", sourcePixel, rule.onRoot().captureToImage().toPixelMap()[x, y])
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("detail.hero").assertDoesNotExist()
        assertEquals(source, rule.onNodeWithText("沙丘 2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun realShellSeriesEpisodeReturnKeepsTheEpisodeSourcePosition() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = FakeCore().loggedIn().movie().series()
        val seriesDetail = core.handlers.getValue("emby.itemDetail")
        core.on("emby.itemDetail") { a ->
            if (a?.get("item_id") == JsonPrimitive("s1")) seriesDetail(a)
            else buildJsonObject {
                put("id", "se2e1"); put("type_", "Episode"); put("name", "分集导航验证")
                put("series_name", "寂静的星河"); put("series_id", "s1")
                put("season_id", "se2"); put("season_no", 2); put("episode_no", 1)
            }
        }
        core.ret("emby.views", arr())
        core.ret("emby.listResume", arr(item("s1", "寂静的星河", "Series")))
        val app = AppState(core, scope)
        rule.setContent { LpTheme(darkOverride = true) { PhoneRoot(app) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("寂静的星河").performClick()
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        val source = rule.onNodeWithTag("detail.ep.se2e1").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("detail.ep.se2e1").performClick()
        rule.mainClock.advanceTimeBy(112)
        val episodeHero = hasTestTag("detail.hero") and hasAnyDescendant(hasText("S2E1：分集导航验证"))
        assertTrue("分集也应随整页滑入", rule.onNode(episodeHero).fetchSemanticsNode().boundsInRoot.left > 0f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0f, rule.onNode(episodeHero).fetchSemanticsNode().boundsInRoot.left, .1f)
        rule.onNodeWithContentDescription("返回").performClick()
        rule.mainClock.advanceTimeBy(112)
        assertTrue("分集返回应滑出", rule.onNode(episodeHero).fetchSemanticsNode().boundsInRoot.left > 0f)
        rule.mainClock.advanceTimeBy(500)
        rule.onNode(episodeHero).assertDoesNotExist()
        assertEquals(source, rule.onNodeWithTag("detail.ep.se2e1").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun realShellDetailRespectsDisabledSystemMotion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val previous = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            shellWithMovie()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithContentDescription("沙丘 2").performClick()
            rule.mainClock.advanceTimeBy(32)
            assertEquals(0f, rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot.left, .1f)
            rule.onNodeWithContentDescription("返回").performClick()
            // 导航出栈仍需完成组合帧；零倍率不得留下位移动画。
            rule.mainClock.advanceTimeBy(96)
            rule.onNodeWithTag("detail.hero").assertDoesNotExist()
            rule.onNodeWithText("沙丘 2", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, previous)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun realShellNavigatesAndKeepsLastFavoriteAboveFloatingBar() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(*(1..18).map { item("fav-$it", "收藏影片 $it", runtime = 5400.0) }.toTypedArray()))
        val app = AppState(core, scope)
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        rule.setContent {
            back = checkNotNull(androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            LpTheme(darkOverride = false) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(1f, 1.3f)) { PhoneRoot(app) }
        } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("首页").assertIsSelected()
        rule.onRoot().captureRoboImage("build/floating-ui/home.png")
        rule.onNodeWithContentDescription("聚合视界").performClick().assertIsSelected()
        rule.onNodeWithText("服务器").assertExists()
        rule.onNodeWithContentDescription("收藏").performClick().assertIsSelected()
        rule.onNode(hasContentDescription("搜索") and hasAnyAncestor(hasTestTag("phone.tabs"))).performClick()
        rule.onNodeWithTag("phone.tabs").assertExists()
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("收藏").assertIsSelected()
        rule.onNodeWithText("收藏的电影").performClick()
        rule.onNodeWithContentDescription("收藏").assertIsSelected()
        rule.onNode(hasScrollToIndexAction()).performScrollToKey("fav-18")
        val last = rule.onNodeWithText("收藏影片 18", useUnmergedTree = true)
        last.assertIsDisplayed()
        val bottom = last.fetchSemanticsNode().boundsInRoot.bottom
        val barTop = rule.onNodeWithTag("phone.tabs").fetchSemanticsNode().boundsInRoot.top
        assertTrue("末项应能滚到悬浮栏上方", bottom < barTop)
        rule.onRoot().captureRoboImage("build/floating-ui/favorites-bottom.png")
        rule.onNode(hasContentDescription("搜索") and hasAnyAncestor(hasTestTag("phone.tabs"))).performClick()
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("收藏影片 18", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("搜索") and hasAnyAncestor(hasTestTag("phone.tabs"))).performClick()
        rule.onNodeWithContentDescription("聚合视界").performClick()
        rule.onNodeWithContentDescription("收藏").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search.field").assertDoesNotExist()
        rule.onNodeWithText("收藏的电影").assertIsDisplayed()
        rule.onNodeWithText("收藏影片 18", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("首页").performClick().assertIsSelected()
        rule.onNodeWithText("继续观看").assertExists()
    }

    @Test fun verticalScrollHidesAndRevealsGlobalBar() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(*(1..30).map { item("fav-$it", "收藏影片 $it") }.toTypedArray()))
        val app = AppState(core, scope)
        rule.setContent { LpTheme(darkOverride = true) { PhoneRoot(app) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("收藏").performClick()
        rule.onNodeWithText("收藏的电影").performClick()
        rule.onRoot().performTouchInput { swipeUp(startY = height * .7f, endY = height * .3f) }
        rule.waitForIdle()
        rule.onNodeWithTag("phone.tabs").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/global-nav/hidden-dark.png")
        rule.onRoot().performTouchInput { swipeDown(startY = height * .3f, endY = height * .6f) }
        rule.waitForIdle()
        rule.onNodeWithTag("phone.tabs").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/global-nav/visible-dark.png")
        rule.onNodeWithContentDescription("首页").performClick()
        rule.onNodeWithTag("home.libraries").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("phone.tabs").assertIsDisplayed()
    }

    @Test fun switchingTabsNeverRestoresSearchAsTabContent() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(item("fav", "收藏影片")))
        val app = AppState(core, scope)
        rule.setContent { LpTheme(darkOverride = true) { PhoneRoot(app) } }
        rule.waitForIdle()

        fun pick(name: String) {
            rule.onNodeWithContentDescription(name).performClick()
            rule.waitForIdle()
            rule.onNodeWithContentDescription(name).assertIsSelected()
            rule.onNodeWithTag("search.field").assertDoesNotExist()
            rule.onNodeWithText(when (name) {
                "首页" -> "继续观看"
                "聚合视界" -> "服务器"
                else -> "收藏的电影"
            }).assertExists()
        }

        // 聚合 → 搜索 → 收藏 → 聚合，原实现会把搜索页作为聚合栈一起恢复。
        for (origin in listOf("聚合视界", "收藏", "首页")) {
            for (target in listOf("首页", "聚合视界", "收藏")) {
                pick(origin)
                rule.onNode(hasContentDescription("搜索") and hasAnyAncestor(hasTestTag("phone.tabs"))).performClick()
                rule.onNodeWithTag("search.field").assertIsDisplayed()
                pick(target)
                pick(origin)
            }
        }
        rule.onRoot().captureRoboImage("build/floating-ui/tab-after-search-dark.png")
    }

    private fun render(dark: Boolean) {
        rule.setContent {
            LpTheme(darkOverride = dark) {
                var selected by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize().background(Lp.colors.bg), contentAlignment = Alignment.BottomCenter) {
                    LpTabBar(selected, onSearch = {}) { selected = it }
                }
            }
        }
        rule.onNodeWithContentDescription("首页").assertIsSelected()
        val search = rule.onNodeWithContentDescription("搜索").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val aggregate = rule.onNodeWithContentDescription("聚合视界").fetchSemanticsNode().boundsInRoot
        assertTrue("搜索应位于聚合视界左侧", search.right <= aggregate.left)
        for (name in listOf("聚合视界", "收藏", "首页")) {
            rule.onNodeWithContentDescription(name).performClick().assertIsSelected()
        }
        rule.onAllNodesWithText("首页").assertCountEquals(0)
        val first = rule.onNodeWithContentDescription("首页").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithContentDescription("收藏").fetchSemanticsNode().boundsInRoot
        assertTrue("底栏应居中悬浮，左右留空", first.left > 40f && last.right < 353f)
        assertTrue("触摸高度至少48dp", first.height >= 48f)
        rule.onRoot().captureRoboImage("build/floating-ui/${if (dark) "dark" else "light"}-tabs.png")
    }

    @Test fun lightTabs() = render(false)
    @Test fun darkTabs() = render(true)
}
