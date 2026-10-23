package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.navigation.compose.rememberNavController
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.advance
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.player
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.ExoPreload
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.theme.LpTheme
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 页面、HTTP取流、预载与自然完播均走生产链路；核心命令替身不代表实服上报。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class,
    shadows = [ShadowAssNative::class], instrumentedPackages = ["xyz.linplayer.app.core"])
class PhoneMedia3RecoveryTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val visible = mutableStateOf(true)
    private val recover = CountDownLatch(1)
    private val stalled = CountDownLatch(1)
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val prepared = mutableMapOf<String, JsonObject>()
    private val core = FakeCore().loggedIn().player()
    private val trace = mutableListOf<String>()
    private val app = AppState(core, scope)

    init {
        val pcmSize = 16_000 * 20
        val wav = ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmSize)
        }.array()
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                val from = exchange.requestHeaders.getFirst("Range")
                    ?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (from > 0) exchange.responseHeaders.add("Content-Range", "bytes $from-${wav.lastIndex}/${wav.size}")
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.sendResponseHeaders(if (from > 0) 206 else 200, (wav.size - from).toLong())
                runCatching {
                    val split = 64_044
                    if (exchange.requestURI.path == "/sh7.wav" && from < split) {
                        exchange.responseBody.write(wav, from, split - from)
                        exchange.responseBody.flush()
                        stalled.countDown()
                        check(recover.await(30, TimeUnit.SECONDS))
                        exchange.responseBody.write(wav, split, wav.size - split)
                    } else {
                        exchange.responseBody.write(wav, from, wav.size - from)
                    }
                }
            }
        }
        server.start()
        core.ret("prefs.getPrefs", buildJsonObject { put("sub_enabled", false); put("default_speed", 1.0) })
        core.ret("player.stopPlayback", JsonNull)
        core.on("player.warmPrepared") { a ->
            trace += "warm ${prepared[a.str("request_id")].str("item_id")} ${a.bool("enabled")} ${currentPlayer()?.currentPosition}"
            JsonNull
        }
        core.ret("player.cancelPrepared", JsonNull)
        core.on("emby.itemDetail") { a -> buildJsonObject {
            val id = a.str("item_id")!!
            put("id", id); put("name", id); put("type_", "Episode")
            put("season_id", "sh"); put("series_id", "s2"); put("season_no", 1)
            put("episode_no", id.removePrefix("sh").toInt())
        } }
        core.on("player.play") { a -> playback(a.str("item_id")!!) }
        core.on("player.prepareNext") { a ->
            val request = a.str("request_id")!!
            trace += "prepare ${a.str("item_id")} ${currentPlayer()?.currentPosition}"
            JsonObject(playback(a.str("item_id")!!) + buildJsonObject { put("request_id", request) })
                .also { prepared[request] = it }
        }
        core.on("player.queuePrepared") { a ->
            trace += "queue ${prepared[a.str("request_id")].str("item_id")} ${currentPlayer()?.currentPosition}"
            prepared.getValue(a.str("request_id")!!)
        }
        core.on("player.advanceQueued") { a -> prepared.getValue(a.str("request_id")!!) }
    }

    @After fun clean() {
        recover.countDown()
        try {
            val player = rule.runOnIdle { currentPlayer() }
            rule.runOnIdle { visible.value = false }
            advance(rule, 300)
            assertTrue(player == null || player.isReleased)
        } finally {
            server.stop(0)
            executor.shutdownNow()
            app.bg.cancel()
            scope.cancel()
            Libass.reset()
        }
    }

    @Test fun naturalAutoplayRecoversAfterStallWithoutRecreatingPlayer() {
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    if (visible.value) PlayerPage(nav, null,
                        embeddedRoute = Route.Player("sh6", "fixture", engine = "exo"))
                }
            }
        }
        await("initial playback") { currentPlayer()?.playbackState == Player.STATE_READY }
        val player = currentPlayer()!!
        val owner = ExoPreload.of(player)!!
        val manager: DefaultPreloadManager = ReflectionHelpers.getField(owner, "manager")
        val transitions = mutableListOf<Pair<String?, Int>>()
        val bufferingItems = mutableListOf<String?>()
        rule.runOnIdle {
            manager.addListener(object : PreloadManagerListener {
                override fun onCompleted(mediaItem: MediaItem) {
                    trace += "completed ${mediaItem.localConfiguration!!.uri.lastPathSegment}"
                }
                override fun onError(exception: PreloadException) { trace += "preload error" }
            })
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions += mediaItem?.localConfiguration?.uri?.lastPathSegment to reason
                    trace += "transition ${mediaItem?.localConfiguration?.uri?.lastPathSegment} $reason ${player.playbackState}"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    trace += "state $state ${player.currentPosition}"
                    if (state == Player.STATE_BUFFERING) bufferingItems += player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment
                }
            })
        }
        await("second episode stalls") {
            stalled.count == 0L && player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment == "sh7.wav" &&
                player.currentPosition > 250 && player.playbackState == Player.STATE_BUFFERING
        }
        recover.countDown()
        for (id in listOf("sh8", "sh9", "sh10")) {
            // 入队后样本可由播放器继续读取，不能再要求预载管理器发出完成回调。
            await("queued samples for $id") {
                val next = player.currentMediaItemIndex + 1
                next < player.mediaItemCount &&
                    player.getMediaItemAt(next).localConfiguration?.uri?.lastPathSegment == "$id.wav" &&
                    player.totalBufferedDuration >= player.duration - player.currentPosition + 5_000
            }
            await("automatic transition to $id") {
                player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment == "$id.wav" &&
                    core.calls.any { it.first == "player.advanceQueued" && prepared[it.second.str("request_id")].str("item_id") == id }
            }
            assertSame(player, currentPlayer())
            assertSame(owner, ExoPreload.of(player))
            assertTrue(transitions.any { it.first == "$id.wav" && it.second == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO })
            await("playback progresses in $id") { player.currentPosition > 250 && player.playbackState == Player.STATE_READY }
            assertFalse("unexpected buffering in $id: $trace", "$id.wav" in bufferingItems)
        }
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals(0, core.calls.count { it.first == "player.stopPlayback" })
    }

    private fun playback(id: String) = buildJsonObject {
        put("item_id", id); put("media_source_id", id); put("resume_secs", 0.0)
        put("play_url", "http://127.0.0.1:${server.address.port}/$id.wav")
    }

    private fun currentPlayer(): ExoPlayer? = ReflectionHelpers.getStaticField(PlaybackService::class.java, "externalPlayer")

    private fun await(stage: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 30_000_000_000L
        while (!rule.runOnIdle(condition) && System.nanoTime() < end) {
            advance(rule, 100)
            Thread.sleep(10)
            rule.runOnIdle { currentPlayer()?.playerError?.let { throw AssertionError(stage, it) } }
        }
        assertTrue("$stage; play=${core.calls.count { it.first == "player.play" }}, " +
            "queue=${core.calls.count { it.first == "player.queuePrepared" }}, " +
            "advance=${core.calls.count { it.first == "player.advanceQueued" }}; $trace", rule.runOnIdle(condition))
    }
}
