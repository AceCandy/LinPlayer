package xyz.linplayer.app

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.ui.player.PlayerController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceTest {
    @Test fun 通知暂停继续与媒体会话跳转控制当前内核() {
        for (media3 in listOf(false, true)) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val scope = CoroutineScope(Dispatchers.Main)
            val core = FakeCore().apply {
                ret("player.status", buildJsonObject {
                    put("position", 37.5); put("duration", 100); put("paused", true)
                })
            }
            val app = AppState(core, scope)
            val ctx = RuntimeEnvironment.getApplication()
            val exo = if (media3) ExoPlayer.Builder(ctx).build() else null
            PlaybackService.start(ctx, app, exo, "测试影片")
            val controller = Robolectric.buildService(PlaybackService::class.java).create()
            try {
                val service = controller.get()
                service.onStartCommand(Intent(ctx, PlaybackService::class.java).setAction("playback.play"), 0, 1)
                if (exo != null) assertTrue(exo.playWhenReady)
                else assertEquals("false", core.calls.last { it.first == "player.setPause" }.second!!["paused"].toString())
                service.onStartCommand(Intent(ctx, PlaybackService::class.java).setAction("playback.pause"), 0, 2)
                if (exo != null) assertFalse(exo.playWhenReady)
                else assertEquals("true", core.calls.last { it.first == "player.setPause" }.second!!["paused"].toString())
                val field = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
                val session = field.get(service) as MediaSessionCompat
                // Robolectric 不派发系统 transportControls；调用真实登记的回调验证内核分派。
                val implField = MediaSessionCompat::class.java.getDeclaredField("mImpl").apply { isAccessible = true }
                val impl = implField.get(session)
                val callback = impl.javaClass.getMethod("getCallback").apply { isAccessible = true }
                    .invoke(impl) as MediaSessionCompat.Callback
                callback.onSeekTo(42000)
                if (exo != null) {
                    assertEquals(42000L, exo.currentPosition)
                    assertTrue(core.calls.none { it.first == "player.setPause" || it.first == "player.seek" })
                } else assertEquals(42.0, core.calls.single { it.first == "player.seek" }.second.dbl("pos")!!, 0.0)
            } finally {
                controller.destroy(); exo?.release()
                app.bg.cancel(); scope.cancel(); Dispatchers.resetMain()
            }
        }
    }

    @Test fun 短暂失焦后页面暂停不会被恢复() = withPlayingService { service, core ->
        focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        PlaybackService.onUserPause(true)
        runBlocking { PlayerController("mpv") { cmd, args -> core.callJson(cmd, args) }.pause(true) }
        core.calls.clear()
        focus(service, AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, core.calls.count { it.first == "player.setPause" })
    }

    @Test fun 短暂失焦未手动暂停时恢复播放() = withPlayingService { service, core ->
        focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        core.calls.clear()
        focus(service, AudioManager.AUDIOFOCUS_GAIN)
        val resume = core.calls.single { it.first == "player.setPause" }
        assertEquals(false, resume.second!!["paused"]!!.toString().toBoolean())
    }

    @Test fun 降音量期间页面调音量不会被旧值覆盖() = withPlayingService { service, core ->
        var actualVolume = 100.0
        core.on("player.setVolume") { values ->
            actualVolume = values.dbl("volume")!!
            JsonPrimitive(true)
        }
        focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(30.0, actualVolume, 0.0)
        PlaybackService.onUserVolume()
        runBlocking { PlayerController("mpv") { cmd, args -> core.callJson(cmd, args) }.volume(.6f) }
        core.calls.clear()
        focus(service, AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, core.calls.count { it.first == "player.setVolume" })
        assertEquals(60.0, actualVolume, 0.0)
    }

    @Test fun 降音量未手动调节时恢复原音量() = withPlayingService { service, core ->
        focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(30.0, core.calls.last { it.first == "player.setVolume" }.second.dbl("volume")!!, 0.0)
        core.calls.clear()
        focus(service, AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(100.0, core.calls.single { it.first == "player.setVolume" }.second.dbl("volume")!!, 0.0)
    }

    private fun focus(service: PlaybackService, change: Int) {
        val field = PlaybackService::class.java.getDeclaredField("focusListener").apply { isAccessible = true }
        (field.get(service) as AudioManager.OnAudioFocusChangeListener).onAudioFocusChange(change)
    }

    private fun withPlayingService(check: (PlaybackService, FakeCore) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(Dispatchers.Main)
        val core = FakeCore().apply {
            ret("player.setPause", JsonPrimitive(true))
            ret("player.setVolume", JsonPrimitive(true))
            ret("player.status", buildJsonObject {
                put("position", 37.5); put("duration", 100); put("paused", false); put("volume", 100)
            })
        }
        val app = AppState(core, scope)
        val ctx = RuntimeEnvironment.getApplication()
        PlaybackService.start(ctx, app, null, "测试影片")
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try { check(controller.get(), core) }
        finally {
            controller.destroy()
            app.bg.cancel(); scope.cancel(); Dispatchers.resetMain()
        }
    }

    @Test fun 通知停止使用当前进度并调用核心停播() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(Dispatchers.Main)
        val core = FakeCore().apply {
            ret("player.status", buildJsonObject {
                put("position", 37.5); put("duration", 100); put("paused", true)
            })
            ret("player.stopPlayback", JsonPrimitive(true))
        }
        val app = AppState(core, scope)
        val ctx = RuntimeEnvironment.getApplication()
        PlaybackService.start(ctx, app, null, "测试影片")
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(ctx, PlaybackService::class.java).setAction("playback.stop"), 0, 1)
            val stop = core.calls.single { it.first == "player.stopPlayback" }
            assertEquals(37.5, stop.second.dbl("pos")!!, 0.0)
        } finally {
            controller.destroy()
            app.bg.cancel(); scope.cancel(); Dispatchers.resetMain()
        }
    }
}
