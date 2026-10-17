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
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.kit.TvC

/** 真外壳的空间切换、返回栈及私密旁路回归，不连接实际服务器。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvPrivacyTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private lateinit var core: FakeCore
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var lifecycle: androidx.lifecycle.Lifecycle

    @After fun clean() { scope.cancel(); app.bg.cancel(); PageCache.clear() }

    private fun mount(classified: Boolean = true) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        core = FakeCore().loggedIn().apply {
            on("emby.views") { a -> arr(buildJsonObject {
                put("id", "library"); put("name", "媒体库")
                if (classified) put("is_adult", a?.get("is_adult")?.jsonPrimitive?.booleanOrNull ?: true)
            }) }
            on("emby.listResume") { a -> if (a?.get("is_adult") == JsonPrimitive(true))
                arr(item("private", "私密作品")) else arr(item("normal", "普通作品")) }
            ret("plugin.homeSections", arr())
            ret("plugin.pageTakeovers", arr())
            ret("emby.search", arr())
        }
        app = AppState(core, scope)
        rule.setContent {
            lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvRoot(app)
        }
        advance(rule, 800)
    }

    private fun enter(longClick: Boolean = false) {
        rule.onNodeWithTag("rail.1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionLeft)
        rule.onNodeWithTag("nav.server").assertIsFocused()
        if (longClick) rule.onNodeWithTag("nav.server").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        else press(rule, Key.Menu)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.waitForIdle()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            advance(rule, 200)
            TvC.privateMode == app.privateSpace.value
        }
        rule.waitForIdle()
    }

    @Test fun rootEnablesClassificationAndSpaceChangeDiscardsNavigationAndCaches() {
        mount()
        val normalColor = TvC.bg
        val normalKey = app.browseCache.key("s", "u", "query")
        assertEquals(JsonPrimitive(false), core.calls.last { it.first == "emby.listResume" }.second?.get("is_adult"))
        enter(longClick = true)
        assertTrue(app.privateSpace.value)
        assertNotEquals("lifecycle=${lifecycle.currentState}, revision=${app.spaceRevision.value}, mode=${TvC.privateMode}", normalColor, TvC.bg)
        assertNotEquals(normalKey, app.browseCache.key("s", "u", "query"))
        assertEquals(JsonPrimitive(true), core.calls.last { it.first == "emby.listResume" }.second?.get("is_adult"))
        rule.onNodeWithText("下载").assertDoesNotExist()
        listOf(0, 1, 3, 4).forEach { rule.onNodeWithTag("rail.$it").assertIsDisplayed() }
        listOf(2, 5, 6, 7).forEach { rule.onNodeWithTag("rail.$it").assertDoesNotExist() }
        assertTrue(rule.onAllNodesWithText("私密作品").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("普通作品").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/tv-privacy/home.png")
        rule.runOnUiThread { back.onBackPressed() }
        advance(rule, 800)
        assertFalse(app.privateSpace.value)
        assertEquals(normalColor, TvC.bg)
        listOf(0, 1, 3, 4, 7).forEach { rule.onNodeWithTag("rail.$it").assertIsDisplayed() }
        assertEquals(normalKey, app.browseCache.key("s", "u", "query"))
        assertTrue(rule.onAllNodesWithText("普通作品").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("私密作品").assertDoesNotExist()
        // 再按返回不能恢复私密页或其保存状态。
        rule.runOnUiThread { back.onBackPressed() }
        advance(rule, 100)
        assertFalse(app.privateSpace.value)
    }

    @Test fun unclassifiedServerCannotEnterAndPrivateSearchDoesNotUseHistoryOrPlugins() {
        mount(classified = false)
        enter()
        assertFalse(app.privateSpace.value)
        core.on("emby.views") { arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }) }
        enter(longClick = true)
        assertTrue(app.privateSpace.value)
        val before = core.calls.size
        rule.runOnUiThread { xyz.linplayer.app.plugin.PluginNav.host!!.push("search", JsonObject(emptyMap()), false) }
        advance(rule, 100)
        // 插件不能把私密导航拉到另一个页面。
        rule.onNodeWithTag("search.field").assertDoesNotExist()
        rule.onNodeWithTag("rail.1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionCenter)
        advance(rule, 500)
        rule.onNode(hasSetTextAction()).performTextInput("查询")
        advance(rule, 900)
        rule.onNodeWithText("搜索历史").assertDoesNotExist()
        val calls = core.calls.drop(before)
        assertTrue(calls.any { it.first == "emby.search" && it.second?.get("is_adult") == JsonPrimitive(true) })
        assertFalse(calls.any { it.first in setOf("prefs.pushSearch", "plugin.searchActions", "source.aggregateSearch") })
        assertFalse(TvRoute.History.privateAllowed())
        assertFalse(TvRoute.Downloads.privateAllowed())
        assertFalse(TvRoute.Player("x", "x", download = true).privateAllowed())
        assertTrue(TvRoute.Player("x", "x").privateAllowed())
    }

    @Test fun privateDiscoverOnlyOpensLiuyingAndServerClickExits() {
        mount(); enter()
        val before = core.calls.size
        rule.onNodeWithTag("rail.1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionCenter)
        advance(rule, 300)
        rule.onNodeWithText("流影").assertIsDisplayed()
        press(rule, Key.DirectionDown)
        rule.onNodeWithText("流影").assertIsFocused()
        assertFalse(core.calls.drop(before).any { it.first in setOf("emby.extRankings", "emby.aggregateOverview") })
        rule.runOnUiThread { back.onBackPressed() }
        advance(rule, 400)
        assertTrue(app.privateSpace.value)
        rule.onNodeWithTag("rail.1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionLeft)
        rule.onNodeWithTag("nav.server").assertIsFocused()
        press(rule, Key.DirectionCenter)
        advance(rule, 500)
        assertFalse(app.privateSpace.value)
    }
}
