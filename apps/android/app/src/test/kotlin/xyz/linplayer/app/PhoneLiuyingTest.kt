package xyz.linplayer.app

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LocalTabClearance
import xyz.linplayer.app.ui.pages.AggregatePage
import xyz.linplayer.app.ui.pages.LiuyingPage
import xyz.linplayer.app.ui.theme.LpTheme
import xyz.linplayer.app.ui.theme.Dim

/** 流影真实入口、双网页留存、暂停和返回释放；不请求实际私有页面。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneLiuyingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private lateinit var root: View
    private var navigationBottom = 0
    private val urls = listOf("https://example.invalid/flow", "https://example.invalid/second")

    @After fun clean() { PageCache.clear(); scope.cancel(); app.bg.cancel() }

    private fun open(pages: List<String> = urls) {
        PageCache.clear()
        val core = FakeCore().loggedIn().apply { ret("emby.aggregateOverview", arr()) }
        app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            root = LocalView.current.rootView
            LpTheme {
                navigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                CompositionLocalProvider(LocalApp provides app, LocalTabClearance provides Dim.tabClearance) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Aggregate) {
                        composable<Route.Aggregate> { AggregatePage(nav) }
                        composable<Route.Liuying> { LiuyingPage(nav, pages) }
                    }
                }
            }
        }
        rule.onNodeWithText("流影").performClick()
        rule.waitForIdle()
    }

    private fun webViews(view: View = root): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun webFillsWidthAndReachesNavigationSafeArea() {
        open()
        val bounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val web = rule.onNodeWithTag("liuying.web").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width, web.width, .1f)
        assertEquals("网页底部只保留系统导航安全区", bounds.bottom - navigationBottom, web.bottom, .1f)
        rule.onRoot().captureRoboImage("build/liuying-ui/full-content.png")
    }

    @Test fun tabsLoadLazilyPreserveViewsAndPauseHiddenMedia() {
        open()
        rule.onNodeWithTag("liuying.tab.0").assertIsSelected()
        val first = webViews().single()
        assertEquals(urls[0], shadowOf(first).lastLoadedUrl)
        assertNull(shadowOf(first).getJavascriptInterface("LinPlayer"))
        assertFalse(first.settings.allowFileAccess)
        rule.onNodeWithTag("liuying.tab.1").performClick()
        rule.onNodeWithTag("liuying.tab.1").assertIsSelected()
        val second = webViews().last()
        assertEquals(2, webViews().size)
        assertEquals(urls[1], shadowOf(second).lastLoadedUrl)
        assertEquals(View.GONE, first.visibility)
        shadowOf(first).lastEvaluatedJavascriptCallback.onReceiveValue("null")
        assertTrue(shadowOf(first).wasOnPauseCalled())
        assertTrue(shadowOf(first).lastEvaluatedJavascript.contains("__liuyingInactive = true"))
        first.scrollTo(0, 120)
        rule.onNodeWithTag("liuying.tab.0").performClick()
        rule.onNodeWithTag("liuying.tab.0").assertIsSelected()
        assertSame(first, webViews().first())
        assertEquals(120, first.scrollY)
        assertEquals(0, shadowOf(first).reloadInvocations)
        assertEquals(View.GONE, second.visibility)
        assertTrue(shadowOf(second).lastEvaluatedJavascript.contains("__liuyingInactive = true"))
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("下载").assertIsDisplayed()
        assertTrue(shadowOf(first).wasDestroyCalled())
        assertTrue(shadowOf(second).wasDestroyCalled())
    }

    @Test fun backUsesCurrentPageHistoryBeforeLeaving() {
        open()
        val first = webViews().single()
        shadowOf(first).setCanGoBack(true)
        rule.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, shadowOf(first).goBackInvocations)
        rule.onNodeWithTag("liuying.tab.0").assertIsDisplayed()
        assertFalse(shadowOf(first).wasDestroyCalled())
    }

    @Test fun bothPagesEnableInlineMediaPlayback() {
        open()
        val first = webViews().single()
        assertNotNull(first.webChromeClient)
        assertFalse(first.settings.mediaPlaybackRequiresUserGesture)
        rule.onNodeWithTag("liuying.tab.1").performClick()
        val second = webViews().last()
        assertNotNull(second.webChromeClient)
        assertFalse(second.settings.mediaPlaybackRequiresUserGesture)
        // 暂停由页面生命周期控制，不能改变网页加载时的自动播放策略。
        assertFalse(first.settings.mediaPlaybackRequiresUserGesture)
    }

    @Test fun latePauseCallbackDoesNotPauseReselectedOrDestroyedPage() {
        open()
        val first = webViews().single()
        rule.onNodeWithTag("liuying.tab.1").performClick()
        val latePause = shadowOf(first).lastEvaluatedJavascriptCallback
        rule.onNodeWithTag("liuying.tab.0").performClick()
        latePause.onReceiveValue("null")
        assertFalse(shadowOf(first).wasOnPauseCalled())
        rule.onNodeWithTag("liuying.tab.1").performClick()
        val leavingPause = shadowOf(first).lastEvaluatedJavascriptCallback
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        assertTrue(shadowOf(first).wasDestroyCalled())
        leavingPause.onReceiveValue("null")
        assertFalse(shadowOf(first).wasOnPauseCalled())
    }

    @Test fun missingConfigurationShowsStateWithoutCreatingWebView() {
        open(listOf("", ""))
        rule.onNodeWithText("当前安装包尚未配置此页面").assertIsDisplayed()
        assertEquals(0, webViews().size)
    }
}
