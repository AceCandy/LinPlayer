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
import xyz.linplayer.app.data.long
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

    @Test fun longSeasonLocatesCurrentOnceAndKeepsManualScrollDuringPaging() {
        val secondPage = CompletableDeferred<Unit>()
        val lastPage = CompletableDeferred<Unit>()
        val starts = mutableListOf<Int>()
        val core = FakeCore().loggedIn().apply {
            ret("emby.itemDetail", buildJsonObject { put("season_id", "season") })
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "emby.seasonEpisodes") return core.callJson(command, args, onPartial)
                val start = (args.long("start_index") ?: 0).toInt()
                starts += start
                if (start == 40) secondPage.await()
                if (start == 120) lastPage.await()
                // 服务端只给 40 条，即使客户端请求更多也必须继续分页。
                val count = minOf((args.long("limit") ?: 40).toInt(), 40)
                return buildJsonObject {
                    put("total", 143)
                    put("items", buildJsonArray {
                        for (i in start until minOf(start + count, 143)) add(item(
                            "ep${i + 1}", "第${i + 1}集", "Episode", season = 1, episode = i + 1))
                    })
                }
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        var target = ""
        var closed = false
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                PlayerPanel("episodes", "ep80", onPlaybackTarget = { id, _, _ -> target = id }, onClose = { closed = true })
            } }
        }
        try {
            rule.waitUntil(5_000) { 40 in starts }
            rule.onNodeWithText("S1E1 第1集").assertIsDisplayed()
            secondPage.complete(Unit)
            rule.waitUntil(5_000) { 120 in starts }
            rule.waitForIdle()
            assertEquals(false, lastPage.isCompleted)
            rule.onNodeWithText("S1E80 第80集").assertIsDisplayed()
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
            rule.onNodeWithText("S1E100 第100集").assertIsDisplayed()
            lastPage.complete(Unit)
            rule.waitForIdle()
            rule.onNodeWithText("S1E100 第100集").assertIsDisplayed()
            rule.onNodeWithText("S1E80 第80集").assertIsNotDisplayed()
            assertEquals(listOf(0, 40, 80, 120), starts)
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(142)
            rule.onNodeWithText("S1E143 第143集").assertIsDisplayed()
            rule.onRoot().captureRoboImage("build/player-osd/episodes-last-page.png")
            rule.onNodeWithText("S1E143 第143集").performClick()
            assertEquals("ep143", target)
            assertEquals(true, closed)
            assertEquals(0, core.calls.count { it.first == "player.play" })
        } finally { secondPage.complete(Unit); lastPage.complete(Unit) }
    }

    @Test fun failedEpisodePageKeepsLoadedItemsAndRetriesFromFailedOffset() {
        val starts = mutableListOf<Int>()
        var failed = false
        val core = FakeCore().loggedIn().apply {
            ret("emby.itemDetail", buildJsonObject { put("season_id", "season") })
            on("emby.seasonEpisodes") { a ->
                val start = (a.long("start_index") ?: 0).toInt()
                starts += start
                if (start == 40 && !failed) {
                    failed = true
                    throw CoreException("E_NETWORK", "测试网络失败", true)
                }
                buildJsonObject {
                    put("total", 81)
                    put("items", buildJsonArray {
                        for (i in start until minOf(start + 40, 81)) add(item(
                            "ep${i + 1}", "第${i + 1}集", "Episode", season = 1, episode = i + 1))
                    })
                }
            }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                PlayerPanel("episodes", "ep10", onClose = {})
            } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("选集加载失败").assertIsDisplayed()
        rule.onNodeWithText("S1E10 第10集").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        rule.onNodeWithText("S1E31 第31集").assertIsDisplayed()
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        assertEquals(listOf(0, 40, 40, 80), starts)
        rule.onNodeWithText("选集加载失败").assertDoesNotExist()
        rule.onNodeWithText("S1E31 第31集").assertIsDisplayed()
        rule.onNodeWithText("S1E10 第10集").assertIsNotDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(79)
        rule.onNodeWithText("S1E80 第80集").assertIsDisplayed()
    }

    @Test fun changingTargetResetsEpisodeOffsetAndLeavingCancelsRemainingPages() {
        val oldResponse = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Unit>()
        val starts = mutableListOf<Pair<String?, Long?>>()
        var finished = 0
        val core = FakeCore().loggedIn().apply {
            on("emby.itemDetail") { a -> buildJsonObject { put("season_id", if (a.str("item_id") == "old80") "old" else "new") } }
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "emby.seasonEpisodes") return core.callJson(command, args, onPartial)
                val parent = args.str("parent_id")
                val start = args.long("start_index") ?: 0
                starts += parent to start
                if (start == 40L) {
                    try {
                        withContext(NonCancellable) { (if (parent == "old") oldResponse else newResponse).await() }
                    } finally { finished++ }
                }
                return buildJsonObject {
                    put("total", 81)
                    put("items", buildJsonArray {
                        for (i in start.toInt() until minOf(start.toInt() + 40, 81)) add(item(
                            "$parent${i + 1}", "$parent 第${i + 1}集", "Episode", season = 1, episode = i + 1))
                    })
                }
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        val target = mutableStateOf("old80")
        val visible = mutableStateOf(true)
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                if (visible.value) PlayerPanel("episodes", target.value, onClose = {})
            } }
        }
        try {
            rule.waitUntil(5_000) { ("old" to 40L) in starts }
            rule.runOnIdle { target.value = "new80" }
            rule.waitForIdle()
            assertEquals("请求游标 $starts；详情目标 ${core.calls.filter { it.first == "emby.itemDetail" }.map { it.second.str("item_id") }}",
                listOf(0L, 40L), starts.filter { it.first == "new" }.map { it.second })
            rule.onNodeWithText("S1E1 new 第1集").assertIsDisplayed()
            oldResponse.complete(Unit)
            rule.waitUntil(5_000) { finished == 1 }
            rule.waitForIdle()
            rule.onNodeWithText("S1E1 new 第1集").assertIsDisplayed()
            rule.onNodeWithText("S1E1 old 第1集").assertDoesNotExist()
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            rule.onNodeWithText("选集").assertDoesNotExist()
            newResponse.complete(Unit)
            rule.waitUntil(5_000) { finished == 2 }
            rule.waitForIdle()
            assertEquals(listOf("old" to 0L, "old" to 40L, "new" to 0L, "new" to 40L), starts)
        } finally { oldResponse.complete(Unit); newResponse.complete(Unit) }
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
        rule.onRoot().captureRoboImage("build/player-osd/audio-read-error.png")
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
                if (opened.value) PlayerPanel("audio", "episode", onTrackPicked = controller::trackPicked,
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
                buildJsonObject { put("id", "4"); put("kind", "sub"); put("lang", "zh-Hans"); put("selected", true) },
                buildJsonObject { put("id", "5"); put("kind", "sub"); put("lang", "zh-TW") }))
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
        rule.onNodeWithText("日语").assertIsDisplayed()
        rule.onNodeWithText("英语").assertIsDisplayed()
        rule.onNodeWithText("未标注").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/player-osd/audio-panel-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/player-osd/audio-panel-dark.png")
        rule.onNodeWithText("英语").performClick()
        rule.waitForIdle()
        assertEquals("2", core.calls.last { it.first == "player.setTrack" }.second.str("id"))
        assertEquals("audio", pickedKind)
        rule.runOnIdle { kind.value = "subtitle" }
        rule.waitForIdle()
        rule.onNodeWithText("关闭字幕").assertIsDisplayed()
        rule.onNodeWithText("简体中文").assertIsDisplayed()
        rule.onNodeWithText("繁体中文").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/subtitle-panel-dark.png")
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
