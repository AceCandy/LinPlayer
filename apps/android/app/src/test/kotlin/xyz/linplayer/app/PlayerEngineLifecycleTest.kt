package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.rememberExoPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlayerEngineLifecycleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun 回退切换组合时释放旧Media3并保持未准备的续播状态() {
        val calls = mutableListOf<String>()
        val controller = PlayerController("auto") { command, _ -> calls += command; JsonObject(emptyMap()) }
        controller.resolved(buildJsonObject { put("resume_secs", 125.0) })
        var player: ExoPlayer? = null
        rule.setContent {
            player = rememberExoPlayer(controller.engine == "exo", null)
            SideEffect { controller.bind(player) }
        }
        lateinit var old: ExoPlayer
        rule.runOnIdle {
            old = player!!
            old.playWhenReady = false
            old.setPlaybackSpeed(1.5f)
            old.volume = 0.6f
            val snapshot = controller.snapshot(old, subOff = true)
            assertEquals(125.0, snapshot.position, 0.0)
            assertTrue(snapshot.paused)
            assertEquals(1.5, snapshot.speed, 0.0)
            assertTrue(snapshot.subOff)
            runBlocking {
                assertTrue(controller.tryFallback(PlaybackException("失败", null,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), snapshot))
            }
        }
        rule.runOnIdle {
            assertNull(player)
            assertTrue(old.isReleased)
            runBlocking { controller.restoreState() }
            assertEquals(listOf("player.stopPlayback", "player.setSpeed", "player.setVolume", "player.setPause", "player.setTrack"), calls)
        }
    }

    @Test fun 切换目标先停止旧Media3输出再收尾会话() {
        var player: ExoPlayer? = null
        var stopped = false
        val controller = PlayerController("exo") { command, _ ->
            assertEquals("player.stopPlayback", command)
            assertEquals(Player.STATE_IDLE, player!!.playbackState)
            stopped = true
            JsonObject(emptyMap())
        }
        rule.setContent {
            player = rememberExoPlayer(true, null)
            SideEffect { controller.bind(player) }
        }
        rule.runOnIdle {
            player!!.setMediaItem(MediaItem.fromUri("asset:///engine-test.mp4"))
            player!!.prepare()
            assertEquals(Player.STATE_BUFFERING, player!!.playbackState)
            runBlocking { controller.stop(10.0) }
            assertTrue(stopped)
        }
    }
}
