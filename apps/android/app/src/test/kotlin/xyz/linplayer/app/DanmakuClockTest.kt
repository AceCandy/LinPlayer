package xyz.linplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import xyz.linplayer.app.ui.player.DmClock
import xyz.linplayer.app.ui.player.dmRollX

class DanmakuClockTest {
    @Test fun sparsePlaybackSamplesKeepUniformMotionAtDifferentRefreshRatesAndSpeeds() {
        for (hz in listOf(60, 90, 120)) for (speed in listOf(0.5, 1.0, 2.0, 3.0)) {
            val clock = DmClock(10.0)
            val dt = 1.0 / hz
            val distance = (1920.0 + 400.0) / 8.0 * dt * speed
            var lastX = dmRollX(1920.0, 400.0, clock.now - 10.0, 8.0)
            repeat(hz * 3) { index ->
                // 真实播放连续推进，但页面仅每250ms收到一次进度。
                val sample = 10.0 + ((index + 1) * 4 / hz) * 0.25 * speed
                val now = clock.tick(sample, dt, speed)
                val x = dmRollX(1920.0, 400.0, now - 10.0, 8.0)
                val moved = lastX - x
                assertTrue("${hz}Hz ${speed}x frame=$index moved=$moved expected=$distance",
                    moved in distance * 0.7..distance * 1.3)
                lastX = x
            }
            assertEquals(10.0 + 3.0 * speed, clock.now, dt * speed * 2)
        }
    }

    @Test fun stoppedClockTracksSeekAndResumesWithoutAccumulatingStoppedTime() {
        val clock = DmClock(10.0)
        repeat(60) { clock.tick(10.0, 1.0 / 60, 1.0) }
        repeat(120) { assertEquals(11.0, clock.tick(11.0, 1.0 / 60, 1.0, stopped = true), 0.0) }
        assertEquals(50.0, clock.tick(50.0, 0.0, 1.0, stopped = true), 0.0)
        assertEquals(50.0 + 1.0 / 60, clock.tick(50.0, 1.0 / 60, 1.0), 1e-9)
    }

    @Test fun largeSeeksAlignImmediatelyInBothDirections() {
        val clock = DmClock(10.0)
        assertEquals(50.0, clock.tick(50.0, 1.0 / 60, 1.0), 0.0)
        assertEquals(5.0, clock.tick(5.0, 1.0 / 60, 1.0), 0.0)
    }

    @Test fun videoFrameQuantizedSamplesDoNotCauseHighRefreshSpeedPulses() {
        for (hz in listOf(60, 90, 120)) {
            val clock = DmClock(10.0)
            val dt = 1.0 / hz
            repeat(hz * 3) { index ->
                val sampleTime = ((index + 1) * 4 / hz) * 0.25
                val sample = 10.0 + floor(sampleTime * 23.976) / 23.976
                val before = clock.now
                val moved = clock.tick(sample, dt, 1.0) - before
                assertTrue("${hz}Hz frame=$index moved=$moved expected=$dt",
                    moved in dt * 0.7..dt * 1.3)
            }
        }
    }
}
