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
        rule.setContent { player = rememberExoPlayer(true, TrackPrefs(), 128 * BUFFER_MIB) }
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
