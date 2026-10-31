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
    private lateinit var back: androidx.activity.OnBackPressedDispatcher
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
        rule.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvFrame(app) { PlayerHost(nav) }
        }
        advance(rule, 1500)
    }

    private fun click(text: String) {
        rule.onNode(if (text == "更多") hasContentDescription(text) else hasText(text)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter)
        advance(rule, 400)
    }

    private fun withMediaInfo(assertions: () -> Unit) {
        click("更多"); click("媒体信息")
        assertions()
        repeat(2) { rule.runOnUiThread { back.onBackPressed() }; advance(rule, 300) }
    }

    private fun switchEngine() {
        click("更多")
        rule.onNodeWithText("切换到", substring = true).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter)
        advance(rule, 400)
        advance(rule, 1000)
    }

    private fun fallbackDuringHold(releaseAt: String) {
        val core = core()
        val stopped = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restoring = CompletableDeferred<Unit>()
        val restoreRelease = CompletableDeferred<Unit>()
        val normalSpeed = 1.75
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.stopPlayback") { stopped.complete(Unit); release.await() }
                val result = core.callJson(command, args, onPartial)
                if (releaseAt == "restore" && command == "player.setSpeed" && args.dbl("speed") == normalSpeed) {
                    restoring.complete(Unit); restoreRelease.await()
                }
                return result
            }
        }
        try {
            show(port, "auto")
            val player = org.robolectric.util.ReflectionHelpers.getStaticField<androidx.media3.exoplayer.ExoPlayer>(
                xyz.linplayer.app.ui.player.PlaybackService::class.java, "externalPlayer")
            // 当前实际倍率与保存偏好不同，切核应保留前者。
            rule.runOnIdle { player.setPlaybackSpeed(normalSpeed.toFloat()) }
            advance(rule, 400)
            rule.runOnUiThread { back.onBackPressed() }; advance(rule, 300)
            rule.onRoot().performKeyInput { keyDown(Key.Enter) }
            advance(rule, 750)
            rule.runOnIdle {
                assertEquals(UiPrefs.tvHoldSpeed.value, player.playbackParameters.speed.toDouble(), .001)
                val info = org.robolectric.util.ReflectionHelpers.getField<Any>(player, "playbackInfo")
                val error = androidx.media3.exoplayer.ExoPlaybackException.createForSource(java.io.IOException("不支持解码"),
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                org.robolectric.util.ReflectionHelpers.setField(info, "playbackError", error)
            }
            advance(rule, 400)
            assertTrue("应进入自动切核的停止屏障", stopped.isCompleted)
            if (releaseAt == "stop") {
                rule.onRoot().performKeyInput { keyUp(Key.Enter) }
                advance(rule, 300)
            }
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 500)
            if (releaseAt == "restore") {
                assertTrue("新内核已应用正常倍率但回执仍未返回", restoring.isCompleted)
                rule.onRoot().performKeyInput { keyUp(Key.Enter) }
                advance(rule, 200)
                rule.runOnIdle { restoreRelease.complete(Unit) }
            }
            advance(rule, 1000)
            assertEquals("mpv", core.calls.last { it.first == "player.play" }.second.str("engine"))
            if (releaseAt == "ready") {
                assertEquals("仍按住时新内核继续临时倍速", UiPrefs.tvHoldSpeed.value,
                    core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
                rule.onRoot().performKeyInput { keyUp(Key.Enter) }
                advance(rule, 400)
            }
            assertEquals("松手后恢复切核前的正常倍率", normalSpeed,
                core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
            assertFalse(core.calls.any { it.first == "player.setPlaybackPrefs" })
        } finally { rule.runOnIdle { release.complete(Unit); restoreRelease.complete(Unit) } }
    }

    @Test fun 长按时自动切核中途松手恢复正常倍率() = fallbackDuringHold("stop")

    @Test fun 长按时自动切核完成再松手恢复正常倍率() = fallbackDuringHold("ready")

    @Test fun 长按时新内核恢复倍率回执缓慢松手不读旧偏好() = fallbackDuringHold("restore")

    @Test fun 媒体信息显示内核快照并能用方向键查看完整内容() {
        val core = core().apply { ret("player.opts", buildJsonObject {
            put("speed", "1.75"); put("frame-drop-count", "12"); put("decoder-frame-drop-count", "0")
            put("video-codec", "hevc"); put("dwidth", "3840"); put("dheight", "2160")
            put("container-fps", "23.976"); put("video-format", "yuv420p10"); put("hwdec-current", "mediacodec")
            put("current-vo", "gpu-next"); put("audio-codec-name", "aac")
            put("last_error", "private diagnostic"); put("path", "private playback address")
        }) }
        show(core, "mpv")
        withMediaInfo {
            rule.onNodeWithText("1.75×").assertIsDisplayed()
            rule.onNodeWithText("12 帧").assertIsDisplayed()
            rule.onNodeWithText("0 帧").assertIsDisplayed()
            repeat(16) { press(rule, Key.DirectionDown); advance(rule, 100) }
            rule.onNodeWithText("aac").assertIsDisplayed()
            repeat(16) { press(rule, Key.DirectionUp); advance(rule, 100) }
            rule.onNodeWithText("返回").assertIsDisplayed()
            rule.onNodeWithText("private diagnostic").assertDoesNotExist()
            rule.onNodeWithText("private playback address").assertDoesNotExist()
        }
    }

    @Test fun Media3媒体信息读取实际倍率且不查询MPV快照() {
        val core = core().apply { ret("player.opts", buildJsonObject {
            put("speed", "3.0"); put("frame-drop-count", "12"); put("decoder-frame-drop-count", "34")
        }) }
        show(core, "exo")
        val reads = core.calls.count { it.first == "player.opts" }
        withMediaInfo {
            rule.onNodeWithText("1.50×").assertIsDisplayed()
            rule.onNodeWithText("渲染丢帧").assertDoesNotExist()
            rule.onNodeWithText("解码丢帧").assertDoesNotExist()
        }
        assertEquals(reads, core.calls.count { it.first == "player.opts" })
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
        rule.onNodeWithContentDescription("倍速").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter); advance(rule, 400)
        click("2.0×")
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

    @Test fun 倍率保存缓慢时仍应用最后一次选择() {
        val core = core()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.setPlaybackPrefs" && args.dbl("default_speed") == 2.0) {
                    entered.complete(Unit); release.await()
                }
                return core.callJson(command, args, onPartial)
            }
        }
        fun choose(value: String) {
            rule.onNodeWithContentDescription("倍速").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.Enter); advance(rule, 400)
            click(value)
        }
        try {
            show(port, "mpv")
            choose("2.0×")
            assertTrue(entered.isCompleted)
            choose("3.0×")
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            assertEquals(3.0, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
            assertEquals(3.0, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.dbl("default_speed")!!, .001)
            rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status",
                buildJsonObject { put("item_id", "sh6"); put("position", 40.0); put("speed", 1.75) })) }
            advance(rule, 200)
            core.on("player.setPlaybackPrefs") { error("保存失败") }
            choose("2.5×")
            assertEquals(1.75, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
            rule.onNodeWithText("1.75×").assertIsDisplayed()
            core.on("player.setSpeed") { a -> if (a.dbl("speed") == 1.75) error("回滚失败") else JsonNull }
            choose("2.25×")
            rule.onNodeWithText("2.25×").assertIsDisplayed()
        } finally { release.complete(Unit) }
    }

    @Test fun MPV状态回读校准倍率且忽略旧片状态() {
        val core = core()
        show(core, "mpv")
        fun status(id: String, value: Double) {
            rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status",
                buildJsonObject { put("item_id", id); put("position", 40.0); put("duration", 3660.0); put("speed", value) })) }
            advance(rule, 200)
        }
        status("sh6", 1.75)
        rule.onNodeWithText("1.75×").assertIsDisplayed()
        status("sh7", 3.0)
        rule.onNodeWithText("1.75×").assertIsDisplayed()
        status("sh6", 0.0)
        rule.onNodeWithText("1.75×").assertIsDisplayed()
    }

    @Test fun 退出后仍保存最后一次已接受倍率且不操作旧内核() = savePendingSpeedAfterExit(false)

    @Test fun 停止失败也必须等待已登记倍率保存() = savePendingSpeedAfterExit(true)

    private fun savePendingSpeedAfterExit(stopFails: Boolean) {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val core = core().apply { if (stopFails) on("player.stopPlayback") { error("停止失败") } }
        val port = object : xyz.linplayer.app.core.CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.setPlaybackPrefs") { entered.complete(Unit); release.await() }
                return core.callJson(command, args, onPartial)
            }
        }
        var settled = false
        try {
            show(port, "mpv")
            rule.onNodeWithContentDescription("倍速").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.Enter); advance(rule, 300); click("2.0×")
            assertTrue(entered.isCompleted)
            rule.onNodeWithContentDescription("倍速").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.Enter); advance(rule, 300); click("3.0×")
            press(rule, Key.MediaStop); advance(rule, 400)
            val appliedBeforeExit = core.calls.count { it.first == "player.setSpeed" }
            rule.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) {
                xyz.linplayer.app.ui.player.PlayerController.awaitPendingStop(); settled = true
            } }
            advance(rule, 200)
            assertFalse("新播放不得越过未完成的倍率保存", settled)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 400)
            rule.waitForIdle()
            assertTrue(settled)
            assertEquals(3.0, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.dbl("default_speed")!!, .001)
            assertEquals("退出后只保存偏好，不向旧内核提交待处理倍率", appliedBeforeExit,
                core.calls.count { it.first == "player.setSpeed" })
        } finally { release.complete(Unit) }
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
            withMediaInfo { rule.onNodeWithText("2160p", substring = true).assertDoesNotExist() }
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 1000)
            withMediaInfo { rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists() }
            withMediaInfo { rule.onNodeWithText("2160p", substring = true).assertDoesNotExist() }
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
        withMediaInfo { rule.onNodeWithText("2160p", substring = true).assertDoesNotExist() }
        withMediaInfo { rule.onNodeWithText("1080p", substring = true).assertDoesNotExist() }
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
            withMediaInfo { rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists() }
            press(rule, Key.MediaNext)
            advance(rule, 600)
            withMediaInfo { rule.onNodeWithText("1080p", substring = true).assertDoesNotExist() }
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 600)
            withMediaInfo { rule.onNodeWithText("1080p", substring = true).assertDoesNotExist() }
            withMediaInfo { rule.onNodeWithText("2160p", substring = true).assertDoesNotExist() }
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
            withMediaInfo { rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists() }
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 600)
            assertTrue("旧响应已完成才检查当前规格", returned.isCompleted)
            withMediaInfo { rule.onNodeWithText("1080p · AAC 7.1", substring = true).assertExists() }
            withMediaInfo { rule.onNodeWithText("2160p", substring = true).assertDoesNotExist() }
        } finally { release.complete(Unit) }
    }

    @Test fun 换集后迟到的起播结果不改写当前规格() =
        lateOldResultDoesNotReplaceNewSpec("player.play")

    @Test fun 换集后迟到的版本资料不改写当前规格() =
        lateOldResultDoesNotReplaceNewSpec("emby.itemMedia")
}
