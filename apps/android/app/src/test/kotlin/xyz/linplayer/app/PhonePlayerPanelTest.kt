package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.PlaybackSnapshot
import xyz.linplayer.app.ui.player.TrackIdentity
import xyz.linplayer.app.ui.player.PlayerPanel
import xyz.linplayer.app.ui.player.trackLanguage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
class PhonePlayerPanelTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel() }

    @Test fun nextUpHonorsMarkedOutroAndEvenShortTrailingContent() {
        assertEquals(true, xyz.linplayer.app.ui.player.nextUpForOutro(1200.0, 1400.0, 1200.0..1400.0))
        assertEquals(false, xyz.linplayer.app.ui.player.nextUpForOutro(1399.7, 1400.0, 1200.0..1399.8))
        assertEquals(true, xyz.linplayer.app.ui.player.nextUpForOutro(1399.0, 1400.0, null))
        assertEquals(false, xyz.linplayer.app.ui.player.nextUpForOutro(0.0, 0.0, null))
    }

    @Test fun markerMenuUsesCurrentPositionAndScopeWithoutSeeking() {
        fun result(scope: String) = xyz.linplayer.app.ui.pages.args("scope" to scope, "can_season" to true)
        val core = FakeCore().loggedIn().apply {
            on("player.getSkipRange") { a -> result(if (a.str("scope") == "auto") "season" else a.str("scope")!!) }
            on("player.setSkipRange") { a -> result(a.str("scope")!!) }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        var saved = 0
        rule.setContent { LpTheme { CompositionLocalProvider(LocalApp provides app) {
            PlayerPanel("segments", "current", markerPosition = 90.0, markerDuration = 1400.0, onMarkersSaved = { saved++ }, onClose = {})
        } } }
        rule.waitForIdle()
        rule.onNodeWithText("本季").assertIsDisplayed()
        rule.onNodeWithText("当前位置设为片头结束").performClick()
        rule.waitForIdle()
        assertEquals("intro_end", core.calls.last { it.first == "player.setSkipRange" }.second.str("action"))
        assertEquals(90.0, core.calls.last { it.first == "player.setSkipRange" }.second.dbl("position")!!, .001)
        rule.onNodeWithText("应用范围").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("仅本集").assertIsDisplayed()
        rule.onNodeWithText("当前位置设为片尾开始").performClick()
        rule.waitForIdle()
        assertEquals("episode", core.calls.last { it.first == "player.setSkipRange" }.second.str("scope"))
        assertEquals(2, saved)
        assertEquals(0, core.calls.count { it.first == "player.seek" || it.first == "player.play" })
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/player-skip-markers.png")
    }

    @Test fun mpvSubtitleMenuUpdatesWhenTracksArriveLate() {
        val core = FakeCore().loggedIn().apply { ret("player.tracks", buildJsonArray {}) }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent { LpTheme { CompositionLocalProvider(LocalApp provides app) {
            PlayerPanel("subtitle", "current", onClose = {})
        } } }
        rule.waitForIdle()
        rule.onNodeWithText("晚到字幕").assertDoesNotExist()
        core.ret("player.tracks", buildJsonArray { add(buildJsonObject {
            put("id", "2"); put("kind", "sub"); put("title", "晚到字幕")
        }) })
        rule.waitUntil(5_000) { rule.onAllNodesWithText("晚到字幕").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("晚到字幕").assertIsDisplayed()
    }

    @Test fun trackFailureRetriesAndSuccessfulEmptyResultIsDistinct() {
        var failing = true
        val core = FakeCore().loggedIn().apply {
            on("player.tracks") {
                if (failing) throw CoreException("E_NETWORK", "测试读取失败", true)
                arr()
            }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        val kind = mutableStateOf("audio")
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                PlayerPanel(kind.value, "episode", onClose = {})
            } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("音轨读取失败").assertIsDisplayed()
        rule.onNodeWithText("这里没有可选项").assertDoesNotExist()
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/audio-read-error.png")
        rule.runOnIdle { failing = false }
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "player.tracks" })
        rule.onNodeWithText("这里没有可选项").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
        rule.runOnIdle { failing = true; kind.value = "subtitle" }
        rule.waitForIdle()
        rule.onNodeWithText("字幕读取失败").assertIsDisplayed()
        rule.onNodeWithText("关闭字幕").assertDoesNotExist()
        rule.runOnIdle { failing = false }
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("关闭字幕").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
        assertEquals(4, core.calls.count { it.first == "player.tracks" })
    }

    @Test fun lateFailureFromPreviousPanelCannotOverwriteNewTracks() {
        val oldResponse = CompletableDeferred<Unit>()
        var reads = 0
        var oldFinished = false
        val core = FakeCore().loggedIn().apply {
            ret("player.tracks", arr(buildJsonObject {
                put("id", "s1"); put("kind", "sub"); put("lang", "zh-Hans")
            }))
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.tracks" && ++reads == 1) {
                    try {
                        withContext(NonCancellable) { oldResponse.await(); throw CoreException("E_NETWORK", "迟到的失败", true) }
                    } finally { oldFinished = true }
                }
                return core.callJson(command, args, onPartial)
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        val kind = mutableStateOf("audio")
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                PlayerPanel(kind.value, "episode", onClose = {})
            } }
        }
        try {
            rule.waitUntil(5_000) { reads == 1 }
            rule.runOnIdle { kind.value = "subtitle" }
            rule.waitForIdle()
            rule.onNodeWithText("简体中文").assertIsDisplayed()
            oldResponse.complete(Unit)
            rule.waitUntil(5_000) { oldFinished }
            rule.waitForIdle()
            rule.onNodeWithText("简体中文").assertIsDisplayed()
            rule.onNodeWithText("音轨读取失败").assertDoesNotExist()
            rule.onNodeWithText("这里没有可选项").assertDoesNotExist()
        } finally { oldResponse.complete(Unit) }
    }

    @Test fun 面板关闭后手选仍排在在途恢复后提交() {
        val release = CompletableDeferred<Unit>()
        val tracks = listOf(
            buildJsonObject { put("id", "1"); put("kind", "audio"); put("lang", "zho"); put("title", "中文"); put("selected", true) },
            buildJsonObject { put("id", "2"); put("kind", "audio"); put("lang", "eng"); put("title", "英语") })
        val core = FakeCore().loggedIn().apply {
            ret("player.tracks", arr(*tracks.toTypedArray()))
            ret("player.stopPlayback", JsonNull)
            ret("player.setTrack", JsonNull)
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "player.setTrack" && args.str("id") == "1") release.await()
                return result
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        val controller = PlayerController("auto") { command, args -> app.call(command, args) }
        runBlocking {
            controller.tryFallback(androidx.media3.common.PlaybackException("测试解码失败", null,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
                PlaybackSnapshot(10.0, false, 1.0, 1f, audio = TrackIdentity("中文", "zh")))
        }
        controller.started()
        val opened = mutableStateOf(true)
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                PlayerPanel(if (opened.value) "audio" else null, "episode", onTrackPicked = controller::trackPicked,
                    onMpvTrackPick = controller::pickTrack, onClose = { opened.value = false })
            } }
        }
        rule.waitForIdle()
        rule.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.restoreTracks(tracks) } }
        try {
            rule.onNodeWithText("英语").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("英语").assertDoesNotExist()
            assertEquals(listOf("1"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
        } finally { rule.runOnIdle { release.complete(Unit) } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.runOnIdle { }; core.calls.count { it.first == "player.setTrack" } == 2 }
        assertEquals(listOf("1", "2"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
    }

    @Test fun trackPanelUsesChineseLanguagesAndDispatchesSelection() {
        val core = FakeCore().loggedIn().apply {
            ret("player.tracks", arr(
                buildJsonObject { put("id", "1"); put("kind", "audio"); put("lang", "jpn"); put("title", "原声 AAC stereo"); put("selected", true) },
                buildJsonObject { put("id", "2"); put("kind", "audio"); put("lang", "eng") },
                buildJsonObject { put("id", "3"); put("kind", "audio"); put("lang", "und"); put("title", "未标注") },
                buildJsonObject { put("id", "4"); put("kind", "sub"); put("lang", "zh-Hans"); put("codec", "ass"); put("selected", true) },
                buildJsonObject { put("id", "5"); put("kind", "sub"); put("lang", "zh-TW"); put("codec", "hdmv_pgs_subtitle"); put("external", true) }))
            ret("player.setTrack", JsonNull)
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        val dark = mutableStateOf(false)
        val kind = mutableStateOf("audio")
        var pickedKind = ""
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF455F48))) {
                        PlayerPanel(kind.value, "episode", onTrackPicked = { pickedKind = it }, onClose = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("音轨").assertDoesNotExist()
        rule.onNodeWithText("日语").assertIsDisplayed()
        rule.onNodeWithText("英语").assertIsDisplayed()
        rule.onNodeWithText("未标注").assertDoesNotExist()
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/audio-panel-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/audio-panel-dark.png")
        rule.onNodeWithText("英语").performClick()
        rule.waitForIdle()
        assertEquals("2", core.calls.last { it.first == "player.setTrack" }.second.str("id"))
        assertEquals("audio", pickedKind)
        rule.runOnIdle { kind.value = "subtitle" }
        rule.waitForIdle()
        rule.onNodeWithText("关闭字幕").assertIsDisplayed()
        rule.onNodeWithText("简体中文").assertIsDisplayed()
        rule.onNodeWithText("繁体中文").assertIsDisplayed()
        rule.onNodeWithText("ASS · 特效字幕 · 内嵌").assertIsDisplayed()
        rule.onNodeWithText("PGS · 图形字幕 · 外挂").assertIsDisplayed()
        rule.onNode(isPopup()).captureRoboImage("build/player-osd/subtitle-panel-dark.png")
        rule.onNodeWithText("繁体中文").performClick()
        rule.waitForIdle()
        assertEquals("5", core.calls.last { it.first == "player.setTrack" }.second.str("id"))
        assertEquals("sub", core.calls.last { it.first == "player.setTrack" }.second.str("kind"))
        assertEquals("subtitle", pickedKind)
        assertEquals("简体中文", trackLanguage("zh-Hans", "subtitle"))
        assertEquals("繁体中文", trackLanguage("zh-TW", "subtitle"))
        assertEquals(null, trackLanguage("und", "audio"))
    }
}
