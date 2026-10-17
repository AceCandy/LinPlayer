package xyz.linplayer.app.tv

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.pages.LiuyingContent

/** 网页使用脱敏地址；验证TV按键、双实例留存及离页释放。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvLiuyingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private lateinit var root: View
    private var exited = false
    private var showing by mutableStateOf(true)
    private val urls = listOf("https://example.invalid/flow", "https://example.invalid/second")

    @After fun clean() { scope.cancel(); app.bg.cancel(); PageCache.clear() }

    private fun open() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
        }
        app = AppState(core, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        rule.setContent {
            root = LocalView.current.rootView
            if (showing) TvFrame(app) { LiuyingContent({ exited = true }, tv = true, urls = urls) }
        }
        rule.waitForIdle()
    }

    private fun webViews(view: View = root): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun remoteSwitchesWebPagesWithoutReloadAndBackUsesCurrentHistory() {
        open()
        rule.onNodeWithTag("liuying.back").assertIsFocused()
        val first = webViews().single()
        assertEquals(urls[0], shadowOf(first).lastLoadedUrl)
        assertNull(shadowOf(first).getJavascriptInterface("LinPlayer"))
        assertFalse(first.settings.allowFileAccess)
        assertFalse(first.settings.allowContentAccess)
        press(rule, Key.DirectionRight, Key.DirectionCenter)
        rule.onNodeWithTag("liuying.bankai").assertIsFocused()
        val second = webViews().last()
        assertEquals(2, webViews().size)
        assertEquals(urls[1], shadowOf(second).lastLoadedUrl)
        assertEquals(View.GONE, first.visibility)
        assertTrue(shadowOf(first).lastEvaluatedJavascript.contains("__liuyingInactive = true"))
        press(rule, Key.DirectionCenter)
        assertSame(first, webViews().first())
        assertEquals(0, shadowOf(first).reloadInvocations)
        shadowOf(first).setCanGoBack(true)
        press(rule, Key.DirectionLeft, Key.DirectionCenter)
        assertEquals(1, shadowOf(first).goBackInvocations)
        assertFalse(exited)
        rule.onRoot().captureRoboImage("build/tv-privacy/liuying.png")
        rule.runOnIdle { showing = false }
        rule.waitForIdle()
        assertTrue(shadowOf(first).wasDestroyCalled())
        assertTrue(shadowOf(second).wasDestroyCalled())
    }
}
