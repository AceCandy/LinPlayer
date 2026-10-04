package xyz.linplayer.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.loggedIn
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
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

    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun realShellNavigatesAndKeepsLastFavoriteAboveFloatingBar() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(*(1..18).map { item("fav-$it", "收藏影片 $it", runtime = 5400.0) }.toTypedArray()))
        val app = AppState(core, scope)
        rule.setContent { LpTheme(darkOverride = false) {
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
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("收藏").assertIsSelected()
        rule.onNode(hasScrollToIndexAction()).performScrollToKey("fav-18")
        val last = rule.onNodeWithText("收藏影片 18", useUnmergedTree = true)
        last.assertIsDisplayed()
        val bottom = last.fetchSemanticsNode().boundsInRoot.bottom
        val barTop = rule.onNodeWithTag("phone.tabs").fetchSemanticsNode().boundsInRoot.top
        assertTrue("末项应能滚到悬浮栏上方", bottom < barTop)
        rule.onRoot().captureRoboImage("build/floating-ui/favorites-bottom.png")
        rule.onNode(hasContentDescription("搜索") and hasAnyAncestor(hasTestTag("phone.tabs"))).performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("收藏影片 18", useUnmergedTree = true).assertIsDisplayed()
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
