package xyz.linplayer.app

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.player.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SessionTrackMemoryTest {
    private val scope = SessionTrackScope("server-a", "user-a", "series-a")
    private fun track(id: String, kind: String = "sub", title: String = "中文") = buildJsonObject {
        put("id", id); put("kind", kind); put("title", title); put("lang", "zho")
        put("codec", if (kind == "audio") "aac" else "subrip"); put("forced", false)
    }

    @Test fun 成功手选在同剧新控制器恢复且仅提交一次() = runBlocking {
        val memory = SessionTrackMemory()
        val source = PlayerController("mpv") { _, _ -> JsonObject(emptyMap()) }
        source.seriesContext(memory, scope); source.observeTracks(listOf(track("1")))
        source.pickTrack("sub", "1")
        val ids = mutableListOf<String?>()
        val next = PlayerController("mpv") { cmd, args ->
            if (cmd == "player.setTrack") ids += args.str("id")
            JsonObject(emptyMap())
        }
        next.started(); next.seriesContext(memory, scope)
        assertEquals(false, next.restoreSessionTracks(null, listOf(track("9"))))
        next.restoreSessionTracks(null, listOf(track("9")))
        assertEquals(listOf("9"), ids)
    }

    @Test fun 换剧换账号换服未知上下文及新页面都不沿用() = runBlocking {
        for (nextScope in listOf(scope.copy(seriesId = "other"), scope.copy(userId = "other"), scope.copy(server = "other"), null)) {
            val memory = SessionTrackMemory(); memory.enter(scope)
            memory.record(scope, "sub", SessionTrackChoice(off = true))
            val next = PlayerController("mpv") { _, _ -> fail("不同作用域不能恢复"); JsonObject(emptyMap()) }
            next.started(); next.seriesContext(memory, nextScope)
            assertNull(next.restoreSessionTracks(null, listOf(track("2"))))
            assertTrue(memory.choicesFor(scope).isEmpty())
        }
        assertTrue(SessionTrackMemory().choicesFor(scope).isEmpty())
    }

    @Test fun 关闭字幕无需轨表且当前手选和详情预选优先() = runBlocking {
        for (manual in listOf(false, true)) {
            val memory = SessionTrackMemory(); memory.enter(scope)
            memory.record(scope, "sub", SessionTrackChoice(off = true))
            val ids = mutableListOf<String?>()
            val next = PlayerController("mpv") { cmd, args -> if (cmd == "player.setTrack") ids += args.str("id"); JsonObject(emptyMap()) }
            next.started(); next.seriesContext(memory, scope)
            assertNull(next.restoreSessionTracks(null, emptyList(), setOf("sub")))
            if (manual) next.pickTrack("sub", "4")
            next.restoreSessionTracks(null, emptyList())
            assertEquals(if (manual) listOf("4") else listOf(""), ids)
        }
    }

    @Test fun 歧义缺轨不恢复且晚到唯一候选可以恢复() = runBlocking {
        val memory = SessionTrackMemory(); memory.enter(scope)
        memory.record(scope, "sub", SessionTrackChoice(TrackIdentity("中文", "zh", bitmap = false)))
        val ids = mutableListOf<String?>()
        val next = PlayerController("mpv") { cmd, args -> if (cmd == "player.setTrack") ids += args.str("id"); JsonObject(emptyMap()) }
        next.started(); next.seriesContext(memory, scope)
        next.restoreSessionTracks(null, emptyList())
        next.restoreSessionTracks(null, listOf(track("2"), track("3")))
        assertTrue(ids.isEmpty())
        next.restoreSessionTracks(null, listOf(track("3")))
        assertEquals(listOf("3"), ids)
    }

    @Test fun 失败手选不写记忆且详情晚到补齐成功手选上下文() = runBlocking {
        val memory = SessionTrackMemory()
        val failure = PlayerController("mpv") { _, _ -> error("选择失败") }
        failure.seriesContext(memory, scope); failure.observeTracks(listOf(track("1")))
        try { failure.pickTrack("sub", "1"); fail("应失败") } catch (_: IllegalStateException) { }
        assertTrue(memory.choicesFor(scope).isEmpty())
        val success = PlayerController("mpv") { _, _ -> JsonObject(emptyMap()) }
        success.observeTracks(listOf(track("1"))); success.pickTrack("sub", "1")
        success.seriesContext(memory, scope)
        assertEquals("中文", memory.choicesFor(scope)["sub"]?.identity?.title)
    }

    @Test fun 切账号重绑记忆不带入旧控制器手选() = runBlocking {
        val old = SessionTrackMemory()
        val controller = PlayerController("mpv") { _, _ -> JsonObject(emptyMap()) }
        controller.seriesContext(old, scope); controller.pickTrack("sub", "")
        val fresh = SessionTrackMemory()
        controller.clearSeriesContext(fresh)
        controller.seriesContext(fresh, scope.copy(userId = "other"))
        assertTrue(fresh.choicesFor(scope.copy(userId = "other")).isEmpty())
        assertNull(controller.manualSubtitleOff)
    }

    @Test fun 只从当前分集及非空账号上下文取得记忆作用域() {
        val session = xyz.linplayer.app.data.Session("server-a", "", "user-a", "")
        val detail = buildJsonObject { put("id", "episode-a"); put("type_", "Episode"); put("series_id", "series-a") }
        assertEquals(scope, sessionTrackScope(session, detail, "episode-a"))
        assertNull(sessionTrackScope(session, detail, "old-episode"))
        assertNull(sessionTrackScope(session.copy(userId = ""), detail, "episode-a"))
        assertNull(sessionTrackScope(session, buildJsonObject { put("id", "movie"); put("type_", "Movie"); put("series_id", "series-a") }, "movie"))
    }

    @Test fun MPV音轨身份不携带字幕强制标志且可在Media3恢复() = runBlocking {
        val memory = SessionTrackMemory()
        val first = PlayerController("mpv") { _, _ -> JsonObject(emptyMap()) }
        first.seriesContext(memory, scope); first.observeTracks(listOf(track("1", "audio"))); first.pickTrack("audio", "1")
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            setTracks(player, listOf(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLabel("中文").setLanguage("zh").build()))
            val next = PlayerController("exo") { _, _ -> JsonObject(emptyMap()) }
            next.started(); next.seriesContext(memory, scope)
            next.restoreSessionTracks(player, emptyList())
            assertEquals(listOf(0), player.trackSelectionParameters.overrides.values.single().trackIndices)
        } finally { player.release() }
    }

    @Test fun 两次切账号取消旧详情且旧选轨回执不能写入新记忆() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val controller = PlayerController("mpv") { _, args ->
            if (args.str("id") == "1") release.await()
            JsonObject(emptyMap())
        }
        val old = SessionTrackMemory()
        controller.seriesContext(old, scope); controller.pickTrack("sub", "")
        controller.observeTracks(listOf(track("1")))
        val picking = launch(start = CoroutineStart.UNDISPATCHED) { controller.pickTrack("sub", "1") }
        val middle = SessionTrackMemory()
        val detail = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.clearSeriesContext(middle)
            CompletableDeferred<Unit>().await() // 旧账号详情在途挂起，取消不会撤销上面的同步解绑。
            controller.seriesContext(middle, scope.copy(userId = "middle"))
        }
        detail.cancel(); detail.join()
        val fresh = SessionTrackMemory()
        controller.clearSeriesContext(fresh)
        controller.seriesContext(fresh, scope.copy(userId = "new"))
        release.complete(Unit); picking.join()
        assertTrue(fresh.choicesFor(scope.copy(userId = "new")).isEmpty())
        assertTrue(middle.choicesFor(scope.copy(userId = "middle")).isEmpty())
        assertNull(controller.manualSubtitleOff)
    }

    private fun setTracks(player: ExoPlayer, formats: List<Format>) {
        val group = TrackGroup(*formats.toTypedArray())
        val info: Any = ReflectionHelpers.getField(player, "playbackInfo")
        val selector: Any = ReflectionHelpers.getField(info, "trackSelectorResult")
        ReflectionHelpers.setField(selector, "tracks", Tracks(listOf(Tracks.Group(group, false,
            IntArray(formats.size) { C.FORMAT_HANDLED }, BooleanArray(formats.size) { it == 0 }))))
    }

    @Test fun Media3点击Format在下一集恢复且不使用旧组索引() = runBlocking {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val chinese = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLabel("中文").setLanguage("zh").build()
            val english = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLabel("英文").setLanguage("en").build()
            val memory = SessionTrackMemory()
            val first = PlayerController("exo") { _, _ -> JsonObject(emptyMap()) }
            first.started(); first.seriesContext(memory, scope)
            setTracks(player, listOf(english, chinese)); first.pickExoTrack(player, "subtitle", "0:1")
            assertEquals("中文", memory.choicesFor(scope)["sub"]?.identity?.title)
            val next = PlayerController("exo") { _, _ -> fail("Media3不发MPV选轨"); JsonObject(emptyMap()) }
            next.started(); next.seriesContext(memory, scope)
            setTracks(player, listOf(chinese, english))
            assertEquals(false, next.restoreSessionTracks(player, emptyList()))
            assertEquals(listOf(0), player.trackSelectionParameters.overrides.values.single().trackIndices)
        } finally { player.release() }
    }

    @Test fun Media3关闭字幕记忆也能在MPV下一集恢复() = runBlocking {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val memory = SessionTrackMemory()
            val first = PlayerController("exo") { _, _ -> JsonObject(emptyMap()) }
            first.started(); first.seriesContext(memory, scope); first.pickExoTrack(player, "subtitle", "lp:off")
            val ids = mutableListOf<String?>()
            val next = PlayerController("mpv") { cmd, args -> if (cmd == "player.setTrack") ids += args.str("id"); JsonObject(emptyMap()) }
            next.started(); next.seriesContext(memory, scope)
            assertEquals(true, next.restoreSessionTracks(null, emptyList()))
            assertEquals(listOf(""), ids)
        } finally { player.release() }
    }
}
