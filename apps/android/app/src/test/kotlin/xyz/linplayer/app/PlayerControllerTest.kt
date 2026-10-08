package xyz.linplayer.app

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
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

    @Test fun 连按累加合并且旧完成不能清新目标() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Double>()
        val controller = PlayerController("mpv") { _, args ->
            calls += args.dbl("pos")!!
            if (calls.size == 1) release.await()
            JsonObject(emptyMap())
        }
        val first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seekBy(10.0, 40.0, 100.0) }
        val second = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seekBy(10.0, 40.0, 100.0) }
        val third = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seekBy(10.0, 40.0, 100.0) }
        try {
            assertEquals(70.0, controller.seekTarget!!, 0.0)
            controller.observePosition(70.0, false)
            assertEquals("未提交目标不被旧采样确认", 70.0, controller.seekTarget!!, 0.0)
        } finally { release.complete(Unit) }
        first.join(); second.join(); third.join()
        assertEquals(listOf(50.0, 70.0), calls)
        controller.observePosition(50.0, false)
        assertEquals(70.0, controller.seekTarget!!, 0.0)
        controller.observePosition(70.0, true)
        assertNotNull(controller.seekTarget)
        controller.observePosition(70.5, false)
        assertNull(controller.seekTarget)
        controller.seekBy(-100.0, 70.5, 100.0)
        assertEquals(0.0, controller.seekTarget!!, 0.0)
        controller.seekBy(150.0, 70.5, 100.0)
        assertEquals(100.0, controller.seekTarget!!, 0.0)
        controller.expireSeek(Long.MAX_VALUE)
        assertNull(controller.seekTarget)
        controller.seekBy(10.0, 45.0, 0.0)
        assertEquals(55.0, controller.seekTarget!!, 0.0)
    }

    @Test fun 停止失效排队目标且等待已发请求随后才允许新会话() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val controller = PlayerController("mpv") { command, _ ->
            calls += command
            if (command == "player.seek") release.await()
            JsonObject(emptyMap())
        }
        val first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seek(50.0) }
        val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seek(70.0) }
        val stop = controller.stopIn(this, 40.0, { throw it })
        try {
            assertNull(controller.seekTarget)
            controller.seek(90.0)
            assertEquals(listOf("player.seek"), calls)
        } finally { release.complete(Unit) }
        first.join(); queued.join(); stop.join()
        assertEquals(listOf("player.seek", "player.stopPlayback"), calls)
        controller.begin(); controller.started(); controller.seek(20.0)
        assertEquals(20.0, controller.seekTarget!!, 0.0)
    }

    @Test fun 旧失败和取消只释放自己的目标() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var count = 0
        val controller = PlayerController("mpv") { _, _ ->
            if (++count == 1) { release.await(); error("失败") }
            JsonObject(emptyMap())
        }
        val first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { runCatching { controller.seek(10.0) } }
        val last = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seek(20.0) }
        release.complete(Unit); first.join(); last.join()
        assertEquals(20.0, controller.seekTarget!!, 0.0)
        val failing = PlayerController("mpv") { _, _ -> error("失败") }
        runCatching { failing.seek(10.0) }
        assertNull(failing.seekTarget)
        val completion = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val waiting = PlayerController("mpv") { command, _ ->
            events += command
            if (command == "player.seek") completion.await()
            JsonObject(emptyMap())
        }
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { waiting.seek(10.0) }
        job.cancel()
        assertFalse("已提交命令须等回执，不能提前放开屏障", job.isCompleted)
        val stopped = waiting.stopIn(this, 0.0, { throw it })
        assertEquals(listOf("player.seek"), events)
        completion.complete(Unit); job.join(); stopped.join()
        assertEquals(listOf("player.seek", "player.stopPlayback"), events)
        assertNull(waiting.seekTarget)
        failing.seek(Double.NaN)
        assertNull(failing.seekTarget)
    }

    @Test fun 服务重绑后旧排队跳转和停止不提交() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var current = true
        val controller = PlayerController("mpv") { command, _ ->
            calls += command
            if (command == "player.seek") release.await()
            JsonObject(emptyMap())
        }
        val first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seek(10.0) }
        val old = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.seek(20.0) { current } }
        current = false; release.complete(Unit)
        first.join(); old.join()
        assertEquals(listOf("player.seek"), calls)
        assertNull(controller.seekTarget)
        controller.stop(0.0) { current }
        assertEquals(listOf("player.seek"), calls)
    }

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
        val choice = TrackIdentity("日语", "ja")
        val tracks = listOf(track("10", "中文", "zh"), track("20", "日语", "ja"))
        assertEquals("20", matchingTrack(choice, tracks.reversed()).str("id"))
        assertNull(matchingTrack(choice, listOf(tracks.first())))
        assertEquals("20", matchingTrack(choice, listOf(track("20", "日语", "jpn"))).str("id"))
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = PlayerController("auto") { command, arguments -> calls += command to arguments; JsonObject(emptyMap()) }
        controller.tryFallback(decoderError, state.copy(audio = choice))
        controller.started()
        controller.restoreTracks(emptyList())
        controller.restoreTracks(tracks)
        controller.restoreTracks(tracks)
        val picks = calls.filter { it.first == "player.setTrack" }
        assertEquals(1, picks.size)
        assertEquals("20", picks.single().second.str("id"))
    }

    @Test fun 在途恢复之后手选最后提交且关闭面板不取消已登记选择() = runBlocking {
        for (kind in listOf("audio", "sub")) {
            val release = CompletableDeferred<Unit>()
            val ids = mutableListOf<String?>()
            val controller = PlayerController("auto") { command, args ->
                if (command == "player.setTrack") {
                    ids += args.str("id")
                    if (ids.size == 1) release.await()
                }
                JsonObject(emptyMap())
            }
            val choice = TrackIdentity("中文", "zh")
            controller.tryFallback(decoderError, state.copy(subOff = false,
                audio = if (kind == "audio") choice else null, subtitle = if (kind == "sub") choice else null))
            controller.started()
            val tracks = listOf(buildJsonObject { put("kind", kind); put("id", "1"); put("title", "中文"); put("lang", "zho") })
            val restore = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.restoreTracks(tracks) }
            val manual = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.pickTrack(kind, if (kind == "sub") "" else "2") }
            manual.cancel() // 真实面板提交后立即关闭，组合取消不能丢掉手选。
            try { assertEquals(listOf("1"), ids) } finally { release.complete(Unit) }
            restore.join(); manual.join()
            controller.restoreTracks(tracks)
            assertEquals(listOf("1", if (kind == "sub") "" else "2"), ids)
        }
    }

    @Test fun 换片失效排队手选且等待在途命令再停止() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = PlayerController("mpv") { command, args ->
            if (command == "player.setTrack") {
                events += "选轨" + args.str("id")
                release.await()
            } else if (command == "player.stopPlayback") events += "停止"
            JsonObject(emptyMap())
        }
        val first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.pickTrack("audio", "1") }
        val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.pickTrack("audio", "2") }
        val stopped = controller.stopIn(this, 10.0, { throw it })
        try {
            controller.pickTrack("sub", "")
            yield()
            assertEquals(listOf("选轨1"), events)
        } finally { release.complete(Unit) }
        first.join(); queued.join(); stopped.join()
        assertEquals(listOf("选轨1", "停止"), events)
        controller.begin(); controller.started(); controller.pickTrack("subtitle", "3")
        assertEquals(listOf("选轨1", "停止", "选轨3"), events)
    }

    @Test fun 详情预选晚到不覆盖手选且新的播放重新允许预选() = runBlocking {
        val calls = mutableListOf<String?>()
        val controller = PlayerController("mpv") { command, args ->
            if (command == "player.setTrack") calls += args.str("id")
            JsonObject(emptyMap())
        }
        controller.pickTrack("subtitle", "2")
        controller.restoreInitialTrack("sub", "1")
        controller.restoreInitialTrack("audio", "3")
        assertEquals(listOf("2", "3"), calls)
        controller.begin(); controller.started()
        controller.restoreInitialTrack("sub", "1")
        assertEquals(listOf("2", "3", "1"), calls)
    }

    @Test fun 选轨失败不锁住后续手选() = runBlocking {
        val calls = mutableListOf<String?>()
        val controller = PlayerController("auto") { command, args ->
            if (command == "player.setTrack") { calls += args.str("id"); if (calls.size == 1) error("选轨失败") }
            JsonObject(emptyMap())
        }
        controller.tryFallback(decoderError, state.copy(subOff = false, audio = TrackIdentity("中文", "zh")))
        controller.started()
        val tracks = listOf(buildJsonObject { put("kind", "audio"); put("id", "1"); put("title", "中文"); put("lang", "zho") })
        try { controller.restoreTracks(tracks); fail("恢复失败应传播") } catch (_: IllegalStateException) { }
        controller.pickTrack("audio", "2")
        controller.restoreTracks(tracks)
        assertEquals(listOf("1", "2"), calls)
    }

    @Test fun 恢复选轨取消后停播仍等真实回执() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = PlayerController("auto") { command, _ ->
            if (command == "player.setTrack") {
                events += "恢复开始"
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                events += "恢复完成"
            } else if (command == "player.stopPlayback") events += "停止"
            JsonObject(emptyMap())
        }
        controller.tryFallback(decoderError, state.copy(subOff = false, audio = TrackIdentity("中文", "zh")))
        controller.started()
        events.clear()
        val restore = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            controller.restoreTracks(listOf(buildJsonObject {
                put("kind", "audio"); put("id", "1"); put("title", "中文"); put("lang", "zho")
            }))
        }
        restore.cancel()
        val stopped = controller.stopIn(this, 10.0, { throw it })
        val next = launch { PlayerController.awaitPendingStop(); events += "新起播" }
        try {
            yield()
            assertEquals("停播不能越过在途恢复", listOf("恢复开始"), events)
        } finally { release.complete(Unit) }
        restore.join(); stopped.join(); next.join()
        assertEquals(listOf("恢复开始", "恢复完成", "停止", "新起播"), events)
    }

    @Test fun 重复标签及无身份不能按旧序号猜选() {
        fun track(id: String, title: String, language: String) = buildJsonObject {
            put("id", id); put("title", title); put("lang", language)
        }
        val duplicates = listOf(track("10", "中文", "zho"), track("20", "中文", "zh"))
        for (tracks in listOf(duplicates, duplicates.reversed())) {
            assertNull("重复标签不能用旧序号消歧", matchingTrack(TrackIdentity("中文", "zh"), tracks))
            assertNull("只有语言时多个候选也不能猜选", matchingTrack(TrackIdentity(null, "zh"), tracks))
        }
        assertNull("空标签不构成身份", matchingTrack(TrackIdentity("", null), listOf(track("10", "", ""))))
        assertNull("und语言不构成身份", matchingTrack(TrackIdentity(null, "und"), listOf(track("10", "", "und"))))
        assertEquals("10", matchingTrack(TrackIdentity(null, "zh"), listOf(duplicates.first())).str("id"))
    }

    @Test fun 歧义不发选轨命令且保留身份直到唯一匹配() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = PlayerController("auto") { command, arguments -> calls += command to arguments; JsonObject(emptyMap()) }
        val choice = TrackIdentity("中文", "zh")
        controller.tryFallback(decoderError, state.copy(subOff = false, audio = choice, subtitle = choice))
        controller.started()
        fun track(kind: String, id: String) = buildJsonObject {
            put("kind", kind); put("id", id); put("title", "中文"); put("lang", "zho")
        }
        controller.restoreTracks(listOf(track("audio", "1"), track("audio", "2"), track("sub", "3"), track("sub", "4")))
        assertTrue("歧义不能提交选轨", calls.none { it.first == "player.setTrack" })
        val unique = listOf(track("audio", "2"), track("sub", "4"))
        controller.restoreTracks(unique)
        controller.restoreTracks(unique)
        assertEquals(listOf("2", "4"), calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
    }

    @Test fun 手选音轨和字幕关闭不被晚到轨道覆盖() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val controller = PlayerController("auto") { command, arguments -> calls += command to arguments; JsonObject(emptyMap()) }
        val choice = TrackIdentity("中文", "zh")
        controller.tryFallback(decoderError, state.copy(audio = choice, subtitle = choice, subOff = true))
        controller.started()
        controller.trackPicked("audio")
        controller.bind(null)
        controller.restoreState()
        controller.restoreTracks(listOf("audio", "sub").map { kind -> buildJsonObject {
            put("kind", kind); put("id", "9"); put("title", "中文"); put("lang", "zho")
        } })
        val picks = calls.filter { it.first == "player.setTrack" }
        assertEquals(1, picks.size)
        assertEquals("sub", picks.single().second.str("kind"))
        assertEquals("", picks.single().second.str("id"))
    }

    @Test fun 手动选字幕后晚到的旧外挂字幕不覆盖用户选择() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = PlayerController("auto") { command, _ -> calls += command; JsonObject(emptyMap()) }
        controller.tryFallback(decoderError, state.copy(subOff = false, subtitle = TrackIdentity("外挂中文", "zh")))
        controller.started()
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
        controller.tryFallback(decoderError, state.copy(subOff = false, subtitle = TrackIdentity("外挂中文", "zh")))
        controller.started()
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
    @Test fun 强制与位图属性参与唯一匹配且未知不能当作普通字幕() = runBlocking {
        fun track(id: String, forced: Boolean?, codec: String) = buildJsonObject {
            put("id", id); put("kind", "sub"); put("title", "中文"); put("lang", "zho"); put("codec", codec)
            if (forced != null) put("forced", forced)
        }
        val ordinary = track("1", false, "ass")
        val forced = track("2", true, "ass")
        val bitmap = track("3", true, "hdmv_pgs_subtitle")
        val choice = TrackIdentity("中文", "zh", forced = true, bitmap = false)
        for (tracks in listOf(listOf(ordinary, forced, bitmap), listOf(bitmap, forced, ordinary))) {
            assertEquals("2", matchingTrack(choice, tracks).str("id"))
        }
        assertNull(matchingTrack(choice, listOf(ordinary)))
        assertNull(matchingTrack(choice, listOf(track("4", null, "ass"))))
        assertNull(matchingTrack(choice, listOf(track("4", true, "unknown"))))
        assertNull(matchingTrack(choice, listOf(forced, forced)))
        for (codec in listOf("dvd_subtitle", "dvb_subtitle", "hdmv_pgs_subtitle")) {
            val candidate = track("5", true, codec)
            assertEquals("5", matchingTrack(choice.copy(bitmap = true), listOf(candidate, forced)).str("id"))
        }
        for (codec in listOf("ass", "subrip", "webvtt")) {
            assertEquals("5", matchingTrack(choice, listOf(track("5", true, codec), bitmap)).str("id"))
        }
        assertNull(matchingTrack(choice, listOf(buildJsonObject {
            put("id", "5"); put("title", "中文"); put("lang", "zho"); put("codec", "ass")
            put("forced", kotlinx.serialization.json.JsonNull)
        })))
        assertNull(matchingTrack(TrackIdentity(null, "und", true, false), listOf(forced)))
        assertNull(matchingTrack(TrackIdentity("中文", "zh", false), listOf(track("4", null, "ass"))))
        assertEquals("1", matchingTrack(TrackIdentity("中文", "zh", false), listOf(ordinary)).str("id"))
        assertEquals("4", matchingTrack(TrackIdentity("中文", "zh"), listOf(track("4", null, "unknown"))).str("id"))
        val picks = mutableListOf<String?>()
        val controller = PlayerController("auto") { cmd, args ->
            if (cmd == "player.setTrack") picks += args.str("id")
            JsonObject(emptyMap())
        }
        controller.tryFallback(decoderError, state.copy(subOff = false, subtitle = choice))
        controller.started()
        controller.restoreTracks(listOf(ordinary))
        assertTrue(picks.isEmpty())
        controller.restoreTracks(listOf(ordinary, forced, bitmap))
        controller.restoreTracks(listOf(forced))
        assertEquals(listOf("2"), picks)
    }

    @Test fun 真实选中Format快照保留语义而不从缺位或标题猜测() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val controller = PlayerController("auto") { _, _ -> JsonObject(emptyMap()) }
        try {
            for ((mime, bitmap) in listOf(
                MimeTypes.APPLICATION_PGS to true, MimeTypes.APPLICATION_VOBSUB to true,
                MimeTypes.APPLICATION_DVBSUBS to true, MimeTypes.TEXT_SSA to false,
                MimeTypes.APPLICATION_SUBRIP to false, MimeTypes.TEXT_VTT to false,
                "application/unknown-subtitle" to null, null to null,
            )) {
                for (flags in listOf(0, C.SELECTION_FLAG_FORCED)) {
                    val format = Format.Builder().setLabel("forced SDH").setLanguage("zh")
                        .setSampleMimeType(mime).setSelectionFlags(flags).setRoleFlags(C.ROLE_FLAG_CAPTION).build()
                    // 字幕组保留已知类型，选中的Format本身可缺失MIME。
                    val group = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build(), format)
                    val info: Any = ReflectionHelpers.getField(player, "playbackInfo")
                    val selector: Any = ReflectionHelpers.getField(info, "trackSelectorResult")
                    val tracks = Tracks(listOf(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(false, true))))
                    ReflectionHelpers.setField(selector, "tracks", tracks)
                    val identity = controller.snapshot(player, false).subtitle
                    assertEquals(TrackIdentity("forced SDH", "zh", if (flags == 0) null else true, bitmap), identity)
                }
            }
        } finally { player.release() }
    }

}
