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
                mkv.writeBytes(javaClass.getResourceAsStream("/subtitles/embedded.mkv")!!.use { it.readBytes() })
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
