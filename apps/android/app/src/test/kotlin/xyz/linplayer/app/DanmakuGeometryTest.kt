package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
