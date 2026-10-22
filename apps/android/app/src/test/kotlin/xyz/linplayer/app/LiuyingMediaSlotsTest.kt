package xyz.linplayer.app

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.ListenerSet
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.ui.player.liuying.LiuyingMedia
import xyz.linplayer.app.ui.player.liuying.LiuyingMediaSlots
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** 真实槽与Media3实例的所有权回归；样本完成注入不代表实际解码或首帧性能。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LiuyingMediaSlotsTest {
    private val server = ServerSocket(0)
    private val connections = Collections.synchronizedList(mutableListOf<Socket>())
    private val slots = LiuyingMediaSlots(RuntimeEnvironment.getApplication())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { connections += server.accept() }
        }
    }

    @After fun clean() {
        slots.release()
        server.close()
        synchronized(connections) { connections.forEach { it.close() } }
    }

    private fun media(id: String, canPreload: Boolean = true) = LiuyingMedia(
        id, id, "http://127.0.0.1:${server.localPort}/$id.mp4", "test", null, canPreload, 0,
    )

    private fun samplesReady(id: String) {
        val entries: Map<String, LiuyingMediaSlots.Entry> = ReflectionHelpers.getField(slots, "entries")
        entries.getValue(id).samplesReady = true
    }

    private fun emit(slot: LiuyingMediaSlots.Slot, item: MediaItem, event: (AnalyticsListener, AnalyticsListener.EventTime) -> Unit) {
        val timeline = SinglePeriodTimeline(20_000_000, true, false, false, null, item)
        val time = AnalyticsListener.EventTime(0, timeline, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        val collector: Any = ReflectionHelpers.getField(slot.player, "analyticsCollector")
        val listeners: ListenerSet<AnalyticsListener> = ReflectionHelpers.getField(collector, "listeners")
        listeners.sendEvent(-1) { event(it, time) }
    }

    @Test fun oneHundredPromotionsReuseAtMostTwoPlayersAndKeepSpareSilent() {
        val players = mutableSetOf<Any>()
        slots.promote(media("current"), 1.5f, false)
        players += slots.active!!.player
        repeat(100) { index ->
            val next = media("next-$index")
            slots.prepare(next, 1)
            slots.warm(next.request, true)
            samplesReady(next.request)
            slots.warm(next.request, true)
            val spare = slots.spare!!
            players += spare.player
            assertTrue(players.size <= 2)
            assertFalse(spare.player.playWhenReady)
            assertEquals(0f, spare.player.volume)
            slots.detachCurrent()
            slots.promote(next, 2f, true)
            assertSame(spare, slots.active)
            assertEquals(2f, slots.active!!.player.playbackParameters.speed)
            assertEquals(0f, slots.active!!.player.volume)
        }
        val used = players.toList()
        slots.release()
        assertNull(slots.active)
        assertNull(slots.spare)
        used.forEach { assertTrue((it as androidx.media3.exoplayer.ExoPlayer).isReleased) }
    }

    @Test fun onlyMatchingMediaAndSurfaceCanPublishFirstFrameOrError() {
        slots.promote(media("first"), 1f, false)
        val slot = slots.active!!
        val old = slot.entry!!.item
        slots.detachCurrent()
        slots.promote(media("second"), 1f, false)
        assertSame(slot, slots.active)
        val current = slot.entry!!.item
        var errors = 0
        slots.onError = { errors++ }
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        slot.surface = surface
        val failure = PlaybackException("fixture", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        try {
            emit(slot, old) { listener, time ->
                listener.onRenderedFirstFrame(time, surface, 0)
                listener.onPlayerError(time, failure)
            }
            assertFalse(slot.frameReady)
            assertEquals(0, errors)
            emit(slot, current) { listener, time -> listener.onRenderedFirstFrame(time, Any(), 0) }
            assertFalse(slot.frameReady)
            emit(slot, current) { listener, time ->
                listener.onRenderedFirstFrame(time, surface, 0)
                listener.onPlayerError(time, failure)
            }
            assertTrue(slot.frameReady)
            assertEquals(1, errors)
        } finally {
            slot.surface = null
            surface.release()
            texture.release()
        }
    }

    @Test fun livePreparationDoesNotCreateSourceOrSpareAndBackgroundReleasesSpare() {
        slots.prepare(media("live", canPreload = false), 1)
        assertNull(slots.nextWarmRequest())
        assertNull(slots.spare)
        slots.promote(media("current"), 1f, false)
        val next = media("next")
        slots.prepare(next, 1)
        slots.warm(next.request, true)
        samplesReady(next.request)
        slots.warm(next.request, true)
        val spare = slots.spare!!
        slots.gate(false, false, false)
        assertTrue(spare.player.isReleased)
        assertNull(slots.spare)
        assertFalse(slots.active!!.player.isReleased)
        assertFalse(slots.active!!.player.playWhenReady)
    }

    @Test fun failedPreloadIsNotRestartedBySchedulerButCanBeSelectedForPlayback() = verifyFailedPreload(false)

    @Test fun latePreloadErrorAfterPauseDoesNotRestoreAutomaticRetry() = verifyFailedPreload(true)

    private fun verifyFailedPreload(errorAfterPause: Boolean) {
        val next = media("failed")
        slots.prepare(next, 1)
        slots.prepare(media("later"), 2)
        slots.warm(next.request, true)
        val entries: Map<String, LiuyingMediaSlots.Entry> = ReflectionHelpers.getField(slots, "entries")
        val entry = entries.getValue(next.request)
        val manager: Any = ReflectionHelpers.getField(slots, "manager")
        val listeners: ListenerSet<PreloadManagerListener> = ReflectionHelpers.getField(manager, "listeners")
        // 已中断的旧源也可能迟到报错；失败必须覆盖中断标记。
        if (errorAfterPause) slots.warm(null, false)
        listeners.sendEvent(-1) { it.onError(PreloadException(entry.item, "fixture", null)) }
        slots.warm(null, false)
        val failedItem = entry.item
        assertTrue(entry.failed)
        repeat(5) {
            assertEquals("later", slots.nextWarmRequest())
            slots.warm("later", true)
            slots.warm(null, false)
            slots.warm(next.request, true)
            slots.warm(null, false)
            assertTrue(entry.failed)
            assertFalse(entry.interrupted)
            assertSame(failedItem, entry.item)
        }
        slots.promote(next, 1f, false)
        assertSame(entry, slots.active!!.entry)
        assertFalse(entry.failed)
        assertNotEquals(failedItem.mediaId, entry.item.mediaId)
    }

    @Test fun interruptedPreloadCanResumeWithoutChangingRequestOrBecomingFailure() {
        val next = media("interrupted")
        slots.prepare(next, 1)
        slots.warm(next.request, true)
        val entries: Map<String, LiuyingMediaSlots.Entry> = ReflectionHelpers.getField(slots, "entries")
        val entry = entries.getValue(next.request)
        val oldItem = entry.item
        slots.warm(null, false)
        assertTrue(entry.interrupted)
        assertFalse(entry.failed)
        assertEquals(next.request, slots.nextWarmRequest())
        slots.warm(next.request, true)
        assertFalse(entry.interrupted)
        assertFalse(entry.failed)
        assertNotEquals(oldItem.mediaId, entry.item.mediaId)
        assertEquals(next, entry.media)
    }
}
