package xyz.linplayer.app

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.platform.LocalContext
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
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
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.player.*
import xyz.linplayer.app.ui.theme.LpTheme

/** 真实Popup覆盖入口定位、背景联动、双向动画与松手提交。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
class PhonePlayerMenuTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel(); SubStyle.loaded.value = false }

    private fun core() = FakeCore().loggedIn().apply {
        ret("player.tracks", arr(
            buildJsonObject { put("id", "1"); put("kind", "audio"); put("title", "原声"); put("selected", true) },
            buildJsonObject { put("id", "2"); put("kind", "audio"); put("title", "配音") }))
    }

    @Test fun positionsStayInsideWindowAndAvoidTopBottomAndSideEntrances() {
        for (direction in LayoutDirection.entries) for (window in listOf(IntSize(320, 640), IntSize(640, 360))) {
            for (anchor in listOf(Rect(272f, 0f, 320f, 48f), Rect(160f, window.height - 48f, 208f, window.height.toFloat()),
                Rect(window.width - 48f, 140f, window.width.toFloat(), 188f))) {
                val size = IntSize(200, 144)
                val pos = PlayerMenuPositionProvider(anchor, 4).calculatePosition(IntRect.Zero, window, direction, size)
                val panel = Rect(pos.x.toFloat(), pos.y.toFloat(), (pos.x + size.width).toFloat(), (pos.y + size.height).toFloat())
                assertTrue(panel.left >= 0 && panel.right <= window.width && panel.top >= 0 && panel.bottom <= window.height)
                assertFalse("入口被菜单覆盖：$anchor / $panel", panel.overlaps(anchor))
            }
        }
    }

    // Popup有自己的窗口坐标系，使用实际窗口位置换算到播放页坐标。
    private fun popupBounds(manager: ShadowWindowManagerImpl): Rect {
        rule.onNode(isPopup()).captureToImage()
        val params = manager.views.last().layoutParams as android.view.WindowManager.LayoutParams
        val local = rule.onNodeWithTag("player.menu").fetchSemanticsNode().boundsInRoot
        return local.translate(androidx.compose.ui.geometry.Offset(params.x.toFloat(), params.y.toFloat()))
    }

    private fun showOsd(portrait: Boolean) {
        val app = AppState(core(), scope)
        runBlocking { app.boot() }
        val panel = mutableStateOf<String?>(null)
        val entrance = mutableStateOf("more")
        val speed = mutableStateOf(1.0)
        var manager: ShadowWindowManagerImpl? = null
        rule.setContent {
            LpTheme(darkOverride = false) { CompositionLocalProvider(LocalApp provides app,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                manager = Shadow.extract(LocalContext.current.getSystemService(Context.WINDOW_SERVICE))
                val anchors = remember { mutableStateMapOf<String, Rect>() }
                Box(Modifier.fillMaxSize().background(Color(0xFF31535E)).testTag("player.host")) {
                    Osd(portrait, "", title = "测试片源", episodeLabel = "S1E1 测试单集", position = 20.0,
                        duration = 120.0, paused = true, speed = speed.value, heat = emptyList(), pluginPanels = listOf(xyz.linplayer.app.ui.plugin.PlayerSurfaceInfo("test", "panel", "测试插件", "plugin", false)),
                        onBack = {}, onToggle = {}, onSeek = {}, onSpeed = { speed.value = it }, onLock = {}, onShot = {},
                        onPanel = { entrance.value = it; panel.value = it }, onPanelAnchor = { k, r -> anchors[k] = r },
                        onOrientation = {}, onChapters = {}, onPip = {}, compact = false)
                    PlayerPanel(panel.value, "episode", anchor = anchors[entrance.value], speed = speed.value,
                        onSpeed = { speed.value = it }, onOpen = { panel.value = it }, onClose = { panel.value = null })
                }
            } }
        }
        rule.waitForIdle()
        val before = rule.onNodeWithTag("player.host").captureToImage().toPixelMap()
        rule.onNodeWithContentDescription("音轨").performClick()
        val entranceBounds = rule.onNodeWithContentDescription("音轨").fetchSemanticsNode().boundsInWindow
        val bounds = popupBounds(manager!!)
        assertEquals(200f, bounds.width, .5f)
        assertFalse(bounds.overlaps(entranceBounds))
        rule.onNodeWithText("音轨").assertDoesNotExist()
        val selected = rule.onNodeWithText("原声", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val check = rule.onNodeWithTag("player.menu.check", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue(check.right <= selected.left)
        for (label in listOf("播放", "倍速", "锁屏", "音轨", "字幕", "切换方向"))
            rule.onNodeWithContentDescription(label).assertIsDisplayed()
        val after = rule.onNodeWithTag("player.host").captureToImage().toPixelMap()
        assertEquals(before[20, 140], after[20, 140])
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/${if (portrait) "portrait" else "landscape"}-audio-popup.png")
        rule.runOnIdle { panel.value = null }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("更多").performClick()
        assertEquals(192f, rule.onNodeWithTag("player.menu").fetchSemanticsNode().boundsInWindow.width, .5f)
        assertFalse(popupBounds(manager!!).overlaps(
            rule.onNodeWithContentDescription("更多").fetchSemanticsNode().boundsInWindow))
        rule.onNodeWithText("更多").assertDoesNotExist()
        assertTrue(rule.onAllNodesWithTag("player.menu.icon", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/${if (portrait) "portrait" else "landscape"}-more-popup.png")
        // 返回键由真实Popup窗口处理。
        rule.runOnIdle {
            val popup = manager!!.views.last()
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
        rule.onNodeWithContentDescription("倍速").performClick()
        rule.onNodeWithText("1.5×").performClick()
        rule.onNodeWithContentDescription("倍速").assertTextContains("1.50×")
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
        rule.onNodeWithContentDescription("音轨").performClick()
        rule.runOnIdle {
            val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { manager!!.views.last().dispatchTouchEvent(outside) } finally { outside.recycle() }
        }
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
        rule.onNodeWithText("测试插件").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithText("测试插件").performClick()
        val pluginEntrance = rule.onNodeWithText("测试插件").fetchSemanticsNode().boundsInWindow
        val pluginMenu = popupBounds(manager!!)
        assertFalse(pluginMenu.overlaps(pluginEntrance))
        assertTrue("插件菜单应在底部入口附近：$pluginMenu / $pluginEntrance", pluginMenu.bottom <= pluginEntrance.top && pluginMenu.bottom >= pluginEntrance.top - 8)
    }

    @Test @Config(qualifiers = "w180dp-h400dp-port-mdpi")
    fun menuShrinksWhenWindowIsNarrowerThanItsPreferredWidth() {
        val app = AppState(core(), scope)
        runBlocking { app.boot() }
        rule.setContent { LpTheme { CompositionLocalProvider(LocalApp provides app) {
            Box(Modifier.fillMaxSize()) { PlayerPanel("audio", "episode", onClose = {}) }
        } } }
        val menu = rule.onNodeWithTag("player.menu").fetchSemanticsNode().boundsInWindow
        assertTrue(menu.left >= 0 && menu.right <= 180 && menu.width <= 172)
        rule.onNodeWithText("原声").assertIsDisplayed()
    }

    @Test fun landscapeMenusFollowEntrancesAndSpeedDispatches() = showOsd(false)
    @Test @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun portraitMenusKeepControlsAtLargeFontInLightTheme() = showOsd(true)

    @Test fun fadeKeepsClosingContentThenRemovesPopup() {
        val open = mutableStateOf(true)
        var manager: ShadowWindowManagerImpl? = null
        rule.setContent { LpTheme {
            manager = Shadow.extract(LocalContext.current.getSystemService(Context.WINDOW_SERVICE))
            Box(Modifier.fillMaxSize().background(Color.White).testTag("menu.host")) {
                PlayerMenu(open.value, Rect(400f, 40f, 448f, 88f), 200.dp, onClose = { open.value = false }) {
                    PlayerMenuRow("原声", {})
                }
            }
        } }
        rule.waitForIdle()
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/popup-open.png")
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { open.value = false }
        var closingStarted = false
        for (frame in 0 until 20) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            rule.onNodeWithTag("menu.host").captureToImage()
            val params = manager!!.views.lastOrNull()?.layoutParams as? android.view.WindowManager.LayoutParams
            if (params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                closingStarted = true
                break
            }
        }
        assertTrue("关闭应先应用到真实Popup窗口", closingStarted)
        rule.onNodeWithText("原声").assertExists()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
    }

    @Test fun closingCancelsReadAndReopeningStartsFreshRequest() {
        val response = CompletableDeferred<Unit>()
        var reads = 0
        var readJob: Job? = null
        val core = core()
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.tracks" && ++reads == 1) {
                    readJob = currentCoroutineContext()[Job]
                    response.await()
                }
                return core.callJson(command, args, onPartial)
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        val kind = mutableStateOf<String?>("audio")
        rule.setContent { LpTheme { CompositionLocalProvider(LocalApp provides app) {
            Box(Modifier.fillMaxSize()) { PlayerPanel(kind.value, "episode", onClose = { kind.value = null }) }
        } } }
        try {
            rule.waitUntil(5_000) { reads == 1 }
            rule.runOnIdle { kind.value = null }
            rule.onNodeWithTag("player.menu").assertDoesNotExist()
            assertTrue("读取任务应已取消", readJob?.isCancelled == true)
            response.complete(Unit)
            rule.runOnIdle { kind.value = "audio" }
            rule.onNodeWithText("原声").assertIsDisplayed()
            assertEquals(2, reads)
        } finally { response.complete(Unit) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun subtitleSlidersWaitForPreferencesAndWriteOnceOnRelease() {
        val loaded = CompletableDeferred<Unit>()
        val core = core().apply {
            ret("player.getSubStyle", buildJsonObject { put("position", 100); put("scale", 1.0) })
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.getSubStyle") loaded.await()
                return core.callJson(command, args, onPartial)
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        SubStyle.loaded.value = false
        rule.setContent { LpTheme(darkOverride = false) { CompositionLocalProvider(LocalApp provides app) {
            PlayerPanel("substyle", "episode", onClose = {})
        } } }
        try {
            rule.onNodeWithContentDescription("字幕位置").assertIsNotEnabled()
            rule.runOnIdle { loaded.complete(Unit) }
            rule.onNodeWithContentDescription("字幕位置").assertIsEnabled()
            for ((label, field) in listOf("字幕位置" to "position", "字幕大小" to "scale")) {
                val slider = rule.onNodeWithContentDescription(label)
                val before = core.calls.count { it.first == "player.setSubStyle" }
                slider.performTouchInput { down(center); moveTo(centerRight - androidx.compose.ui.geometry.Offset(16f, 0f)); advanceEventTime(200) }
                assertEquals(before, core.calls.count { it.first == "player.setSubStyle" })
                slider.performTouchInput { up() }
                rule.waitForIdle()
                val writes = core.calls.filter { it.first == "player.setSubStyle" }
                assertEquals(before + 1, writes.size)
                val value = writes.last().second.dbl(field)!!
                if (field == "position") { assertTrue(value in 0.0..150.0); assertEquals(0.0, value % 5, .001) }
                else { assertTrue(value in .2..4.0); assertEquals(value * 10, kotlin.math.round(value * 10), .001) }
            }
            rule.onNodeWithText("粗体").assertExists()
            rule.onNodeWithText("字号跟窗口缩放").assertExists()
            rule.onNode(isPopup()).captureRoboImage("build/player-osd/substyle-slider-light.png")
        } finally { loaded.complete(Unit) }
    }
}
