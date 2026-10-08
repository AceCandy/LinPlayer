package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.SeekTiming
import xyz.linplayer.app.ui.player.SeekSampleSource
import xyz.linplayer.app.ui.player.ObserveSeekTiming

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SeekTimingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun 生产拥有者后台中断并在离页撤销控制器接线() {
        org.robolectric.shadows.ShadowLog.clear()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val visible = mutableStateOf(true)
        val controller = PlayerController("mpv") { _, _ -> JsonObject(emptyMap()) }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) ObserveSeekTiming(controller)
            }
        }
        lateinit var original: SeekTiming
        rule.runOnIdle {
            original = controller.seekTiming!!
            runBlocking { controller.seek(50.0) }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            controller.observePosition(50.0, false, false)
            controller.observePosition(50.5, false, false)
        }
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").any { it.msg.startsWith("phase=seek_background") }
        }
        rule.runOnIdle { visible.value = false }
        rule.runOnIdle {
            assertNull(controller.seekTiming)
            original.sample(50.75, false, false, SeekSampleSource.PAGE)
            assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").none { it.msg.startsWith("phase=seek_clock_advanced") })
        }
    }
    @Test fun 提交和目标时钟推进分开且暂停缓冲不能冒充恢复() {
        var now = 0L
        val logs = mutableListOf<String>()
        val timing = SeekTiming({ now }, logs::add)
        timing.request(1, 50.0, "exo")
        now = 100
        timing.sample(50.0, false, false, SeekSampleSource.PAGE)
        assertEquals(1, logs.size)
        timing.submitted(1)
        now = 200
        timing.sample(40.0, false, false, SeekSampleSource.PAGE)
        timing.sample(50.0, true, false, SeekSampleSource.PAGE)
        assertEquals(2, logs.size)
        now = 300
        timing.sample(50.0, false, false, SeekSampleSource.PAGE)
        timing.sample(50.1, false, false, SeekSampleSource.SERVICE)
        assertEquals(3, logs.size)
        now = 600
        timing.sample(50.5, true, false, SeekSampleSource.PAGE)
        timing.sample(50.5, false, false, SeekSampleSource.PAGE)
        assertEquals(3, logs.size)
        now = 900
        timing.sample(50.75, false, false, SeekSampleSource.SERVICE)
        timing.sample(51.0, false, false, SeekSampleSource.PAGE)
        assertEquals(4, logs.size)
        assertTrue(logs[1].contains("phase=seek_submitted") && logs[1].contains("elapsed_ms=100"))
        assertTrue(logs[2].contains("phase=seek_target_observed") && logs[2].contains("elapsed_ms=300"))
        assertTrue(logs[3].contains("phase=seek_clock_advanced") && logs[3].endsWith("frame_verified=false"))
        timing.request(2, 70.0, "mpv"); timing.submitted(2)
        timing.sample(70.0, false, true, SeekSampleSource.PAGE)
        timing.sample(71.0, false, false, SeekSampleSource.PAGE)
        assertTrue(logs.last().contains("paused=true"))
        assertEquals(1, logs.count { "seek_clock_advanced" in it })
    }

    @Test fun 超时后台失败和旧修订拒绝迟到事件() {
        var now = 0L
        val logs = mutableListOf<String>()
        val timing = SeekTiming({ now }, logs::add)
        timing.request(1, 10.0, "mpv")
        timing.request(2, 20.0, "mpv")
        timing.submitted(1); timing.failed(1, false)
        assertTrue(logs.any { "seek_superseded" in it })
        assertTrue(logs.none { "seek_submitted" in it || "seek_failed" in it })
        timing.submitted(2)
        timing.sample(20.0, false, false, SeekSampleSource.PAGE)
        now = 15_000
        timing.expire()
        timing.sample(20.5, false, false, SeekSampleSource.PAGE)
        assertTrue(logs.last().contains("seek_timeout"))
        timing.request(3, 30.0, "mpv"); timing.submitted(3)
        timing.setActive(false); timing.sample(30.5, false, false, SeekSampleSource.PAGE)
        val count = logs.size
        timing.request(4, 40.0, "mpv")
        assertEquals(count, logs.size)
        timing.setActive(true); timing.request(5, 50.0, "mpv"); timing.failed(5, true)
        assertTrue(logs.last().contains("seek_cancelled"))
        assertTrue(logs.none { "seek_clock_advanced" in it })
    }

    @Test fun 共用控制器连按挂起只让最新回执完成且UI目标清除后仍测时钟() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var now = 0L
        val logs = mutableListOf<String>()
        val controller = PlayerController("mpv") { _, _ ->
            if (++calls == 1) release.await()
            JsonObject(emptyMap())
        }
        controller.seekTiming = SeekTiming({ now }, logs::add)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.seek(50.0) }
        now = 100
        val second = launch(start = CoroutineStart.UNDISPATCHED) { controller.seek(70.0) }
        controller.observePosition(70.0, false, false)
        assertEquals(0, logs.count { "seek_target_observed" in it })
        release.complete(Unit); first.join(); second.join()
        assertEquals(1, logs.count { "seek_submitted" in it })
        controller.observePosition(50.0, false, false)
        assertNotNull(controller.seekTarget)
        now = 300
        controller.observePosition(70.0, false, false)
        assertNull(controller.seekTarget)
        now = 600
        controller.observePosition(70.25, false, false)
        assertTrue(logs.last().contains("seek_clock_advanced"))
        controller.observePosition(70.5, false, false)
        assertEquals(1, logs.count { "seek_clock_advanced" in it })
    }

    @Test fun 控制器停止与命令失败终止测量而不释放在途屏障() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val controller = PlayerController("mpv") { command, _ ->
            commands += command
            if (command == "player.seek") release.await()
            JsonObject(emptyMap())
        }
        controller.seekTiming = SeekTiming({ 0L }, logs::add)
        val seek = launch(start = CoroutineStart.UNDISPATCHED) { controller.seek(50.0) }
        val stop = controller.stopIn(this, 0.0, { throw it })
        assertEquals(listOf("player.seek"), commands)
        assertTrue(logs.last().contains("seek_interrupted"))
        release.complete(Unit); seek.join(); stop.join()
        controller.observePosition(50.5, false, false)
        assertTrue(logs.none { "seek_submitted" in it || "seek_clock_advanced" in it })
        assertEquals(listOf("player.seek", "player.stopPlayback"), commands)
        val failing = PlayerController("mpv") { _, _ -> error("命令失败") }
        failing.seekTiming = SeekTiming({ 0L }, logs::add)
        runCatching { failing.seek(70.0) }
        assertTrue(logs.last().contains("seek_failed"))
        assertTrue(logs.none { "命令失败" in it })
    }
}
