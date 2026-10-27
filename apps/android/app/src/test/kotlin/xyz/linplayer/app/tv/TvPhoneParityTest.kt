package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvPhoneParityTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    @After fun clean() { scope.cancel(); if (::app.isInitialized) app.bg.cancel() }

    private fun core() = FakeCore().loggedIn().player().apply {
        ret("prefs.getPrefs", buildJsonObject { put("default_speed", 1.5); put("sub_enabled", true) })
        on("player.play") { buildJsonObject {
            put("media_source_id", "ea"); put("play_url", "asset:///startup-measurement.mp4"); put("resume_secs", 0.0)
        } }
        for (command in listOf("player.setSpeed", "player.setVolume", "player.setPause", "player.setTrack",
            "player.setAspectRatio", "player.stopPlayback", "player.setPlaybackPrefs")) ret(command, JsonNull)
    }

    private fun show(core: xyz.linplayer.app.core.CorePort, engine: String?) {
        app = AppState(core, scope)
        runBlocking { app.boot() }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val nav = TvNav().apply { push(TvRoute.Player("sh6", "测试剧集", engine = engine)) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { PlayerHost(nav) } }
        advance(rule, 1500)
    }

    private fun click(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter)
        advance(rule, 400)
    }

    private fun switchEngine() {
        click("更多")
        rule.onNodeWithText("切换到", substring = true).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter)
        advance(rule, 400)
        advance(rule, 1000)
    }

    @Test fun 手动双向切核保留暂停倍率和精确续播() {
        val core = core().apply {
            ret("player.status", buildJsonObject {
                put("position", 42.5); put("duration", 3660); put("paused", true); put("speed", 1.75); put("volume", 40)
            })
        }
        show(core, "mpv")
        switchEngine()
        val second = core.calls.last { it.first == "player.play" }.second
        assertEquals("exo", second.str("engine"))
        assertTrue(second.bool("resume_exact"))
        assertEquals(42.5, second.dbl("resume_secs")!!, .001)
        assertEquals("ea", second.str("media_source_id"))
        switchEngine()
        assertEquals(1.75, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        assertTrue(core.calls.last { it.first == "player.setPause" }.second.bool("paused"))
        assertEquals(listOf("player.play", "player.stopPlayback", "player.play", "player.stopPlayback", "player.play"),
            core.calls.map { it.first }.filter { it in listOf("player.play", "player.stopPlayback") })
    }

    @Test fun Media3默认倍率进入真实内核且显式修改记忆() {
        val core = core()
        show(core, "exo")
        switchEngine()
        assertEquals(1.5, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        click("更多"); click("倍速"); click("2.0×")
        assertEquals(2.0, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.dbl("default_speed")!!, .001)
        assertNull(core.calls.last { it.first == "player.setPlaybackPrefs" }.second?.get("liuying_speed"))
    }

    @Test fun 显式Media3跨上下集保持且不读取新全局偏好() {
        val previous = UiPrefs.engine.value
        try {
            UiPrefs.engine.value = "mpv"
            val core = core()
            show(core, "exo")
            press(rule, Key.MediaNext)
            advance(rule, 1000)
            assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals("exo", core.calls.last { it.first == "player.play" }.second.str("engine"))
            press(rule, Key.MediaPrevious)
            advance(rule, 1000)
            assertEquals("sh6", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals("exo", core.calls.last { it.first == "player.play" }.second.str("engine"))
        } finally { UiPrefs.engine.value = previous }
    }

    @Test fun 显式MPV自动连播不回全局Media3() {
        val previous = UiPrefs.engine.value
        try {
            UiPrefs.engine.value = "exo"
            val core = core()
            show(core, "mpv")
            core.tick(3658.0); advance(rule, 300)
            core.tick(3658.25); advance(rule, 300)
            core.tick(3660.0, eof = true); advance(rule, 1200)
            assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals("mpv", core.calls.last { it.first == "player.play" }.second.str("engine"))
        } finally { UiPrefs.engine.value = previous }
    }

    @Test fun 自动模式跨切集仍由控制器解析为Media3() {
        val previous = UiPrefs.engine.value
        try {
            UiPrefs.engine.value = "auto"
            val core = core()
            show(core, null)
            rule.runOnIdle { UiPrefs.engine.value = "mpv" }
            press(rule, Key.MediaNext)
            advance(rule, 1000)
            assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals("exo", core.calls.last { it.first == "player.play" }.second.str("engine"))
        } finally { UiPrefs.engine.value = previous }
    }

    @Test fun 偏好晚于详情返回不会清空分集标题() {
        val core = core()
        val release = CompletableDeferred<Unit>()
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "prefs.getPrefs") release.await()
                return result
            }
        }
        try {
            show(port, "mpv")
            rule.onNodeWithText("S1E6：妾之王国").assertExists()
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 1000)
            rule.onNodeWithText("幕府将军").assertExists()
            rule.onNodeWithText("S1E6：妾之王国").assertExists()
            assertEquals(1, core.calls.count { it.first == "emby.itemDetail" })
        } finally { release.complete(Unit) }
    }

    @Test fun 同条目手动切核保留分集标题() {
        val core = core()
        show(core, "mpv")
        rule.onNodeWithText("S1E6：妾之王国").assertExists()
        switchEngine()
        rule.onNodeWithText("幕府将军").assertExists()
        rule.onNodeWithText("S1E6：妾之王国").assertExists()
        assertEquals(1, core.calls.count { it.first == "emby.itemDetail" })
    }

    @Test fun 同条目起播失败重试保留分集标题() {
        var fail = true
        val core = core().apply {
            val play = handlers.getValue("player.play")
            on("player.play") { a ->
                if (fail) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "合成起播失败", false)
                play(a)
            }
        }
        show(core, "mpv")
        fail = false
        click("重试")
        rule.onNodeWithText("幕府将军").assertExists()
        rule.onNodeWithText("S1E6：妾之王国").assertExists()
        assertEquals(1, core.calls.count { it.first == "emby.itemDetail" })
    }

    @Test fun 换集等待新详情时不残留旧分集标题() {
        val release = CompletableDeferred<Unit>()
        val core = core().apply {
            on("emby.itemDetail") { a -> buildJsonObject {
                val next = a.str("item_id") == "sh7"
                put("id", if (next) "sh7" else "sh6")
                put("type_", "Episode"); put("series_id", "s2"); put("series_name", "幕府将军")
                put("season_id", "sh"); put("season_no", 1); put("episode_no", if (next) 7 else 6)
                put("name", if (next) "今日之时" else "妾之王国")
            } }
        }
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "emby.itemDetail" && args.str("item_id") == "sh7") release.await()
                return result
            }
        }
        try {
            show(port, "mpv")
            rule.onNodeWithText("S1E6：妾之王国").assertExists()
            press(rule, Key.MediaNext)
            advance(rule, 600)
            rule.onNodeWithText("S1E6：妾之王国").assertDoesNotExist()
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 600)
            rule.onNodeWithText("S1E7：今日之时").assertExists()
        } finally { release.complete(Unit) }
    }

    private fun actualVersionSpecWithDelayed(commandToDelay: String) {
        val release = CompletableDeferred<Unit>()
        val core = core().apply {
            ret("player.play", buildJsonObject { put("media_source_id", "eb") })
        }
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == commandToDelay) release.await()
                return result
            }
        }
        try {
            show(port, "mpv")
            rule.onNodeWithText("2160p", substring = true).assertDoesNotExist()
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 1000)
            rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists()
            rule.onNodeWithText("2160p", substring = true).assertDoesNotExist()
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertEquals(1, core.calls.count { it.first == "emby.itemMedia" })
        } finally { release.complete(Unit) }
    }

    @Test fun 起播实际版本晚到时规格不会冒认第一版() =
        actualVersionSpecWithDelayed("player.play")

    @Test fun 版本资料晚到时规格仍匹配实际版本() =
        actualVersionSpecWithDelayed("emby.itemMedia")

    @Test fun 未知实际版本不显示第一版本规格() {
        val core = core().apply {
            ret("player.play", buildJsonObject { put("media_source_id", "missing") })
        }
        show(core, "mpv")
        rule.onNodeWithText("2160p", substring = true).assertDoesNotExist()
        rule.onNodeWithText("1080p", substring = true).assertDoesNotExist()
    }

    @Test fun 换集不保留上一集版本规格() {
        val release = CompletableDeferred<Unit>()
        val core = core().apply {
            ret("player.play", buildJsonObject { put("media_source_id", "eb") })
            val media = handlers.getValue("emby.itemMedia")
            on("emby.itemMedia") { a ->
                val versions = media(a).jsonArray
                if (a.str("item_id") == "sh7") JsonArray(versions.filter { it.obj().str("id") == "ea" }) else versions
            }
        }
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "emby.itemMedia" && args.str("item_id") == "sh7") release.await()
                return result
            }
        }
        try {
            show(port, "mpv")
            rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists()
            press(rule, Key.MediaNext)
            advance(rule, 600)
            rule.onNodeWithText("1080p", substring = true).assertDoesNotExist()
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 600)
            rule.onNodeWithText("1080p", substring = true).assertDoesNotExist()
            rule.onNodeWithText("2160p", substring = true).assertDoesNotExist()
        } finally { release.complete(Unit) }
    }

    private fun lateOldResultDoesNotReplaceNewSpec(commandToDelay: String) {
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val core = core().apply {
            on("player.play") { a -> buildJsonObject { put("media_source_id", if (a.str("item_id") == "sh7") "eb" else "ea") } }
            val media = handlers.getValue("emby.itemMedia")
            on("emby.itemMedia") { a ->
                JsonArray(media(a).jsonArray.filter { it.obj().str("id") == if (a.str("item_id") == "sh7") "eb" else "ea" })
            }
        }
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == commandToDelay && args.str("item_id") == "sh6") {
                    withContext(NonCancellable) { release.await() }
                    returned.complete(Unit)
                }
                return result
            }
        }
        try {
            show(port, "mpv")
            press(rule, Key.MediaNext)
            advance(rule, 600)
            rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists()
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 600)
            assertTrue("旧响应已完成才检查当前规格", returned.isCompleted)
            rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists()
            rule.onNodeWithText("2160p", substring = true).assertDoesNotExist()
        } finally { release.complete(Unit) }
    }

    @Test fun 换集后迟到的起播结果不改写当前规格() =
        lateOldResultDoesNotReplaceNewSpec("player.play")

    @Test fun 换集后迟到的版本资料不改写当前规格() =
        lateOldResultDoesNotReplaceNewSpec("emby.itemMedia")
}
