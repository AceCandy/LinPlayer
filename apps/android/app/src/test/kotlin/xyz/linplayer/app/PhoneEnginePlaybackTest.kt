package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PhoneEnginePlaybackTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun cleanup() {
        PlaybackService.stop(ApplicationProvider.getApplicationContext())
        scope.cancel()
    }

    @Test fun Media3换版本和选集继续通过页面起播并等待旧会话停止() {
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            on("player.play") { arguments -> buildJsonObject {
                put("media_source_id", arguments.str("media_source_id") ?: "ea")
                put("resume_secs", 0.0)
            } }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Player("sh6", "测试剧集", engine = "exo", ar = 16f / 9)) {
                        composable<Route.Player> { PlayerPage(nav, it) }
                    }
                }
            }
        }
        advance(rule, 800)
        assertEquals("初次起播请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithContentDescription("更多").performClick()
        advance(rule, 300)
        assertEquals("打开更多后的请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithText("版本与线路").performClick()
        advance(rule, 300)
        assertEquals("打开版本面板后的请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithText("1080p").performClick()
        advance(rule, 500)
        val plays = core.calls.filter { it.first == "player.play" }
        assertEquals(plays.map { listOf(it.second.str("item_id"), it.second.str("engine"), it.second.str("media_source_id")) }.toString(), 2, plays.size)
        assertEquals("exo", plays.last().second.str("engine"))
        assertEquals("eb", plays.last().second.str("media_source_id"))
        assertEquals(0.0, plays.last().second.dbl("resume_secs")!!, 0.0)
        assertTrue(plays.last().second.bool("from_start"))
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        val stop = core.calls.indexOfFirst { it.first == "player.stopPlayback" }
        val next = core.calls.indexOfLast { it.first == "player.play" }
        assertTrue(stop in 0 until next)
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel("episodes") }
        advance(rule, 300)
        rule.onNode(hasText("S1E7", substring = true)).performClick()
        advance(rule, 500)
        val episode = core.calls.last { it.first == "player.play" }.second
        assertEquals(3, core.calls.count { it.first == "player.play" })
        assertEquals(2, core.calls.count { it.first == "player.stopPlayback" })
        assertEquals("sh7", episode.str("item_id"))
        assertEquals("exo", episode.str("engine"))
        assertFalse(episode!!.containsKey("media_source_id"))
        assertFalse(episode.containsKey("resume_secs"))
        assertEquals(listOf("player.play", "player.stopPlayback", "player.play", "player.stopPlayback", "player.play"),
            core.calls.map { it.first }.filter { it == "player.play" || it == "player.stopPlayback" })
    }

    @Test fun 自动回退等待停止和慢起播后仍恢复播放状态及晚到音轨() {
        val stopRelease = CompletableDeferred<Unit>()
        val playRelease = CompletableDeferred<Unit>()
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            ret("player.setSpeed", JsonNull)
            ret("player.setVolume", JsonNull)
            ret("player.setPause", JsonNull)
            ret("player.setTrack", JsonNull)
            ret("player.setAspectRatio", JsonNull)
            ret("prefs.getPrefs", buildJsonObject { put("sub_enabled", false) })
            on("player.play") { buildJsonObject {
                put("media_source_id", "resolved-version")
                put("resume_secs", 42.5)
            } }
            on("player.tracks") {
                if (!playRelease.isCompleted) arr() else arr(buildJsonObject {
                    put("kind", "audio"); put("id", "mpv-japanese")
                    put("title", "日语"); put("lang", "jpn")
                })
            }
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "player.stopPlayback") stopRelease.await()
                if (command == "player.play" && args.str("engine") == "mpv") playRelease.await()
                return result
            }
        }
        showPlayer(port, "auto")
        lateinit var exo: ExoPlayer
        rule.runOnIdle {
            exo = currentExo()
            exo.seekTo(42_500)
            exo.playWhenReady = false
            exo.setPlaybackSpeed(1.5f)
            exo.volume = 0.4f
            injectError(exo, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, selectedAudio = true)
            assertEquals("日语", exo.currentTracks.groups.single().getTrackFormat(0).label)
        }
        advance(rule, 800)
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        assertEquals("旧停止挂起时不启动 MPV", 1, core.calls.count { it.first == "player.play" })
        assertEquals(42.5, core.calls.single { it.first == "player.stopPlayback" }.second.dbl("pos")!!, 0.0)

        rule.runOnIdle { stopRelease.complete(Unit) }
        advance(rule, 800)
        val plays = core.calls.filter { it.first == "player.play" }
        assertEquals(listOf("exo", "mpv"), plays.map { it.second.str("engine") })
        assertEquals("resolved-version", plays.last().second.str("media_source_id"))
        assertEquals(42.5, plays.last().second.dbl("resume_secs")!!, 0.0)
        assertFalse(plays.last().second.bool("from_start"))
        rule.runOnIdle { assertTrue("旧 Media3 已释放", exo.isReleased) }
        assertTrue(core.calls.indexOfFirst { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })

        // 起播等待超过旧轮询窗口；次数必须留给起播成功后的轨道。
        advance(rule, 12_500)
        assertEquals("MPV 尚未起播成功，不读取轨表", 0, core.calls.count { it.first == "player.tracks" })
        assertFalse(core.calls.any { it.first == "player.setSpeed" || it.first == "player.setTrack" })
        rule.runOnIdle { playRelease.complete(Unit) }
        advance(rule, 1_800)
        assertEquals(1.5, core.calls.single { it.first == "player.setSpeed" }.second.dbl("speed")!!, 0.0)
        assertEquals(40.0, core.calls.single { it.first == "player.setVolume" }.second.dbl("volume")!!, 0.0)
        assertTrue(core.calls.single { it.first == "player.setPause" }.second.bool("paused"))
        val picks = core.calls.filter { it.first == "player.setTrack" }
        assertEquals(listOf("", "mpv-japanese"), picks.map { it.second.str("id") })
        assertEquals(listOf("sub", "audio"), picks.map { it.second.str("kind") })
        advance(rule, 2_000)
        assertEquals("音轨只恢复一次", 2, core.calls.count { it.first == "player.setTrack" })
        assertEquals("回退不重复起播", 2, core.calls.count { it.first == "player.play" })
    }

    @Test fun 自动模式网络错误显示失败而不回退() {
        assertNoFallback("auto", PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    }

    @Test fun 手动Media3解码错误显示失败而不回退() {
        assertNoFallback("exo", PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
    }

    private fun assertNoFallback(mode: String, errorCode: Int) {
        val core = FakeCore().loggedIn().player()
        showPlayer(core, mode)
        rule.runOnIdle { injectError(currentExo(), errorCode) }
        advance(rule, 1_000)
        rule.onNodeWithText("这一片没能播起来").assertIsDisplayed()
        assertEquals(listOf("exo"), core.calls.filter { it.first == "player.play" }.map { it.second.str("engine") })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
    }

    private fun showPlayer(core: CorePort, mode: String) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Player("sh6", "测试剧集", versionId = "requested-version", engine = mode, ar = 16f / 9)) {
                        composable<Route.Player> { PlayerPage(nav, it) }
                    }
                }
            }
        }
        advance(rule, 800)
    }

    private fun currentExo(): ExoPlayer = ReflectionHelpers.getStaticField(PlaybackService::class.java, "externalPlayer")

    /** 注入安装版本的 Media3 状态，仍由真实页面读取错误并执行整条回退链路。 */
    private fun injectError(player: ExoPlayer, errorCode: Int, selectedAudio: Boolean = false) {
        val info: Any = ReflectionHelpers.getField(player, "playbackInfo")
        if (selectedAudio) {
            val selector: Any = ReflectionHelpers.getField(info, "trackSelectorResult")
            val group = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLabel("日语").setLanguage("ja").build())
            ReflectionHelpers.setField(selector, "tracks", Tracks(listOf(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))))
        }
        val error = if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            ExoPlaybackException.createForSource(java.io.IOException("网络连接失败"), errorCode)
        else ExoPlaybackException.createForRenderer(IllegalStateException("解码初始化失败"), "测试解码器", 0, null, C.FORMAT_HANDLED, false, errorCode)
        ReflectionHelpers.setField(info, "playbackError", error)
        assertEquals(errorCode, player.playerError?.errorCode)
    }
}
