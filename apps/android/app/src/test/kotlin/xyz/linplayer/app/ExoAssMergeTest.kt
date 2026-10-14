package xyz.linplayer.app

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.LibassParserFactory
import xyz.linplayer.app.ui.player.exoMediaItem
import xyz.linplayer.app.ui.player.syncLibassTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [ShadowAssNative::class],
    instrumentedPackages = ["xyz.linplayer.app.core"])
class ExoAssMergeTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun 真实外挂合并后所选脚本能打开且切轨关闭不串轨() {
        Libass.reset()
        ShadowAssNative.opened.clear()
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setSubtitleParserFactory(LibassParserFactory("/system/fonts")),
        ).build()
        var off = false
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { syncLibassTrack(player, tracks, off) }
        })
        try {
            // 合成 PCM 主源仅用于驱动真实解封装和 MergingMediaPeriod，无设备解码依赖。
            val wav = files.newFile("main.wav")
            val pcmSize = 16000
            wav.writeBytes(ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcmSize); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
                putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmSize)
            }.array())
            val scripts = listOf("first", "second").map { name ->
                files.newFile("$name.ass").apply {
                    writeText("[Script Info]\nScriptType: v4.00+\n[Events]\nDialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,$name")
                }
            }
            val subs = Json.parseToJsonElement(scripts.mapIndexed { i, file ->
                """{"url":"${file.toURI()}","mime_type":"text/x-ssa","is_default":${i == 0}}"""
            }.joinToString(",", "[", "]"))
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            val item = exoMediaItem(wav.toURI().toString(), subs)
            player.setMediaItem(item)
            player.prepare()
            await(player) { player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT } == 2 }
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            assertEquals("1:" + item.localConfiguration!!.subtitleConfigurations[0].id,
                groups[0].getTrackFormat(0).id)
            assertTrue(groups[0].isSelected)
            await(player) { ShadowAssNative.opened.isNotEmpty() }
            assertEquals("file:" + scripts[0].readText(), ShadowAssNative.opened.last())
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(groups[1].mediaTrackGroup, 0)).build()
            await(player) { ShadowAssNative.opened.lastOrNull() == "file:" + scripts[1].readText() }
            off = true
            syncLibassTrack(player, player.currentTracks, off)
            assertFalse(Libass.isActive)
            Libass.file(item.localConfiguration!!.subtitleConfigurations[1].id!!, "late".toByteArray())
            assertFalse(Libass.isActive)
        } finally {
            player.release()
            Libass.reset()
        }
    }

    @Test fun 真实内封轨在有无外挂时均能打开解析器注册的头() {
        for (merged in listOf(false, true)) {
            Libass.reset()
            ShadowAssNative.opened.clear()
            val context = RuntimeEnvironment.getApplication()
            val player = ExoPlayer.Builder(context).setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setSubtitleParserFactory(LibassParserFactory("/system/fonts")),
            ).build()
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) { syncLibassTrack(player, tracks, false) }
            })
            try {
                val mkv = files.newFile("embedded-$merged.mkv")
                mkv.writeBytes(this@ExoAssMergeTest.javaClass.getResourceAsStream("/subtitles/embedded.mkv")!!.use { it.readBytes() })
                val ass = files.newFile("external-$merged.ass")
                ass.writeBytes(javaClass.getResourceAsStream("/subtitles/embedded.ass")!!.use { it.readBytes() })
                val subs = if (merged) Json.parseToJsonElement(
                    """[{"url":"${ass.toURI()}","mime_type":"text/x-ssa"}]""",
                ) else null
                player.setMediaItem(exoMediaItem(mkv.toURI().toString(), subs))
                player.prepare()
                await(player) { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT } }
                val embedded = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_TEXT }
                assertEquals(merged, embedded.getTrackFormat(0).id!!.startsWith("0:"))
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(embedded.mediaTrackGroup, 0)).build()
                await(player) { Libass.isActive }
                assertTrue(ShadowAssNative.opened.last().startsWith("[Script Info]"))
                assertTrue(ShadowAssNative.opened.last().contains("Style: Default"))
                assertFalse(ShadowAssNative.opened.last().startsWith("file:"))
            } finally { player.release(); Libass.reset() }
        }
    }

    @Test fun 退出后迟到的预加载字幕不能重开旧轨() {
        Libass.reset()
        val subtitles = xyz.linplayer.app.ui.player.PreparedSubtitles()
        subtitles.activate()
        subtitles.deactivate()
        Libass.reset()
        Libass.activateTrack("old", "/system/fonts")
        subtitles.file("old", "late".toByteArray())
        subtitles.header("old", "late".toByteArray())
        assertFalse(Libass.isActive)
        Libass.reset()
    }

    @Test fun 预加载不污染当前字幕且正式接管保留内封头和外挂脚本() {
        Libass.reset()
        ShadowAssNative.opened.clear()
        val context = RuntimeEnvironment.getApplication()
        val owner = xyz.linplayer.app.ui.player.ExoPreload(context,
            androidx.media3.datasource.DefaultDataSource.Factory(context),
            xyz.linplayer.app.ui.player.playbackLoadControl(0), "/system/fonts")
        val player = owner.player
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { syncLibassTrack(player, tracks, false) }
        })
        try {
            val mkv = files.newFile("prepared.mkv")
            mkv.writeBytes(this@ExoAssMergeTest.javaClass.getResourceAsStream("/subtitles/embedded.mkv")!!.use { it.readBytes() })
            val ass = files.newFile("prepared.ass")
            ass.writeBytes(javaClass.getResourceAsStream("/subtitles/embedded.ass")!!.use { it.readBytes() })
            val subs = Json.parseToJsonElement("""[{"url":"${ass.toURI()}","mime_type":"text/x-ssa"}]""")
            val manager = org.robolectric.util.ReflectionHelpers.getField<androidx.media3.exoplayer.source.preload.DefaultPreloadManager>(owner, "manager")
            var complete = false
            var error: Exception? = null
            manager.addListener(object : androidx.media3.exoplayer.source.preload.PreloadManagerListener {
                override fun onCompleted(item: androidx.media3.common.MediaItem) { complete = true }
                override fun onError(exception: androidx.media3.exoplayer.source.preload.PreloadException) { error = exception }
            })
            owner.prepare(mkv.toURI().toString(), subs)
            await(player) { error?.let { throw it }; complete }
            assertTrue("预加载字幕不得打开当前渲染轨", ShadowAssNative.opened.isEmpty())
            assertTrue(player.currentMediaItem == null)
            Libass.reset()
            assertTrue("必须接管已加载的MediaSource", owner.load(mkv.toURI().toString(), subs))
            player.prepare()
            await(player) { player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT } == 2 }
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(groups[0].mediaTrackGroup, 0)).build()
            await(player) { Libass.isActive }
            assertTrue(ShadowAssNative.opened.last().contains("Style: Default"))
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(groups[1].mediaTrackGroup, 0)).build()
            await(player) { ShadowAssNative.opened.lastOrNull() == "file:" + ass.readText() }
            assertFalse("旧地址不能再次消费", owner.load("file:///another.mkv", null))
        } finally { player.release(); owner.release(); Libass.reset() }
    }

    @Test fun 真实预载源追加队列切换后不重载且清理旧队列() {
        Libass.reset()
        val owner = xyz.linplayer.app.ui.player.ExoPreload(RuntimeEnvironment.getApplication(),
            androidx.media3.datasource.DefaultDataSource.Factory(RuntimeEnvironment.getApplication()),
            xyz.linplayer.app.ui.player.playbackLoadControl(0), "/system/fonts")
        val player = owner.player
        try {
            val media = listOf("current", "next").map { name -> files.newFile("$name.mkv").apply {
                writeBytes(this@ExoAssMergeTest.javaClass.getResourceAsStream("/subtitles/embedded.mkv")!!.use { it.readBytes() })
            } }
            player.setMediaItem(exoMediaItem(media[0].toURI().toString(), null))
            player.prepare()
            await(player) { player.currentTracks.groups.isNotEmpty() }
            val manager = org.robolectric.util.ReflectionHelpers.getField<androidx.media3.exoplayer.source.preload.DefaultPreloadManager>(owner, "manager")
            var complete = false
            manager.addListener(object : androidx.media3.exoplayer.source.preload.PreloadManagerListener {
                override fun onCompleted(item: androidx.media3.common.MediaItem) { complete = true }
            })
            owner.prepare(media[1].toURI().toString(), null)
            await(player) { complete }
            var transitions = 0
            assertTrue(owner.queue(media[1].toURI().toString(), null) { transitions++ })
            assertEquals(2, player.mediaItemCount)
            assertEquals(0, transitions)
            // 经过真实Media3队列切换；设备解码与片尾时序另行验收。
            player.seekToNextMediaItem()
            await(player) { transitions == 1 && player.currentTracks.groups.isNotEmpty() }
            assertEquals(media[1].toURI().toString(), player.currentMediaItem!!.localConfiguration!!.uri.toString())
            owner.trimPrevious()
            assertEquals(1, player.mediaItemCount)
            owner.cancel()
            assertEquals(1, transitions)
            assertNotEquals(Player.STATE_IDLE, player.playbackState)
        } finally { player.release(); owner.release(); Libass.reset() }
    }

    private fun await(player: ExoPlayer, condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            player.playerError?.let { throw AssertionError("真实媒体加载失败", it) }
            Thread.sleep(10)
        }
        assertTrue("真实Media3链路未到达预期状态", condition())
    }
}
