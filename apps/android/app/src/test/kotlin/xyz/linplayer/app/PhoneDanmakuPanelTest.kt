package xyz.linplayer.app

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.player.*
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
class PhoneDanmakuPanelTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kind = mutableStateOf<String?>("danmaku")
    private val itemId = mutableStateOf("episode")
    private lateinit var manager: ShadowWindowManagerImpl
    private var searches = 0
    private var stored = buildJsonObject {
        put("roll_lines", 5); put("top_lines", 4); put("bottom_lines", 3)
        put("area", 1.0); put("scale", 1.0); put("opacity", .5); put("speed", 1.0)
        put("bold", false); put("merge", true); put("prevent_overlap", true); put("heatmap", false)
    }
    private fun core() = FakeCore().loggedIn().apply {
        on("player.getDanmakuStyle") { stored }
        on("player.setDanmakuStyle") { a -> stored = JsonObject(stored + a.orEmpty().filterKeys { it in stored }); stored }
        ret("player.danmakuLayout", JsonNull)
        on("player.danmakuOffset") { a -> buildJsonObject { put("seconds", a.dbl("seconds") ?: 0.0) } }
    }
    @After fun clean() { scope.cancel(); DanmakuStyle.loaded.value = false; DanmakuStyle.source.value = "" }

    private fun open(core: CorePort, fontScale: Float = 1f) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        DanmakuStyle.loaded.value = false
        DanmakuStyle.source.value = "测试来源 · 测试作品 · 第2集"
        rule.setContent {
            LpTheme(darkOverride = false) { CompositionLocalProvider(LocalApp provides app,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                manager = Shadow.extract(LocalContext.current.getSystemService(Context.WINDOW_SERVICE))
                Box(Modifier.fillMaxSize().background(Color(0xFF31535E)).testTag("danmaku.host")) {
                    PlayerPanel(kind.value, itemId.value, anchor = Rect(480f, 290f, 528f, 338f),
                        onSearch = { searches++ }, onClose = { kind.value = null })
                }
            } }
        }
        rule.waitForIdle()
    }

    @Test fun 快捷菜单只保留显示区域设置() {
        val core = core()
        open(core)
        rule.onNodeWithText("测试来源", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("滚动弹幕行数").assertDoesNotExist()
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/danmaku-quick.png")
        val before = rule.onNodeWithTag("danmaku.host").captureToImage().toPixelMap()[10, 100]
        rule.onNodeWithText("显示设置").performClick()
        rule.onNodeWithText("显示区域").assertIsDisplayed()
        rule.onNodeWithText("不透明度").assertDoesNotExist()
        rule.onNodeWithText("字号").assertDoesNotExist()
        rule.onNodeWithText("滚动速度").assertDoesNotExist()
        rule.onNodeWithText("更多显示设置").assertDoesNotExist()
        val bounds = rule.onNodeWithTag("player.menu").fetchSemanticsNode().boundsInRoot
        assertEquals(280f, bounds.width, .5f)
        assertTrue(bounds.height <= 360)
        assertEquals(before, rule.onNodeWithTag("danmaku.host").captureToImage().toPixelMap()[10, 100])
        rule.onNodeWithText("显示区域").performClick(); rule.waitForIdle()
        assertEquals(0.25, stored.dbl("area")!!, .001)
        rule.onNodeWithText("1/4 屏").assertIsDisplayed()
        rule.onNodeWithText("显示区域").performClick(); rule.waitForIdle()
        assertEquals(0.5, stored.dbl("area")!!, .001)
        rule.onNodeWithText("显示区域").performClick(); rule.waitForIdle()
        assertEquals(0.75, stored.dbl("area")!!, .001)
        rule.onNodeWithText("显示区域").performClick(); rule.waitForIdle()
        assertEquals(1.0, stored.dbl("area")!!, .001)
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/danmaku-style-landscape.png")
        // 返回键回一级，外点才关闭整张菜单。
        rule.runOnIdle {
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.onNodeWithText("更换弹幕").assertIsDisplayed()
        rule.onNodeWithText("更换弹幕").performClick()
        rule.onNodeWithText("搜索弹幕").performClick()
        rule.waitForIdle()
        assertEquals(1, searches)
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
        rule.runOnIdle { itemId.value = "another"; kind.value = "danmaku" }
        rule.onNodeWithText("显示设置").performClick()
        rule.onNodeWithText("显示区域").assertIsDisplayed()
        rule.runOnIdle {
            val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            manager.views.last().dispatchTouchEvent(e); e.recycle()
        }
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun 大字竖屏样式与时间偏移可操作() {
        val core = core()
        open(core, 2f)
        rule.onNodeWithText("显示设置").performClick()
        rule.onNodeWithText("显示区域").assertIsDisplayed()
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/danmaku-style-portrait-large.png")
        rule.onNodeWithContentDescription("返回弹幕菜单").performClick()
        rule.onNodeWithText("时间偏移").performClick()
        rule.onNodeWithText("+").performClick()
        rule.waitForIdle()
        assertEquals(.5, core.calls.last { it.first == "player.danmakuOffset" }.second.dbl("seconds")!!, .001)
        rule.onNodeWithText("重置弹幕时间").performClick()
        rule.waitForIdle()
        assertEquals(0.0, core.calls.last { it.first == "player.danmakuOffset" }.second.dbl("seconds")!!, .001)
    }

    @Test fun 保存失败恢复滑杆和开关读数() {
        val core = core()
        core.on("player.setDanmakuStyle") { error("保存失败") }
        core.on("player.setDanmakuEnabled") { error("保存失败") }
        open(core)
        val before = DanmakuStyle.enabled.value
        rule.onNodeWithText("显示弹幕").performClick()
        rule.waitForIdle()
        assertEquals(before, DanmakuStyle.enabled.value)
        rule.onNodeWithText("显示设置").performClick()
        rule.onNodeWithText("显示区域").performClick()
        rule.waitForIdle()
        assertEquals(1.0, DanmakuStyle.area.doubleValue, .001)
    }

    @Test fun 未读取完不提交且读取失败允许重试() {
        val gate = CompletableDeferred<Unit>()
        val core = core()
        var fail = true
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.getDanmakuStyle") { gate.await(); if (fail) error("读取失败") }
                return core.callJson(command, args, onPartial)
            }
        }
        try {
            open(port)
            rule.onNodeWithText("显示设置").performClick()
            rule.onNodeWithText("显示区域").assertIsNotEnabled()
            rule.runOnIdle { gate.complete(Unit) }
            rule.onNodeWithText("弹幕设置读取失败").assertIsDisplayed()
            rule.runOnIdle { fail = false }
            rule.onNodeWithText("重试").performClick()
            rule.onNodeWithText("显示区域").assertIsEnabled()
            assertFalse(core.calls.any { it.first == "player.setDanmakuStyle" })
        } finally { gate.complete(Unit) }
    }
}
