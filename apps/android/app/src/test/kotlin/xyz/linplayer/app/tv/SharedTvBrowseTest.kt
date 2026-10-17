package xyz.linplayer.app.tv

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache

/** 从实际TV根进入，共用页面与遥控路径必须同时成立。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class SharedTvBrowseTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var core: FakeCore
    private lateinit var app: AppState
    private lateinit var back: OnBackPressedDispatcher

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun mount() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        core = FakeCore().loggedIn().movie()
        app = AppState(core, scope)
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvRoot(app)
        }
        rule.waitForIdle()
    }

    @Test fun actualTvEntryUsesSharedHomeAndTopNavigation() {
        mount()
        rule.onNodeWithText("聚合视界").assertIsDisplayed()
        rule.onNodeWithTag("phone.tabs").assertDoesNotExist()
        assertFalse(core.calls.any { it.first == "emby.listRandom" || it.first == "emby.listNextUp" })
        rule.onRoot().captureRoboImage("build/shared-tv/home.png")
    }

    @Test fun directionsOnlyMoveFocusAndConfirmOpensSharedAggregateWithoutDownloads() {
        mount()
        rule.onNodeWithTag("rail.1").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag("rail.4").assertIsFocused().assertIsNotSelected()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("rail.4").assertIsSelected()
        rule.onNodeWithText("排行榜").assertIsDisplayed()
        rule.onNodeWithText("日历").assertIsDisplayed()
        rule.onNodeWithText("下载").assertDoesNotExist()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("rail.4").assertIsNotFocused()
        rule.onRoot().captureRoboImage("build/shared-tv/aggregate.png")
    }

    @Test fun cardMenuAndDetailReturnKeepRemoteFocusAndHideDownload() {
        mount()
        val card = rule.onNodeWithContentDescription("谎言的代价")
        card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        card.assertIsFocused()
        press(rule, Key.Menu)
        rule.onNodeWithText("标为已看").assertIsDisplayed()
        rule.onNodeWithText("下载").assertDoesNotExist()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.hero").assertIsDisplayed()
        val hero = rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("TV详情头图应在首屏内留出播放区域", hero.height < screen.height * .65f)
        rule.onNodeWithContentDescription("下载").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/shared-tv/detail.png")
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused()
    }

    @Test fun serverNameIsSingleManagementEntryAndPluginCannotOpenDownloads() {
        mount()
        rule.onAllNodesWithText("服务器 A").assertCountEquals(1)
        rule.onNodeWithTag("rail.1").assertIsFocused()
        press(rule, Key.DirectionLeft, Key.DirectionCenter)
        rule.onNodeWithText("服务器", substring = false).assertIsDisplayed()
        rule.runOnIdle {
            assertFalse(xyz.linplayer.app.plugin.PluginNav.host!!.push("downloads", kotlinx.serialization.json.JsonObject(emptyMap()), false))
        }
        assertFalse(core.calls.any { it.first == "download.list" })
    }
}
