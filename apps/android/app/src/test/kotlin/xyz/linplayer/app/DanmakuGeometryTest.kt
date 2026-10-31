package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
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
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.pages.args as jsonArgs
import xyz.linplayer.app.ui.player.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h640dp-mdpi", sdk = [36], application = Application::class)
class DanmakuGeometryTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel() }

    private fun prepared(revision: Long) = DmLayout(1920.0, 1080.0, 54.0, 8.0, 5.0, 40.0, 1.0, false,
        listOf(DmItem(0.0, 5, 0, 0.0, 0xFFFFFF, "实测字宽 ×2")), revision = revision, needsMeasurement = true)

    @Test fun firstBatchDrawsBeforeFutureMeasurementsComplete() {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val requests = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val position = mutableStateOf(1.0)
        val fake = FakeCore().loggedIn()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "player.danmakuLayout") return fake.callJson(command, args, onPartial)
                assertNotEquals("大批参数必须在后台进入核心序列化", android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                requests.add(args!!)
                if (args.dbl("until") == null) { entered.complete(Unit); release.await() }
                val at = if ((args.dbl("until") ?: 0.0) > 100.0) 100.0 else 0.0
                return jsonArgs("revision" to 1, "items" to JsonArray(listOf(
                    jsonArgs("t" to at, "m" to 5, "c" to 0x00FF00, "u" to "首批弹幕"))))
            }
        }
        val app = AppState(port, scope)
        val layout = DmLayout(1920.0, 1080.0, 54.0, 8.0, 5.0, 40.0, 1.0, false,
            listOf(DmItem(0.0, 5, 0, 0.0, 0xFFFFFF, "首批弹幕"),
                DmItem(100.0, 1, 0, 0.0, 0xFFFFFF, "后续弹幕"),
                DmItem(400.0, 1, 0, 0.0, 0xFFFFFF, "片尾弹幕")), revision = 1, needsMeasurement = true)
        try {
            rule.setContent { DanmakuLayer(layout, position.value, true, 1.0,
                Modifier.width(640.dp).height(360.dp).background(Color.Black).testTag("dm"), app = app) }
            rule.waitUntil(5000) { entered.isCompleted }; rule.waitForIdle()
            assertEquals(31.0, requests.first().dbl("until")!!, 0.0)
            assertEquals(listOf(1, 3), requests.map { it["widths"].arr().size })
            val pixels = rule.onNodeWithTag("dm").captureToImage().toPixelMap()
            assertTrue((0 until pixels.width).any { x -> (0 until 80).any { y -> pixels[x, y].green > .5f } })
            rule.runOnIdle { position.value = 101.0 }
            rule.waitForIdle()
            rule.waitUntil(5000) { requests.any { it.dbl("until") == 131.0 } }; rule.waitForIdle()
            rule.waitUntil(5000) {
                val afterSeek = rule.onNodeWithTag("dm").captureToImage().toPixelMap()
                (0 until afterSeek.width).any { x -> (0 until 80).any { y -> afterSeek[x, y].green > .5f } }
            }
            assertFalse("跳转后首批不能等整集释放", release.isCompleted)
        } finally { release.complete(Unit); app.bg.cancel() }
    }

    @Test fun idleDecisionIncludesLongLivedItemsAndWakesBeforeNextEntry() {
        val layout = DmLayout(1920.0, 1080.0, 54.0, 80.0, 5.0, 40.0, 1.0, false,
            listOf(DmItem(0.0, 1, 0, 100.0, 0xFFFFFF, "慢速"),
                DmItem(90.0, 5, 0, 100.0, 0xFFFFFF, "固定"),
                DmItem(120.0, 1, 0, 100.0, 0xFFFFFF, "下一条")))
        assertTrue(dmHasActivity(layout, 79.0, .35))
        assertFalse(dmHasActivity(layout, 81.0, .35))
        assertTrue(dmHasActivity(layout, 89.8, .35))
        assertFalse(dmHasActivity(layout, 96.0, .35))
        assertTrue(dmHasActivity(layout, 119.0, .35 * 3.25))
        assertFalse(dmHasActivity(layout, 201.0, .35))
        assertTrue("倒退跳转应唤醒", dmHasActivity(layout, 1.0, .35))
    }

    @Test fun opacityUpdatesWhileSavingWithoutClearingOrMeasuringAgain() {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var stored = jsonArgs("area" to 1.0, "scale" to 1.0, "opacity" to .8, "speed" to 1.0,
            "bold" to false, "merge" to false, "prevent_overlap" to true, "heatmap" to false)
        var fail = false
        var layouts = 0
        val writes = mutableListOf<JsonObject>()
        val fake = FakeCore().loggedIn().apply {
            on("player.getDanmakuStyle") { stored }
            ret("prefs.getPrefs", jsonArgs("danmaku_enabled" to true))
        }
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.danmakuLayout") {
                    layouts++
                    return jsonArgs("revision" to 1, "needs_measurement" to args.bool("prepare"), "opacity" to .8,
                        "items" to JsonArray(listOf(jsonArgs("t" to 0.0, "m" to 5, "u" to "即时透明度"))))
                }
                if (command == "player.setDanmakuStyle") {
                    writes.add(args!!)
                    if (writes.size == 1) { entered.complete(Unit); release.await(); error("旧保存失败") }
                    if (fail) error("保存失败")
                    stored = JsonObject(stored + args)
                    return stored
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        val app = AppState(port, scope)
        lateinit var edits: CoroutineScope
        fun brightest(): Float {
            val p = rule.onNodeWithTag("dm").captureToImage().toPixelMap()
            return (0 until 80).maxOf { y -> (0 until p.width).maxOf { x -> p[x, y].red } }
        }
        try {
            DanmakuStyle.layout.value = null
            rule.setContent {
                edits = androidx.compose.runtime.rememberCoroutineScope()
                androidx.compose.runtime.LaunchedEffect(Unit) { DanmakuStyle.load(app) }
                DanmakuLayer(DanmakuStyle.layout.value, 1.0, true, 1.0,
                    Modifier.width(640.dp).height(360.dp).background(Color.Black).testTag("dm"),
                    app = app, opacity = DanmakuStyle.opacity.doubleValue)
            }
            rule.waitUntil(5000) { layouts == 2 }; rule.waitForIdle()
            rule.waitUntil(5000) { brightest() > .7f }
            val original = DanmakuStyle.layout.value
            rule.runOnIdle { repeat(5) { edits.launch {
                DanmakuStyle.set(app, "opacity") { DanmakuStyle.step(DanmakuStyle.opacity.doubleValue, false, .1, .1, 1.0) }
            } } }
            rule.waitUntil(5000) { entered.isCompleted && DanmakuStyle.opacity.doubleValue == .3 }
            assertSame(original, DanmakuStyle.layout.value)
            assertEquals(.3f, brightest(), .02f)
            assertEquals(2, layouts)
            rule.runOnIdle { release.complete(Unit) }
            rule.waitUntil(5000) { stored.dbl("opacity") == .3 }; rule.waitForIdle()
            assertEquals(listOf(.7, .3), writes.map { it.dbl("opacity") })
            assertEquals(2, layouts)
            rule.runOnIdle { fail = true; edits.launch { DanmakuStyle.set(app, "opacity", .1) } }
            rule.waitUntil(5000) { writes.size == 3 && DanmakuStyle.opacity.doubleValue == .3 }
            assertEquals(.3f, brightest(), .02f)
            assertSame(original, DanmakuStyle.layout.value)
        } finally { release.complete(Unit); DanmakuStyle.layout.value = null; app.bg.cancel() }
    }

    @Test fun viewportAndSourceChangesDiscardLateMeasurements() {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val requests = mutableListOf<JsonObject>()
        val fake = FakeCore().loggedIn()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "player.danmakuLayout") return fake.callJson(command, args, onPartial)
                requests.add(args!!)
                if (requests.size == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                val red = args.long("revision") == 1L
                return jsonArgs("revision" to args.long("revision")!!, "items" to JsonArray(listOf(
                    jsonArgs("t" to 0.0, "m" to 5, "l" to 0, "w" to args["widths"].arr()[0],
                        "c" to if (red) 0xFF0000 else 0x00FF00, "u" to "实测字宽 ×2"))))
            }
        }
        val app = AppState(port, scope)
        val input = mutableStateOf(prepared(1))
        val height = mutableStateOf(360.dp)
        try {
            rule.setContent { DanmakuLayer(input.value, 1.0, true, 1.0,
                Modifier.width(640.dp).height(height.value).background(Color.Black).testTag("dm"), app = app) }
            rule.waitUntil(5000) { entered.isCompleted }
            rule.runOnIdle { input.value = prepared(2); height.value = 600.dp }
            rule.waitForIdle()
            rule.waitUntil(5000) { requests.size >= 2 }
            rule.waitForIdle()
            rule.runOnIdle { release.complete(Unit) }
            rule.waitForIdle()
            rule.waitUntil(5000) {
                val p = rule.onNodeWithTag("dm").captureToImage().toPixelMap()
                (0 until p.width).any { x -> (0 until 80).any { y -> p[x, y].green > .5f } }
            }
            val p = rule.onNodeWithTag("dm").captureToImage().toPixelMap()
            assertFalse((0 until p.width).any { x -> (0 until 80).any { y -> p[x, y].red > .5f } })
            assertTrue(requests.last().dbl("viewport_width")!! < requests.first().dbl("viewport_width")!!)
            assertTrue(requests.last()["widths"].arr()[0].jsonPrimitive.double > 100)
        } finally { release.complete(Unit) }
    }

    @Test fun prepareRepliesCannotRestorePreviousSession() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val fake = FakeCore().loggedIn()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "player.danmakuLayout") return fake.callJson(command, args, onPartial)
                entered.complete(Unit); release.await()
                return jsonArgs("revision" to 1, "needs_measurement" to true, "items" to JsonArray(emptyList()))
            }
        }
        val app = AppState(port, scope)
        DanmakuStyle.layout.value = prepared(9)
        val job = launch { DanmakuStyle.reloadLayout(app) }
        entered.await()
        assertNull("等待新prepare期间必须清旧布局", DanmakuStyle.layout.value)
        DanmakuSession.invalidate()
        release.complete(Unit); job.join()
        assertNull(DanmakuStyle.layout.value)
    }

    @Test fun failedStyleReloadKeepsPreviousLayoutButSuccessfulEmptyResultClearsIt() = runBlocking {
        var fail = true
        val fake = FakeCore().loggedIn().apply {
            on("player.danmakuLayout") {
                if (fail) error("布局准备失败")
                jsonArgs("revision" to 2, "needs_measurement" to true, "items" to JsonArray(emptyList()))
            }
        }
        val app = AppState(fake, scope)
        val previous = prepared(1)
        try {
            DanmakuStyle.layout.value = previous
            DanmakuStyle.reloadLayout(app, clear = false)
            assertSame("失败时不得清空已有弹幕", previous, DanmakuStyle.layout.value)
            fail = false
            DanmakuStyle.reloadLayout(app, clear = false)
            assertTrue(DanmakuStyle.layout.value!!.items.isEmpty())
        } finally { DanmakuStyle.layout.value = null; app.bg.cancel() }
    }

    @Test fun heatmapClearsOnDisableAndIgnoresLateReply() {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val fake = FakeCore().loggedIn()
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "player.danmakuHeatmap") return fake.callJson(command, args, onPartial)
                calls++
                if (calls == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                return JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(1.0)))
            }
        }
        val app = AppState(port, scope)
        val duration = mutableStateOf(100.0)
        DanmakuStyle.enabled.value = true
        DanmakuStyle.heatmap.value = true
        DanmakuStyle.layout.value = prepared(1)
        try {
            rule.setContent {
                val heat = rememberDanmakuHeatmap(app, duration.value)
                Box(androidx.compose.ui.Modifier.width(240.dp).height(40.dp).background(Color.Black)) {
                    DanmakuHeatmap(heat, Modifier.width(240.dp).height(10.dp))
                }
            }
            rule.waitUntil(5000) { entered.isCompleted }
            rule.runOnIdle { DanmakuStyle.heatmap.value = false }
            rule.waitForIdle()
            rule.runOnIdle { release.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("弹幕热力图").assertDoesNotExist()
            rule.runOnIdle { DanmakuStyle.heatmap.value = true }
            rule.waitForIdle(); rule.waitUntil(5000) { calls == 2 }; rule.waitForIdle()
            val pixels = rule.onNodeWithContentDescription("弹幕热力图").captureToImage().toPixelMap()
            assertTrue(pixels[180, 5].red > .3f && pixels[60, 5].red < .1f)
            rule.runOnIdle { duration.value = 0.0 }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("弹幕热力图").assertDoesNotExist()
            assertEquals(2, calls)
            rule.runOnIdle { duration.value = 50.0 }
            rule.waitForIdle(); rule.waitUntil(5000) { calls == 3 }; rule.waitForIdle()
            rule.onNodeWithContentDescription("弹幕热力图").assertExists()
            rule.runOnIdle { DanmakuStyle.layout.value = null }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("弹幕热力图").assertDoesNotExist()
        } finally { release.complete(Unit); DanmakuStyle.heatmap.value = false; DanmakuStyle.layout.value = null }
    }
}
