package xyz.linplayer.app

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.ui.player.BUFFER_MIB
import xyz.linplayer.app.ui.player.playbackLoadControl
import xyz.linplayer.app.ui.player.rememberExoPlayer
import xyz.linplayer.app.ui.player.TrackPrefs

/** 实际LoadControl判据与生产播放器构造接线，不用设置回显代替内核生效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackBufferTest {
    @get:Rule val rule = createComposeRule()

    @Test fun 流媒体半秒起播且保留恢复和本地策略() {
        for (target in listOf(0L, 64 * BUFFER_MIB)) {
            val control = playbackLoadControl(target)
            val previous = DefaultLoadControl.Builder().apply {
                if (target > 0) { setTargetBufferBytes(target.toInt()); setPrioritizeTimeOverSizeThresholds(false) }
            }.build()
            val id = PlayerId("startup-buffer")
            fun params(local: Boolean, ms: Long, rebuffer: Boolean): LoadControl.Parameters {
                val timeline = SinglePeriodTimeline(60_000_000, true, false, false, null,
                    MediaItem.fromUri(if (local) "file:///fixture.mkv" else "https://buffer-fixture.invalid/video.mkv"))
                return LoadControl.Parameters(id, timeline, MediaPeriodId(timeline.getUidOfPeriod(0)),
                    0L, ms * 1000, 1f, true, rebuffer, C.TIME_UNSET, C.TIME_UNSET)
            }
            control.onPrepared(id); previous.onPrepared(id)
            try {
                control.onTracksSelected(params(false, 0, false), TrackGroupArray.EMPTY, emptyArray())
                previous.onTracksSelected(params(false, 0, false), TrackGroupArray.EMPTY, emptyArray())
                assertFalse(control.shouldStartPlayback(params(false, 499, false)))
                assertTrue("首播或seek后不再等一秒", control.shouldStartPlayback(params(false, 500, false)))
                assertFalse(control.shouldStartPlayback(params(false, 1999, true)))
                assertTrue(control.shouldStartPlayback(params(false, 2000, true)))
                for (ms in listOf(499L, 500L, 999L, 1000L, 1999L, 2000L, 10_000L, 50_000L)) {
                    for (rebuffer in listOf(false, true)) {
                        val p = params(true, ms, rebuffer)
                        assertEquals("本地门槛不变: $target/$ms/$rebuffer", previous.shouldStartPlayback(p), control.shouldStartPlayback(p))
                        assertEquals(previous.shouldContinueLoading(p), control.shouldContinueLoading(p))
                    }
                }
            } finally { control.onReleased(id); previous.onReleased(id) }
        }
    }

    @Test fun 自定义达到容量后停止加载但起播不等待填满() {
        val control = playbackLoadControl(64 * BUFFER_MIB)
        val id = PlayerId("buffer-test")
        val timeline = SinglePeriodTimeline(60_000_000, true, false, false, null,
            MediaItem.fromUri("https://buffer-fixture.invalid/video.mkv"))
        fun params(ms: Long) = LoadControl.Parameters(id, timeline, MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, ms * 1000, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        control.onPrepared(id)
        control.onTracksSelected(params(0), TrackGroupArray.EMPTY, emptyArray())
        val allocator = control.getAllocator(id)
        try {
            assertTrue("达到正常时间即可起播，不需填满容量", control.shouldStartPlayback(params(10_000)))
            assertTrue(control.shouldContinueLoading(params(1_000)))
            repeat((64 * BUFFER_MIB / allocator.individualAllocationLength).toInt()) { allocator.allocate() }
            assertFalse("时间还不足min但容量已达标，不能继续盲目扩大", control.shouldContinueLoading(params(1_000)))
        } finally { control.onReleased(id) }
    }

    @Test fun 实际播放器采用自定义目标且新自动实例不残留() {
        var player: ExoPlayer? = null
        rule.setPlayerContent { player = rememberExoPlayer(true, TrackPrefs(), 128 * BUFFER_MIB) }
        rule.runOnIdle { assertEquals(128 * BUFFER_MIB, actualBufferTarget(player!!).toLong()) }
        assertEquals(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES, bufferTarget(playbackLoadControl(0)))
    }
}

/** 核验构造后的真实播放器，删掉setLoadControl接线应使断言失败。 */
internal fun actualBufferTarget(player: ExoPlayer): Int {
    val internal: Any = ReflectionHelpers.getField(player, "internalPlayer")
    val control: DefaultLoadControl = ReflectionHelpers.getField(internal, "loadControl")
    return bufferTarget(control)
}

private fun bufferTarget(control: DefaultLoadControl): Int = ReflectionHelpers.getField(control, "targetBufferBytesOverwrite")
