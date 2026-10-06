package xyz.linplayer.app

import android.app.Application
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.player.PlaybackSnapshot
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.TrackIdentity
import xyz.linplayer.app.ui.player.isEngineCompatibilityError
import xyz.linplayer.app.ui.player.matchingTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlayerControllerTest {
    private val decoderError = PlaybackException("解码初始化失败", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
    private val state = PlaybackSnapshot(42.5, true, 1.25, 0.4f, subOff = true)

    @Test fun 模式兼容旧值且长按明确选择内核() {
        fun engine(mode: String) = PlayerController(mode) { _, _ -> JsonObject(emptyMap()) }.engine
        assertEquals("exo", engine("auto"))
        assertEquals("exo", engine("exo"))
        assertEquals("mpv", engine("mpv"))
        assertEquals("mpv", engine("unknown"))
        assertEquals("mpv", UiPrefs.otherEngine("auto"))
        assertEquals("mpv", UiPrefs.otherEngine("exo"))
        assertEquals("exo", UiPrefs.otherEngine("mpv"))
        assertEquals("Media3", UiPrefs.engineLabel("exo"))
    }

    @Test fun 仅明确兼容错误允许回退() {
        val allowed = listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
        for (code in allowed) assertTrue(isEngineCompatibilityError(PlaybackException("失败", null, code)))
        val denied = listOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED, PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_UNSPECIFIED)
        for (code in denied) assertFalse(isEngineCompatibilityError(PlaybackException("失败", null, code)))
    }

    @Test fun 自动回退等待旧会话收尾并只执行一次() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = PlayerController("auto") { command, arguments ->
            calls += command to arguments
            if (command == "player.stopPlayback") { entered.complete(Unit); stopped.await() }
            JsonObject(emptyMap())
        }
        controller.resolved(buildJsonObject { put("media_source_id", "chosen"); put("resume_secs", 30) })
        var outputStopped = false
        val job = launch { assertTrue(controller.tryFallback(decoderError, state) { outputStopped = true }) }
        entered.await()
        assertTrue(outputStopped)
        assertTrue(controller.switching)
        assertEquals("exo", controller.engine)
        assertNull(controller.fallback)
        assertFalse(controller.tryFallback(decoderError, state))
        stopped.complete(Unit)
        job.join()
        assertEquals("mpv", controller.engine)
        assertEquals(42.5, calls.single().second.dbl("pos")!!, 0.0)
        assertFalse(controller.tryFallback(decoderError, state))
        val next = controller.playbackArgs(mapOf("item_id" to "item", "from_start" to true, "media_source_id" to "initial"))
        assertEquals("chosen", next.str("media_source_id"))
        assertEquals("mpv", next.str("engine"))
        assertEquals(42.5, next.dbl("resume_secs")!!, 0.0)
        assertFalse(next.bool("from_start"))
        controller.bind(null)
        controller.restoreState()
        assertEquals(listOf("player.stopPlayback", "player.setSpeed", "player.setVolume", "player.setPause", "player.setTrack"), calls.map { it.first })
        assertEquals(1.25, calls[1].second.dbl("speed")!!, 0.0)
        assertEquals(40.0, calls[2].second.dbl("volume")!!, 0.0)
        assertTrue(calls[3].second.bool("paused"))
        assertEquals("", calls[4].second.str("id"))
    }

    @Test fun 手动模式和网络失败都不换内核() = runBlocking {
        for (mode in listOf("mpv", "exo", "auto")) {
            val calls = mutableListOf<String>()
            val controller = PlayerController(mode) { command, _ -> calls += command; JsonObject(emptyMap()) }
            val error = if (mode == "auto") PlaybackException("HTTP 403", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) else decoderError
            assertFalse(controller.tryFallback(error, state))
            assertTrue(calls.isEmpty())
        }
        val protected = PlayerController("auto") { _, _ -> fail("DRM 不应尝试 MPV"); JsonObject(emptyMap()) }
        assertFalse(protected.tryFallback(decoderError, state.copy(drm = true)))
    }

    @Test fun 零秒回退不读取服务器旧进度() = runBlocking {
        val controller = PlayerController("auto") { _, _ -> JsonObject(emptyMap()) }
        assertTrue(controller.tryFallback(decoderError, state.copy(position = 0.0)))
        val next = controller.playbackArgs(mapOf("item_id" to "item"))
        assertTrue(next.bool("from_start"))
        assertEquals(0.0, next.dbl("resume_secs")!!, 0.0)
    }

    @Test fun 收尾失败不启动另一内核也不循环尝试() = runBlocking {
        val controller = PlayerController("auto") { _, _ -> error("收尾失败") }
        try { controller.tryFallback(decoderError, state); fail("应保留失败") } catch (_: IllegalStateException) { }
        assertEquals("exo", controller.engine)
        assertFalse(controller.switching)
        assertNull(controller.fallback)
        assertFalse(controller.tryFallback(decoderError, state))
    }

    @Test fun 回退轨道按身份匹配且晚到后只应用一次() = runBlocking {
        fun track(id: String, title: String, language: String) = buildJsonObject {
            put("id", id); put("kind", "audio"); put("title", title); put("lang", language)
        }
        val choice = TrackIdentity("日语", "ja", 1)
        val tracks = listOf(track("10", "中文", "zh"), track("20", "日语", "ja"))
        assertEquals("20", matchingTrack(choice, tracks.reversed()).str("id"))
        assertNull(matchingTrack(choice, listOf(tracks.first())))
        assertEquals("20", matchingTrack(choice, listOf(track("20", "日语", "jpn"))).str("id"))
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = PlayerController("auto") { command, arguments -> calls += command to arguments; JsonObject(emptyMap()) }
        controller.tryFallback(decoderError, state.copy(audio = choice))
        controller.restoreTracks(emptyList())
        controller.restoreTracks(tracks)
        controller.restoreTracks(tracks)
        val picks = calls.filter { it.first == "player.setTrack" }
        assertEquals(1, picks.size)
        assertEquals("20", picks.single().second.str("id"))
    }

    @Test fun 手动选字幕后晚到的旧外挂字幕不覆盖用户选择() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = PlayerController("auto") { command, _ -> calls += command; JsonObject(emptyMap()) }
        controller.tryFallback(decoderError, state.copy(subOff = false, subtitle = TrackIdentity("外挂中文", "zh", 0)))
        controller.restoreTracks(emptyList())
        controller.trackPicked("subtitle")
        controller.restoreTracks(listOf(buildJsonObject {
            put("kind", "sub"); put("id", "9"); put("external", true)
            put("title", "外挂中文"); put("lang", "zho")
        }))
        assertEquals(listOf("player.stopPlayback"), calls)
    }

    @Test fun 外挂字幕晚到后按身份恢复() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = PlayerController("auto") { command, arguments -> calls += command to arguments; JsonObject(emptyMap()) }
        controller.tryFallback(decoderError, state.copy(subOff = false, subtitle = TrackIdentity("外挂中文", "zh", 0)))
        controller.restoreTracks(emptyList())
        controller.restoreTracks(listOf(buildJsonObject {
            put("kind", "sub"); put("id", "9"); put("external", true)
            put("title", "外挂中文"); put("lang", "zho")
        }))
        assertEquals("9", calls.last().second.str("id"))
        assertEquals("sub", calls.last().second.str("kind"))
    }

    @Test fun 下一页起播等待离页收尾和附加清理完成() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = PlayerController("mpv") { _, _ ->
            events += "stop开始"; release.await(); events += "stop完成"; JsonObject(emptyMap())
        }
        val stopped = controller.stopIn(this, 10.0, { throw it }) { events += "标题清理" }
        assertEquals(listOf("stop开始"), events)
        val next = launch { PlayerController.awaitPendingStop(); events += "新起播" }
        yield()
        assertEquals(listOf("stop开始"), events)
        release.complete(Unit)
        next.join(); stopped.join()
        assertEquals(listOf("stop开始", "stop完成", "标题清理", "新起播"), events)
    }
}
