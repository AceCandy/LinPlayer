package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
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
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.pages.LiuyingContent

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvLiuyingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    @After fun clean() { scope.cancel(); if (::app.isInitialized) app.bg.cancel(); PageCache.clear() }

    @Test fun emptyLibraryRemainsNativeAndSwitchesFeedWithoutCapabilityRefresh() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", true) }) })
            ret("emby.extLiuYingLabels", arr())
            ret("emby.extLiuYingItems", buildJsonObject { put("items", arr()); put("has_more", false) })
        }
        app = AppState(core, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        var exited = false
        rule.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalTvMode provides true) {
                TvFrame(app) { LiuyingContent({ exited = true }, tv = true) }
            }
        }
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        rule.onNodeWithText("返回").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.DirectionRight)
        rule.onNodeWithTag("liuying.bankai").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.waitForIdle()
        assertEquals("adult", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("feed"))
        assertEquals(1, core.calls.count { it.first == "emby.extCapabilities" })
        assertNull(core.calls.single { it.first == "emby.extCapabilities" }.second?.get("refresh"))
        assertFalse(core.calls.any { it.first == "player.play" })
        press(rule, Key.DirectionLeft, Key.DirectionCenter)
        assertTrue(exited)
    }
}
