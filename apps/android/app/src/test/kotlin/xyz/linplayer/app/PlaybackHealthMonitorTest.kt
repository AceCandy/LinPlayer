package xyz.linplayer.app

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import xyz.linplayer.app.ui.player.PlaybackDiagnostic
import xyz.linplayer.app.ui.player.PlaybackHealthMonitor
import xyz.linplayer.app.ui.player.PlaybackHealthSample

class PlaybackHealthMonitorTest {
    private val buffering = PlaybackHealthSample(Player.STATE_BUFFERING, 30_000, 31_000, 1f,
        true, true, true, false, true, 120_000)
    private fun monitor() = PlaybackHealthMonitor().apply { begin(0) }

    @Test fun 就绪不是首帧且阶段耗时只报一次() {
        val m = monitor()
        assertEquals(500L, m.ready(500))
        assertNull(m.ready(600))
        val ready = buffering.copy(state = Player.STATE_READY)
        assertNull(m.sample(1_000, ready))
        assertEquals(PlaybackDiagnostic.FIRST_FRAME_MISSING, m.sample(7_000, ready))
        assertNull(m.sample(8_000, ready))
        assertEquals(9_000L, m.firstFrame(9_000))
        assertNull(m.firstFrame(10_000))
        assertNull(m.sample(30_000, ready))
    }

    @Test fun 长时间缺数据不能算进已有缓冲停滞窗口() {
        val m = monitor()
        assertNull(m.sample(0, buffering))
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(60_000, buffering))
        val filled = buffering.copy(bufferedPositionMs = 38_000)
        assertNull(m.sample(60_001, filled))
        assertNull(m.sample(72_000, filled))
        assertEquals(PlaybackDiagnostic.BUFFERED_STALL, m.sample(72_001, filled))
        assertNull(m.sample(80_000, filled))
    }

    @Test fun 首帧等待必须连续满足就绪条件且时间推进不能冒充出画面() {
        val m = monitor()
        val ready = buffering.copy(state = Player.STATE_READY)
        m.sample(0, ready)
        assertNull(m.sample(5_000, buffering))
        assertNull(m.sample(6_000, ready))
        assertNull(m.sample(11_000, ready.copy(active = false)))
        assertNull(m.sample(12_000, ready))
        assertNull(m.sample(17_000, ready.copy(positionMs = 34_000)))
        assertEquals(PlaybackDiagnostic.FIRST_FRAME_MISSING, m.sample(18_000, ready.copy(positionMs = 35_000)))
    }

    @Test fun 倍率和字节上限停止取数共同影响缓冲判定() {
        val filled = buffering.copy(bufferedPositionMs = 38_000, speed = 2f)
        val m = monitor()
        m.sample(0, filled)
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(12_000, filled))
        val capped = filled.copy(loading = false)
        assertNull(m.sample(12_001, capped))
        assertEquals(PlaybackDiagnostic.BUFFERED_STALL, m.sample(24_001, capped))
        val empty = buffering.copy(bufferedPositionMs = 30_000, loading = false)
        assertNull(m.sample(25_000, empty))
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(37_000, empty))
    }

    @Test fun 暂停抑制后台和结束不累计等待() {
        val excluded = listOf(buffering.copy(wantsPlayback = false), buffering.copy(suppressed = true),
            buffering.copy(active = false), buffering.copy(state = Player.STATE_IDLE),
            buffering.copy(state = Player.STATE_ENDED), buffering.copy(durationMs = 31_000),
            buffering.copy(speed = 0f), buffering.copy(speed = Float.NaN), buffering.copy(speed = Float.POSITIVE_INFINITY))
        for (s in excluded) {
            val m = monitor()
            m.sample(0, buffering)
            assertNull(m.sample(11_999, s))
            assertNull(m.sample(12_000, buffering))
            assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(24_000, buffering))
        }
    }

    @Test fun 用户跳转与倍率改变重新计时() {
        val m = monitor()
        m.sample(0, buffering)
        m.interrupt()
        assertNull(m.sample(12_000, buffering))
        assertNull(m.sample(23_000, buffering.copy(speed = 2f)))
        assertNull(m.sample(24_000, buffering.copy(speed = 2f)))
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(35_000, buffering.copy(speed = 2f)))
    }

    @Test fun 小时钟抖动不无限延后告警但真实进展重新计时() {
        val m = monitor()
        m.sample(0, buffering)
        assertNull(m.sample(6_000, buffering.copy(positionMs = 30_100)))
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(12_000, buffering.copy(positionMs = 30_200)))
        assertNull(m.sample(13_000, buffering.copy(positionMs = 30_300)))
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(25_000, buffering.copy(positionMs = 30_300)))
    }

    @Test fun 未知时长仍诊断且纯音频不等待视频首帧() {
        val m = monitor()
        val s = buffering.copy(durationMs = -1)
        m.sample(0, s)
        assertEquals(PlaybackDiagnostic.WAITING_DATA, m.sample(12_000, s))
        assertNull(m.sample(20_000, s.copy(state = Player.STATE_READY, hasVideo = false)))
        assertNull(m.sample(40_000, s.copy(state = Player.STATE_READY, hasVideo = false)))
    }

    @Test fun 停止及换片不继承旧首帧或等待窗口() {
        val m = monitor()
        m.ready(1_000)
        m.firstFrame(2_000)
        m.sample(3_000, buffering)
        m.end()
        assertNull(m.sample(20_000, buffering))
        assertNull(m.ready(21_000))
        assertNull(m.firstFrame(22_000))
        m.begin(30_000)
        assertEquals(1_000L, m.ready(31_000))
        val ready = buffering.copy(state = Player.STATE_READY)
        assertNull(m.sample(32_000, ready))
        assertEquals(PlaybackDiagnostic.FIRST_FRAME_MISSING, m.sample(38_000, ready))
        assertEquals(9_000L, m.firstFrame(39_000))
    }
}
