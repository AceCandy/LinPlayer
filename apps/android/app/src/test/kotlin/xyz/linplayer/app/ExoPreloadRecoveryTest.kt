package xyz.linplayer.app

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.ui.player.ExoPreload
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.playbackLoadControl
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** 受控数据源驱动实际解封装和预载；不把媒体源存在当作样本已准备完成。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [ShadowAssNative::class],
    instrumentedPackages = ["xyz.linplayer.app.core"])
class ExoPreloadRecoveryTest {
    private val opens = AtomicInteger()
    @Volatile private var offline = false
    @Volatile private var stallCurrent = false
    private val stalledReads = AtomicInteger()
    private val pcmSize = 8_000 * 2 * 10
    private val wav = ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcmSize); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmSize)
    }.array()
    private val data = DataSource.Factory {
        val source = ByteArrayDataSource(wav)
        object : DataSource by source {
            private var current = false
            private var position = 0L
            override fun open(spec: DataSpec): Long {
                opens.incrementAndGet()
                current = spec.uri.lastPathSegment == "current.wav"
                position = spec.position
                if (offline) throw IOException("fixture offline")
                return source.open(spec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (offline) throw IOException("fixture disconnected")
                if (current && position >= 64_044 && stallCurrent) {
                    stalledReads.incrementAndGet()
                    while (stallCurrent) Thread.sleep(10)
                }
                return source.read(buffer, offset, length.coerceAtMost(1_000)).also {
                    if (it > 0) position += it
                }
            }
        }
    }
    private val owner = ExoPreload(RuntimeEnvironment.getApplication(), data, playbackLoadControl(0), "/system/fonts")
    private val player = owner.player
    private val manager: DefaultPreloadManager = ReflectionHelpers.getField(owner, "manager")
    private val completed = mutableListOf<Uri>()
    private var failure: PreloadException? = null

    init {
        manager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) { completed += mediaItem.localConfiguration!!.uri }
            override fun onError(exception: PreloadException) { failure = exception }
        })
    }

    @After fun clean() { stallCurrent = false; player.release(); owner.release(); Libass.reset() }

    @Test fun interruptedPreloadAndThreeLaterItemsCompleteInSameManager() {
        owner.load(url("current"), null)
        player.prepare()
        await { player.duration > 0 && player.bufferedPosition >= player.duration - 1 }
        val before = opens.get()
        offline = true
        owner.prepare(url("interrupted"), null)
        await { opens.get() > before }
        owner.cancel()
        offline = false
        for (id in listOf("interrupted", "later-1", "later-2", "later-3")) {
            val target = url(id)
            owner.prepare(target, null)
            await { Uri.parse(target) in completed }
            var advanced = false
            assertTrue(owner.queue(target, null) { advanced = true })
            player.seekToNextMediaItem()
            await { advanced && player.currentTracks.groups.isNotEmpty() }
            owner.trimPrevious()
            assertEquals(1, player.mediaItemCount)
        }
        assertNull(failure)
    }

    @Test fun failedPreloadCanResumeAfterCancelAndNetworkRecovery() {
        val target = url("failed")
        offline = true
        owner.prepare(target, null)
        await { failure != null }
        offline = false
        owner.cancel()
        owner.prepare(target, null)
        await { Uri.parse(target) in completed }
    }

    @Test fun playbackRebufferDoesNotPreventLaterPreloadsInSameManager() {
        stallCurrent = true
        owner.load(url("current"), null)
        player.prepare()
        await { stalledReads.get() > 0 && player.playbackState == Player.STATE_READY }
        player.play()
        await { player.currentPosition > 250 }
        await { player.playbackState == Player.STATE_BUFFERING }
        stallCurrent = false
        await { player.playbackState == Player.STATE_READY }
        player.pause()
        for (id in listOf("later-1", "later-2", "later-3")) {
            val target = url(id)
            owner.prepare(target, null)
            await { Uri.parse(target) in completed }
            var advanced = false
            assertTrue(owner.queue(target, null) { advanced = true })
            player.seekToNextMediaItem()
            await { advanced && player.currentTracks.groups.isNotEmpty() }
            owner.trimPrevious()
            assertEquals(1, player.mediaItemCount)
        }
        assertNull(failure)
    }

    private fun url(id: String) = "https://preload-fixture.invalid/$id.wav"

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 12_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            player.playerError?.let { throw AssertionError("Media3 fixture playback failed", it) }
            Thread.sleep(10)
        }
        assertTrue("Media3 fixture state=${player.playbackState}, position=${player.currentPosition}, " +
            "buffered=${player.bufferedPosition}, duration=${player.duration}, opens=${opens.get()}", condition())
    }
}
