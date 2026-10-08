package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.ui.player.Media3DiagnosticObserver
import xyz.linplayer.app.ui.player.PlaybackDiagnostic
import xyz.linplayer.app.ui.player.TrackPrefs
import xyz.linplayer.app.ui.player.rememberExoPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Media3DiagnosticsTest {
    @get:Rule val rule = createComposeRule()

    // 从真实播放器已登记的 listener 集取观察器，避免仅测试一个未接线的独立对象。
    private fun observers(player: ExoPlayer): List<Media3DiagnosticObserver> {
        val set = ReflectionHelpers.getField<Any>(player, "listeners")
        val holders = ReflectionHelpers.getField<Set<Any>>(set, "listeners")
        return holders.map { ReflectionHelpers.getField<Any>(it, "listener") }.filterIsInstance<Media3DiagnosticObserver>()
    }

    @Test fun 观察器先于页面起播并在偏好重组后保持唯一且离页撤销() {
        ShadowLog.clear()
        val enabled = mutableStateOf(true)
        val prefs = mutableStateOf<TrackPrefs?>(null)
        var player: ExoPlayer? = null
        rule.setContent {
            player = rememberExoPlayer(enabled.value, prefs.value)
            val current = player
            LaunchedEffect(current) { current?.setMediaItem(MediaItem.fromUri("asset:///diagnostic-one.mp4")) }
        }
        lateinit var original: ExoPlayer
        lateinit var observer: Media3DiagnosticObserver
        rule.runOnIdle {
            original = player!!
            observer = observers(original).single()
            // 忠实注入解码器事件；开始计时必须来自上面真实 setMediaItem 的 transition。
            observer.onPlaybackStateChanged(Player.STATE_READY)
            observer.onRenderedFirstFrame()
            observer.onRenderedFirstFrame()
            prefs.value = TrackPrefs(subEnabled = false)
        }
        rule.waitUntil(5_000) { ShadowLog.getLogsForTag("lp-playback").size == 2 }
        rule.runOnIdle {
            assertSame(observer, observers(original).single())
            val logs = ShadowLog.getLogsForTag("lp-playback").map { it.msg }
            assertTrue(logs.any { it.startsWith("phase=ready media_elapsed_ms=") })
            assertTrue(logs.any { it.startsWith("phase=first_frame media_elapsed_ms=") })
            assertTrue(logs.none { "diagnostic-one" in it || "asset:" in it })
            enabled.value = false
        }
        rule.runOnIdle {
            assertTrue(original.isReleased)
            assertTrue(observers(original).isEmpty())
            observer.onRenderedFirstFrame()
        }
    }

    @Test fun 同实例换片重开阶段计时且停止之后拒绝迟到首帧() {
        var player: ExoPlayer? = null
        rule.setContent { player = rememberExoPlayer(true, null) }
        rule.runOnIdle {
            val p = player!!
            var now = 100L
            val events = mutableListOf<Pair<PlaybackDiagnostic, Long?>>()
            val observer = Media3DiagnosticObserver(p, { now }) { kind, elapsed, _ -> events += kind to elapsed }
            p.addListener(observer)
            p.setMediaItem(MediaItem.fromUri("asset:///one.mp4"))
            now = 600
            observer.onPlaybackStateChanged(Player.STATE_READY)
            now = 1_100
            observer.onRenderedFirstFrame()
            p.setMediaItem(MediaItem.fromUri("asset:///two.mp4"))
            now = 1_300
            observer.onRenderedFirstFrame()
            assertEquals(listOf(PlaybackDiagnostic.READY to 500L, PlaybackDiagnostic.FIRST_FRAME to 1_000L,
                PlaybackDiagnostic.FIRST_FRAME to 200L), events)
            observer.onPlaybackStateChanged(Player.STATE_IDLE)
            observer.onRenderedFirstFrame()
            assertEquals(3, events.size)
            p.removeListener(observer)
        }
    }

    @Test fun 真实缓冲采样在后台清窗口且停止后不再报告() {
        var player: ExoPlayer? = null
        rule.setContent { player = rememberExoPlayer(true, null) }
        rule.runOnIdle {
            val p = player!!
            var now = 0L
            val events = mutableListOf<PlaybackDiagnostic>()
            val observer = Media3DiagnosticObserver(p, { now }) { kind, _, _ -> events += kind }
            p.addListener(observer)
            p.setMediaItem(MediaItem.fromUri("asset:///waiting-data.mp4"))
            p.prepare()
            p.playWhenReady = true
            assertEquals(Player.STATE_BUFFERING, p.playbackState)
            observer.sample(true)
            now = 12_000
            observer.sample(false)
            assertTrue(events.isEmpty())
            now = 13_000
            observer.sample(true)
            now = 25_000
            observer.sample(true)
            assertEquals(listOf(PlaybackDiagnostic.WAITING_DATA), events)
            p.stop()
            now = 50_000
            observer.sample(true)
            observer.onRenderedFirstFrame()
            assertEquals(1, events.size)
            p.removeListener(observer)
        }
    }
}
