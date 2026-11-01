package xyz.linplayer.app.tv

import org.junit.Assert.assertFalse
import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.boolOrNull
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.player.DanmakuStyle

/**
 * 焦点路径断言(UI_TV.md §11.3)。☠ 焦点问题在静态截图上**完全看不出来** —— 出图之外必须有按键驱动的断言。
 * 按键走 `performKeyInput`,返回走 OnBackPressedDispatcher —— 和遥控器落到的是同一组处理。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvFocusTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var app: AppState
    private lateinit var core: FakeCore
    private lateinit var nav: TvNav
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var view: android.view.View

    @Before fun boot() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
    }

    private fun mount(route: TvRoute.Player, intercept: ((FakeCore) -> xyz.linplayer.app.core.CorePort)? = null, setup: FakeCore.() -> Unit = {}) {
        core = FakeCore().loggedIn().apply {
            for (command in listOf("player.stopPlayback", "player.setPause", "player.setSpeed", "player.seek"))
                ret(command, kotlinx.serialization.json.JsonNull)
        }.apply(setup)
        app = AppState(intercept?.invoke(core) ?: core, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        runBlocking { app.boot() }
        nav = TvNav().apply { push(route) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            view = androidx.compose.ui.platform.LocalView.current
            TvFrame(app) { PlayerHost(nav) }
        }
        advance(rule, 800)
    }

    private fun focused(tag: String) = rule.onNode(hasTestTag(tag)).assertIsFocused()
    private fun pressBack() { rule.runOnUiThread { back.onBackPressed() }; advance(rule, 300) }

    private val prepared = mutableMapOf<String, kotlinx.serialization.json.JsonObject>()
    private fun mountQueued(intercept: ((FakeCore) -> xyz.linplayer.app.core.CorePort)? = null, setup: FakeCore.() -> Unit = {}) {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv"), intercept) {
            player()
            ret("player.status", args("buffered" to 3660.0))
            ret("player.warmPrepared", kotlinx.serialization.json.JsonNull)
            ret("player.cancelPrepared", kotlinx.serialization.json.JsonNull)
            on("player.prepareNext") { a ->
                args("request_id" to a.str("request_id")!!, "item_id" to a.str("item_id")!!,
                    "media_source_id" to "fixture", "resume_secs" to 0.0)
                    .also { prepared[a.str("request_id")!!] = it }
            }
            on("player.queuePrepared") { a -> prepared.getValue(a.str("request_id")!!) }
            setup()
        }
    }

    private fun queueNext(): kotlinx.serialization.json.JsonObject {
        core.tick(3651.0); advance(rule, 300)
        core.tick(3651.25); advance(rule, 1500)
        return prepared.getValue(core.calls.last { it.first == "player.queuePrepared" }.second.str("request_id")!!)
    }

    private fun advanced(result: kotlinx.serialization.json.JsonObject) {
        rule.runOnIdle { core.events.tryEmit(CoreEvent("player.queueAdvanced", result)) }
        advance(rule, 600)
    }

    @Test fun 精简播控保留下一集和倍率且普通遥控可操作() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        rule.onNodeWithText("字幕").assertDoesNotExist()
        rule.onNodeWithContentDescription("字幕").assertExists()
        listOf("osd.play", "osd.prev", "osd.rew", "osd.fwd", "osd.eps").forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
        focused("osd.bar")
        press(rule, Key.DirectionUp); focused("osd.next")
        press(rule, Key.DirectionRight); focused("osd.speed")
        press(rule, Key.DirectionRight); focused("osd.dm")
        press(rule, Key.DirectionDown); focused("osd.bar")
        press(rule, Key.DirectionRight)
        press(rule, Key.Enter)
        assertEquals(1, core.calls.count { it.first == "player.seek" })
    }

    @Test fun 隐藏确认只切换暂停且进度确认不重复暂停() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            on("player.setPause") { a ->
                events.tryEmit(CoreEvent("player.status", args("position" to 1200.0, "duration" to 3660.0, "paused" to a.bool("paused"))))
                kotlinx.serialization.json.JsonNull
            }
        }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        pressBack(); focused("player.root")
        press(rule, Key.Enter)
        assertTrue(core.calls.last { it.first == "player.setPause" }.second.bool("paused"))
        rule.onNodeWithTag("osd.bar").assertDoesNotExist()
        press(rule, Key.Enter)
        assertFalse(core.calls.last { it.first == "player.setPause" }.second.bool("paused"))
        press(rule, Key.DirectionRight); focused("osd.bar")
        assertFalse(core.calls.any { it.first == "player.seek" })
        press(rule, Key.Enter)
        assertTrue(core.calls.last { it.first == "player.setPause" }.second.bool("paused"))
        press(rule, Key.DirectionRight, Key.Enter)
        assertEquals(1, core.calls.count { it.first == "player.seek" })
        assertEquals(3, core.calls.count { it.first == "player.setPause" })
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 隐藏时长按确认恢复倍率且松手不暂停不切集() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player(); ret("player.setSpeed", kotlinx.serialization.json.JsonNull)
        }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        pressBack(); focused("player.root")
        rule.onRoot().performKeyInput { keyDown(Key.Enter) }
        advance(rule, 750)
        assertEquals(xyz.linplayer.app.data.UiPrefs.tvHoldSpeed.value,
            core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        rule.onRoot().performKeyInput { keyUp(Key.Enter) }
        advance(rule, 250)
        assertEquals(1.0, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        assertFalse(core.calls.any { it.first == "player.setPause" })
        assertEquals(1, core.calls.count { it.first == "player.play" })
        focused("player.root")
    }

    @Test fun 下一集聚焦时确认长按重复事件不切集且之后短按可用() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        press(rule, Key.DirectionUp); focused("osd.next")
        val start = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int = 0) {
            rule.runOnUiThread { view.dispatchKeyEvent(android.view.KeyEvent(start, android.os.SystemClock.uptimeMillis(),
                action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, repeat)) }
            advance(rule, 100)
        }
        send(android.view.KeyEvent.ACTION_DOWN)
        advance(rule, 650)
        send(android.view.KeyEvent.ACTION_DOWN, 1)
        send(android.view.KeyEvent.ACTION_DOWN, 2)
        send(android.view.KeyEvent.ACTION_UP)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.setPause" })
        assertEquals(1.0, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        focused("osd.next")
        press(rule, Key.Enter); advance(rule, 600)
        assertEquals("sh7", lastPlayed())
    }

    @Test fun 预览时按住确认不提前提交不倍速且抬起只跳转一次() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        press(rule, Key.DirectionRight)
        val speedCalls = core.calls.count { it.first == "player.setSpeed" }
        rule.onRoot().performKeyInput { keyDown(Key.Enter) }
        advance(rule, 900)
        assertFalse(core.calls.any { it.first == "player.seek" })
        assertEquals(speedCalls, core.calls.count { it.first == "player.setSpeed" })
        rule.onRoot().performKeyInput { keyUp(Key.Enter) }
        advance(rule, 900)
        assertEquals(1, core.calls.count { it.first == "player.seek" })
        assertFalse(core.calls.any { it.first == "player.setPause" })
    }

    @Test fun 季末下集禁用时上行直达倍率() {
        mount(TvRoute.Player("sh10", "季末", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        press(rule, Key.DirectionUp); focused("osd.speed")
        press(rule, Key.DirectionDown); focused("osd.bar")
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun MPV队列连续接管不重复起停且忽略旧事件() {
        mountQueued()
        for (next in listOf("sh7", "sh8")) {
            val result = queueNext()
            assertEquals(next, result.str("item_id"))
            core.tick(3660.0, eof = true); advance(rule, 200)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            advanced(result)
            assertEquals(next, core.calls.last { it.first == "emby.itemDetail" }.second.str("item_id"))
            advanced(result)
        }
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals(0, core.calls.count { it.first == "player.stopPlayback" })
        press(rule, Key.MediaStop)
        assertEquals("sh8", core.calls.last { it.first == "player.stopPlayback" }.second.str("item_id"))
    }

    @Test fun 队列失败和核心取消都回退普通连播() {
        mountQueued { ret("player.queuePrepared", kotlinx.serialization.json.JsonNull) }
        queueNext()
        core.tick(3660.0, eof = true); advance(rule, 1000)
        assertEquals("sh7", lastPlayed())
        core.on("player.queuePrepared") { a -> prepared.getValue(a.str("request_id")!!) }
        val result = queueNext()
        rule.runOnIdle { core.events.tryEmit(CoreEvent("player.queueCanceled", result)) }
        advance(rule, 300)
        core.tick(3660.0, eof = true); advance(rule, 1000)
        assertEquals("sh8", lastPlayed())
        assertEquals(3, core.calls.count { it.first == "player.play" })
    }

    @Test fun 跳进度取消队列且迟到跨集事件无效() {
        mountQueued()
        val result = queueNext()
        press(rule, Key.MediaRewind)
        advanced(result)
        assertTrue(core.calls.any { it.first == "player.cancelPrepared" && it.second.str("request_id") == result.str("request_id") })
        assertEquals("sh6", core.calls.last { it.first == "emby.itemDetail" }.second.str("item_id"))
        assertEquals(1, core.calls.count { it.first == "player.seek" })
    }

    @Test fun 已排队时手动下一集走正常切换不消费失效准备() {
        mountQueued()
        val result = queueNext()
        press(rule, Key.MediaNext); advance(rule, 1000)
        advanced(result)
        assertEquals("sh7", lastPlayed())
        assertEquals(2, core.calls.count { it.first == "player.play" })
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        assertEquals(null, core.calls.last { it.first == "player.play" }.second.str("prepared_id"))
    }

    @Test fun 手动下一集消费已准备结果但不等待慢准备() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val returned = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            mountQueued(intercept = { fake -> object : xyz.linplayer.app.core.CorePort by fake {
                override suspend fun callJson(command: String, args: kotlinx.serialization.json.JsonObject?,
                    onPartial: ((kotlinx.serialization.json.JsonElement) -> Unit)?): kotlinx.serialization.json.JsonElement {
                    val result = fake.callJson(command, args, onPartial)
                    if (command == "player.prepareNext" && args.str("item_id") == "sh8") {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                        returned.complete(Unit)
                    }
                    return result
                }
            } })
            core.tick(3601.0); advance(rule, 500)
            val request = core.calls.single { it.first == "player.prepareNext" }.second.str("request_id")
            press(rule, Key.MediaNext); advance(rule, 600)
            assertEquals(request, core.calls.last { it.first == "player.play" }.second.str("prepared_id"))
            press(rule, Key.MediaNext); advance(rule, 600)
            assertEquals("sh8", lastPlayed())
            assertEquals(null, core.calls.last { it.first == "player.play" }.second.str("prepared_id"))
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 500)
            assertTrue(returned.isCompleted)
            assertEquals(3, core.calls.count { it.first == "player.play" })
        } finally { release.complete(Unit) }
    }

    @Test fun 换版本取消队列且旧跨集事件不覆盖新版本() {
        mountQueued()
        val result = queueNext()
        press(rule, Key.Menu)
        rule.onNodeWithText("版本").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter, Key.DirectionDown, Key.Enter); advance(rule, 700)
        advanced(result)
        assertEquals("sh6", lastPlayed())
        assertEquals("eb", core.calls.last { it.first == "player.play" }.second.str("media_source_id"))
        assertTrue(core.calls.any { it.first == "player.cancelPrepared" && it.second.str("request_id") == result.str("request_id") })
    }

    @Test fun 切核取消队列且旧内核跨集事件被忽略() {
        mountQueued()
        val result = queueNext()
        press(rule, Key.Menu)
        rule.onNodeWithText("切换到 Media3 内核").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter); advance(rule, 1000)
        advanced(result)
        assertEquals("sh6", lastPlayed())
        assertEquals("exo", core.calls.last { it.first == "player.play" }.second.str("engine"))
        assertTrue(core.calls.any { it.first == "player.cancelPrepared" && it.second.str("request_id") == result.str("request_id") })
    }

    @Test fun 队列慢回执在离页后不能接管新会话() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val returned = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            mountQueued(intercept = { fake -> object : xyz.linplayer.app.core.CorePort by fake {
                override suspend fun callJson(command: String, args: kotlinx.serialization.json.JsonObject?,
                    onPartial: ((kotlinx.serialization.json.JsonElement) -> Unit)?): kotlinx.serialization.json.JsonElement {
                    val result = fake.callJson(command, args, onPartial)
                    if (command == "player.queuePrepared") {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                        returned.complete(Unit)
                    }
                    return result
                }
            } })
            val result = queueNext()
            press(rule, Key.MediaStop)
            assertFalse(nav.top.route is TvRoute.Player)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            assertTrue(returned.isCompleted)
            advanced(result)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertTrue(core.calls.any { it.first == "player.cancelPrepared" && it.second.str("request_id") == result.str("request_id") })
        } finally { release.complete(Unit) }
    }

    @Test fun 更多保留上一集且倍率面板返回恢复原按钮() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        press(rule, Key.DirectionUp, Key.DirectionRight, Key.Enter)
        rule.onNodeWithText("倍速").assertExists()
        pressBack(); focused("osd.speed")
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.Enter)
        rule.onNodeWithText("倍速").assertDoesNotExist()
        pressBack(); focused("osd.more")
        press(rule, Key.Enter)
        rule.onNodeWithText("上一集").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter); advance(rule, 800)
        assertEquals("sh5", lastPlayed())
    }

    @Test fun 弹幕四项设置可遥控且区域按四档循环() {
        var style = args(
            "area" to 1.0, "scale" to 1.0, "opacity" to 1.0, "speed" to 1.0,
            "roll_lines" to 5, "top_lines" to 4, "bottom_lines" to 3,
            "bold" to false, "merge" to false, "prevent_overlap" to true, "heatmap" to false,
        )
        DanmakuStyle.loaded.value = false
        DanmakuStyle.layout.value = null
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            ret("player.getDanmakuStyle", style)
            ret("prefs.getPrefs", args("danmaku_enabled" to false))
            ret("player.danmakuLayout", args("items" to kotlinx.serialization.json.JsonArray(emptyList())))
            ret("player.danmakuHeatmap", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(1.0))))
            on("player.setDanmakuStyle") { a -> kotlinx.serialization.json.JsonObject(style + a.orEmpty()).also { style = it } }
        }
        core.tick(1200.0); advance(rule, 100)
        core.tick(1200.25); advance(rule, 100)
        press(rule, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight, Key.Enter); advance(rule, 400)
        rule.onNodeWithText("当前视频").assertExists()
        rule.onNodeWithText("显示区域").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(4) { press(rule, Key.Enter); advance(rule, 200) }
        val areas = core.calls.filter { it.first == "player.setDanmakuStyle" }.mapNotNull { it.second.dbl("area") }
        assertEquals(listOf(.25, .5, .75, 1.0), areas.takeLast(4))
        assertEquals(20.0, style.dbl("roll_lines")!!, .001)
        assertEquals(20.0, style.dbl("top_lines")!!, .001)
        assertEquals(20.0, style.dbl("bottom_lines")!!, .001)
        press(rule, Key.DirectionDown, Key.DirectionLeft); advance(rule, 200)
        assertEquals(.9, style.dbl("opacity")!!, .001)
        press(rule, Key.DirectionDown, Key.DirectionRight); advance(rule, 200)
        assertEquals(1.1, style.dbl("scale")!!, .001)
        press(rule, Key.DirectionDown, Key.DirectionRight); advance(rule, 200)
        assertEquals(1.1, style.dbl("speed")!!, .001)
        press(rule, Key.DirectionDown, Key.Enter); advance(rule, 200)
        rule.onNodeWithText("粗体").assertIsFocused()
        for (key in listOf("bold", "merge", "prevent_overlap", "heatmap")) {
            val expected = !style.bool(key)
            press(rule, Key.Enter); advance(rule, 200)
            assertEquals(expected, style.bool(key))
            if (key != "heatmap") press(rule, Key.DirectionDown)
        }
        rule.onRoot().captureRoboImage("build/player-osd/danmaku-advanced-tv.png")
        pressBack()
        rule.onNodeWithText("更多设置").assertIsFocused()
        rule.runOnIdle { DanmakuStyle.enabled.value = true }
        pressBack(); focused("osd.dm")
        advance(rule, 300)
        assertTrue("热力图开关未开启", DanmakuStyle.heatmap.value)
        assertTrue("语料布局尚未到达", DanmakuStyle.layout.value != null)
        assertTrue("没有请求热力图", core.calls.any { it.first == "player.danmakuHeatmap" })
        rule.onNodeWithContentDescription("弹幕热力图").assertExists()
        press(rule, Key.Enter); advance(rule, 200)
        rule.onNodeWithText("更多设置").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter); advance(rule, 200)
        rule.onNodeWithText("粗体").assertIsFocused()
        press(rule, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.Enter); advance(rule, 200)
        pressBack()
        pressBack(); focused("osd.dm")
        rule.onNodeWithContentDescription("弹幕热力图").assertDoesNotExist()
    }

    @Test fun 弹幕慢保存时连续按键逐次累加() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var hold = true
        var style = args("area" to 1.0, "scale" to 1.0, "opacity" to 1.0, "speed" to 1.0)
        DanmakuStyle.loaded.value = false
        DanmakuStyle.layout.value = null
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv"), intercept = { fake ->
            object : xyz.linplayer.app.core.CorePort by fake {
                override suspend fun callJson(command: String, args: kotlinx.serialization.json.JsonObject?,
                    onPartial: ((kotlinx.serialization.json.JsonElement) -> Unit)?): kotlinx.serialization.json.JsonElement {
                    if (command == "player.setDanmakuStyle" && hold) gate.await()
                    return fake.callJson(command, args, onPartial)
                }
            }
        }) {
            player()
            ret("player.getDanmakuStyle", style)
            ret("prefs.getPrefs", args("danmaku_enabled" to false))
            ret("player.danmakuLayout", kotlinx.serialization.json.JsonNull)
            on("player.setDanmakuStyle") { a -> kotlinx.serialization.json.JsonObject(style + a.orEmpty()).also { style = it } }
        }
        try {
            press(rule, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight, Key.Enter); advance(rule, 400)
            rule.onNodeWithText("显示区域").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.Enter, Key.Enter, Key.DirectionDown, Key.DirectionLeft, Key.DirectionLeft)
            assertFalse(core.calls.any { it.first == "player.setDanmakuStyle" })
            assertEquals("慢保存不得阻塞新的步进回显", .5, DanmakuStyle.area.doubleValue, .001)
            assertEquals(.8, DanmakuStyle.opacity.doubleValue, .001)
            repeat(5) { press(rule, Key.DirectionLeft) }
            assertEquals(.3, DanmakuStyle.opacity.doubleValue, .001)
            pressBack(); focused("osd.dm")
            rule.runOnIdle { hold = false; gate.complete(Unit) }; advance(rule, 800)
            assertEquals(.5, style.dbl("area")!!, .001)
            assertEquals(.3, style.dbl("opacity")!!, .001)
            assertTrue("在途之后只提交合并的最终值", core.calls.count { it.first == "player.setDanmakuStyle" } <= 2)
        } finally { gate.complete(Unit) }
    }

    @Test fun 播放页OSD收起后方向键只唤醒不移动焦点() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        focused("osd.bar")
        advance(rule, 5600)
        rule.onNodeWithContentDescription("字幕").assertDoesNotExist()
        focused("player.root")
        press(rule, Key.DirectionRight)
        rule.onNodeWithContentDescription("字幕").assertExists()
        // 唤醒不执行 seek，也不进入下一集按钮。
        focused("osd.bar")
    }

    @Test fun 分集加载失败不能误判季末退出且可以重试连播() {
        var fail = true
        mount(TvRoute.Player("sh6", "第六集")) {
            player()
            val response = handlers.getValue("emby.playbackSeason")
            on("emby.playbackSeason") { a ->
                if (fail) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分集加载失败", true)
                response(a)
            }
        }
        core.tick(3658.0); advance(rule, 300)
        core.tick(3658.25); advance(rule, 300)
        core.tick(3660.0, eof = true); advance(rule, 600)
        assertTrue("加载失败不能退出播放页", nav.top.route is TvRoute.Player)
        rule.onNode(hasText("无法加载下一集", substring = true)).assertExists()
        fail = false
        rule.onNode(hasText("重试")).assertIsFocused()
        press(rule, Key.Enter)
        advance(rule, 1200)
        assertTrue(core.calls.count { it.first == "emby.playbackSeason" } >= 2)
        assertEquals("sh7", lastPlayed())
    }

    private fun lastPlayed() = (core.calls.last { it.first == "player.play" }.second?.get("item_id") as kotlinx.serialization.json.JsonPrimitive).content

    @Test fun 播完直接连续播放三集() {
        mount(TvRoute.Player("sh6", "第六集")) {
            player(); ret("player.stopPlayback", kotlinx.serialization.json.JsonNull)
        }
        for (next in listOf("sh7", "sh8")) {
            core.tick(3658.0); advance(rule, 300)
            core.tick(3658.25); advance(rule, 300)
            core.tick(3660.0, eof = true); advance(rule, 1200)
            assertEquals(next, lastPlayed())
            assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })
        }
        assertEquals(3, core.calls.count { it.first == "player.play" })
    }

    @Test fun 完播等待慢分集请求返回后才切下一集() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            mount(TvRoute.Player("sh6", "第六集"), intercept = { fake ->
                object : xyz.linplayer.app.core.CorePort by fake {
                    override suspend fun callJson(command: String, args: kotlinx.serialization.json.JsonObject?,
                        onPartial: ((kotlinx.serialization.json.JsonElement) -> Unit)?): kotlinx.serialization.json.JsonElement {
                        val result = fake.callJson(command, args, onPartial)
                        if (command == "emby.playbackSeason") release.await()
                        return result
                    }
                }
            }) { player() }
            core.tick(3658.0); advance(rule, 300)
            core.tick(3658.25); advance(rule, 300)
            core.tick(3660.0, eof = true); advance(rule, 600)
            assertTrue(nav.top.route is TvRoute.Player)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 1200)
            assertEquals("sh7", lastPlayed())
        } finally { release.complete(Unit) }
    }

    @Test fun 清除本集标记后恢复的区间仍可自动跳过() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            ret("player.chapterInfo", args("intro" to args("start" to 0.0, "end" to 100.0), "skip_auto" to true))
            ret("player.getSkipRange", args("scope" to "episode", "can_season" to true))
            on("player.setSkipRange") {
                ret("player.chapterInfo", args("intro" to args("start" to 200.0, "end" to 300.0), "skip_auto" to true))
                args("scope" to "episode", "can_season" to true)
            }
        }
        core.tick(10.0); advance(rule, 150)
        core.tick(10.25); advance(rule, 150)
        assertTrue(core.calls.any { it.first == "player.seek" && it.second.dbl("pos") == 100.0 })
        core.tick(150.0); advance(rule, 150)
        press(rule, Key.Menu)
        rule.onNodeWithText("片头片尾").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.Enter); advance(rule, 300)
        rule.onNodeWithText("清除本集标记").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.Enter); advance(rule, 300)
        pressBack(); pressBack()
        core.tick(210.0); advance(rule, 300)
        assertTrue(core.calls.any { it.first == "player.seek" && it.second.dbl("pos") == 300.0 })
    }

    @Test fun 设置片尾后不自动跳转且焦点留在当前操作() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            ret("player.chapterInfo", args("skip_auto" to true))
            ret("player.getSkipRange", args("scope" to "season", "can_season" to true))
            on("player.setSkipRange") { a ->
                val outro = args("start" to a.dbl("position")!!, "end" to 3660.0)
                ret("player.chapterInfo", args("outro" to outro, "skip_auto" to true))
                args("scope" to "season", "can_season" to true, "outro" to outro)
            }
        }
        core.tick(3400.0); advance(rule, 150)
        core.tick(3400.25); advance(rule, 150)
        press(rule, Key.Menu)
        rule.onNodeWithText("片头片尾").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.Enter); advance(rule, 300)
        rule.onNodeWithText("当前位置设为片尾开始").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.Enter); advance(rule, 300)
        rule.onNodeWithText("当前位置设为片尾开始").assertIsFocused()
        assertEquals("outro_start", core.calls.last { it.first == "player.setSkipRange" }.second.str("action"))
        pressBack(); pressBack(); advance(rule, 300)
        assertFalse(core.calls.any { it.first == "player.seek" })
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 有后续内容的片尾半隐确认先展开再跳到区间末尾() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            ret("player.chapterInfo", args("outro" to args("start" to 3500.0, "end" to 3650.0), "skip_auto" to false))
        }
        core.tick(3640.0); advance(rule, 150)
        core.tick(3640.25); advance(rule, 150)
        rule.onNodeWithText("跳过片尾").assertExists()
        rule.onNodeWithTag("osd.next").assertExists()
        pressBack()
        press(rule, Key.Enter)
        rule.onNodeWithText("跳过片尾").assertIsFocused()
        rule.onNodeWithTag("osd.next").assertExists()
        assertFalse(core.calls.any { it.first == "player.seek" })
        assertEquals(1, core.calls.count { it.first == "player.play" })
        press(rule, Key.Enter); advance(rule, 300)
        assertEquals(3650.0, core.calls.last { it.first == "player.seek" }.second.dbl("pos")!!, .001)
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 标记到结尾的片尾提前出现下一集() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            ret("player.chapterInfo", args("outro" to args("start" to 3400.0, "end" to 3660.0), "skip_auto" to false))
        }
        core.tick(3401.0); advance(rule, 150)
        core.tick(3401.25); advance(rule, 150)
        rule.onNodeWithTag("player.nextUp").assertExists()
        rule.onNodeWithTag("osd.next").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 下一集卡替代已聚焦按钮且回退进度后恢复按钮() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 150)
        core.tick(1200.25); advance(rule, 150)
        press(rule, Key.DirectionUp); focused("osd.next")
        core.tick(3645.0); advance(rule, 300)
        rule.onNodeWithTag("osd.next").assertDoesNotExist()
        focused("player.nextUp")
        press(rule, Key.DirectionDown); focused("osd.bar")
        press(rule, Key.DirectionUp); focused("osd.speed")
        press(rule, Key.DirectionUp); focused("player.nextUp")
        core.tick(1200.0); advance(rule, 300)
        rule.onNodeWithTag("player.nextUp").assertDoesNotExist()
        focused("osd.bar")
        press(rule, Key.DirectionUp); focused("osd.next")
        press(rule, Key.Enter); advance(rule, 800)
        assertEquals("sh7", lastPlayed())
    }

    @Test fun 下一集卡出现消失不抢其它按钮焦点() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 150)
        core.tick(1200.25); advance(rule, 150)
        press(rule, Key.DirectionUp, Key.DirectionRight); focused("osd.speed")
        core.tick(3645.0); advance(rule, 300)
        focused("osd.speed")
        rule.onNodeWithTag("osd.next").assertDoesNotExist()
        press(rule, Key.DirectionRight); focused("osd.dm")
        core.tick(1200.0); advance(rule, 300)
        focused("osd.dm")
        rule.onNodeWithTag("osd.next").assertExists()
        press(rule, Key.DirectionLeft, Key.DirectionLeft); focused("osd.next")
    }

    @Test fun 下一集卡在菜单期间变化仍能恢复可用焦点() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(1200.0); advance(rule, 150)
        core.tick(1200.25); advance(rule, 150)
        press(rule, Key.DirectionUp); focused("osd.next")
        press(rule, Key.Menu); advance(rule, 300)
        core.tick(3645.0); advance(rule, 300)
        pressBack(); focused("player.nextUp")
        rule.onNodeWithTag("osd.next").assertDoesNotExist()
        press(rule, Key.Menu); advance(rule, 300)
        core.tick(1200.0); advance(rule, 300)
        pressBack(); focused("osd.bar")
        press(rule, Key.DirectionUp); focused("osd.next")
    }

    @Test fun 更多自动跳过开关文案明确且沿用原偏好字段() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) {
            player()
            ret("player.getPlaybackPrefs", args("skip_intro" to false, "skip_outro" to true))
            ret("player.setPlaybackPrefs", kotlinx.serialization.json.JsonNull)
        }
        press(rule, Key.Menu); advance(rule, 300)
        rule.onNodeWithText("跳过片头").assertDoesNotExist()
        rule.onNodeWithText("跳过片尾").assertDoesNotExist()
        rule.onNodeWithText("自动跳过片头").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.Enter); advance(rule, 200)
        assertEquals(true, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.boolOrNull("skip_intro"))
        press(rule, Key.DirectionDown)
        rule.onNodeWithText("自动跳过片尾").assertIsFocused()
        press(rule, Key.Enter); advance(rule, 200)
        assertEquals(false, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.boolOrNull("skip_outro"))
    }

    @Test fun 片尾半隐下一集确认先展开再切集() {
        mount(TvRoute.Player("sh6", "第六集")) { player() }
        core.tick(3645.0); advance(rule, 300)
        core.tick(3645.25); advance(rule, 300)
        advance(rule, 5600)
        focused("player.root")
        rule.onNode(hasTestTag("player.nextUp")).assertExists()
        assertEquals(1, core.calls.count { it.first == "player.play" })
        press(rule, Key.Enter)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.setPause" })
        advance(rule, 300)
        rule.onNodeWithTag("player.nextUp").assertIsFocused()
        press(rule, Key.Enter)
        advance(rule, 600)
        assertEquals("sh7", lastPlayed())
    }

    @Test fun 片尾半隐下一集上下键唤醒后仍聚焦下一集() {
        mount(TvRoute.Player("sh6", "第六集")) { player() }
        core.tick(3645.0); advance(rule, 300)
        core.tick(3645.25); advance(rule, 300)
        advance(rule, 5600)
        focused("player.root")
        press(rule, Key.DirectionDown)
        advance(rule, 300)
        rule.onNodeWithTag("player.nextUp").assertIsFocused()
        pressBack()
        focused("player.root")
        press(rule, Key.DirectionUp)
        advance(rule, 300)
        rule.onNodeWithTag("player.nextUp").assertIsFocused()
    }

    @Test fun 片尾半隐下一集快速连续确认仍直接切集() {
        mount(TvRoute.Player("sh6", "第六集")) { player() }
        core.tick(3645.0); advance(rule, 300)
        core.tick(3645.25); advance(rule, 300)
        advance(rule, 5600)
        focused("player.root")
        rule.onRoot().performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.Enter)
        }
        advance(rule, 800)
        assertEquals("sh7", lastPlayed())
        assertFalse(core.calls.any { it.first == "player.setPause" })
    }

    @Test fun 跳过提示不接管隐藏确认且消失后返回进度焦点() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        core.tick(10.0); advance(rule, 100)
        core.tick(10.25); advance(rule, 100)
        pressBack(); focused("player.root")
        press(rule, Key.Enter)
        assertFalse(core.calls.any { it.first == "player.seek" })
        press(rule, Key.DirectionUp, Key.DirectionUp, Key.DirectionUp)
        focused("osd.skip")
        press(rule, Key.Enter); advance(rule, 200)
        assertEquals(220.0, core.calls.single { it.first == "player.seek" }.second.dbl("pos")!!, .001)
        focused("osd.bar")
    }

    @Test fun 未知时长仍可暂停和访问工具且时长晚到不抢焦点() {
        mount(TvRoute.Player("sh6", "第六集", engine = "mpv")) { player() }
        focused("player.root")
        press(rule, Key.Enter)
        assertTrue("没有暂停调用：${core.calls.map { it.first }}", core.calls.any { it.first == "player.setPause" })
        press(rule, Key.DirectionUp); focused("osd.next")
        press(rule, Key.DirectionDown); focused("player.root")
        press(rule, Key.DirectionUp, Key.DirectionRight); focused("osd.speed")
        core.tick(1200.0); advance(rule, 200)
        focused("osd.speed")
        press(rule, Key.DirectionDown); focused("osd.bar")
    }

    @Test fun 核心返回当前季末时退出而不跨季() {
        mount(TvRoute.Player("sh6", "第六集")) {
            player()
            ret("emby.playbackSeason", arr(item("sh6", "季末", "Episode", season = 1, episode = 6)))
        }
        core.tick(3658.0); advance(rule, 300)
        core.tick(3658.25); advance(rule, 300)
        core.tick(3660.0, eof = true); advance(rule, 600)
        assertFalse(nav.top.route is TvRoute.Player)
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 自动内核发送实际Media3且设置变化不重启当前片() {
        val previous = xyz.linplayer.app.data.UiPrefs.engine.value
        try {
            xyz.linplayer.app.data.UiPrefs.engine.value = "auto"
            mount(TvRoute.Player("sh6", "测试")) { player() }
            val plays = core.calls.filter { it.first == "player.play" }
            assertEquals("\"exo\"", plays.single().second?.get("engine").toString())
            rule.runOnIdle { xyz.linplayer.app.data.UiPrefs.engine.value = "mpv" }
            advance(rule, 600)
            assertEquals(1, core.calls.count { it.first == "player.play" })
        } finally { xyz.linplayer.app.data.UiPrefs.engine.value = previous }
    }

    @Test fun 自动偏好下显式手动内核仍优先() {
        val previous = xyz.linplayer.app.data.UiPrefs.engine.value
        try {
            xyz.linplayer.app.data.UiPrefs.engine.value = "auto"
            mount(TvRoute.Player("sh6", "测试", engine = "mpv")) { player() }
            assertEquals("\"mpv\"", core.calls.single { it.first == "player.play" }.second?.get("engine").toString())
        } finally { xyz.linplayer.app.data.UiPrefs.engine.value = previous }
    }

    @Test fun 播放页OSD收起时菜单键和媒体键都有反应() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        advance(rule, 5600)
        rule.onNodeWithContentDescription("字幕").assertDoesNotExist()
        press(rule, Key.MediaPlayPause)
        assertTrue("播放/暂停媒体键没发到核心层", core.calls.any { it.first == "player.setPause" })
        rule.onNodeWithContentDescription("字幕").assertDoesNotExist()
        press(rule, Key.Menu)
        rule.onNode(hasText("画面比例")).assertExists()
    }

    @Test fun 播放页返回键先收OSD再按两次才退出() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        pressBack()
        assertEquals("OSD 显示时返回 = 收起 OSD,不退页", 2, nav.stack.size)
        pressBack()
        assertEquals("第一次返回只唤出 OSD 并提示", 2, nav.stack.size)
        pressBack()
        assertEquals("2 秒内再按才退出", 1, nav.stack.size)
    }
}
