package xyz.linplayer.app.tv

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.rememberExoPlayer
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import xyz.linplayer.app.ui.player.PlaybackSnapshot
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvRefreshRatePlaybackTest {
    @get:Rule val rule = createComposeRule()
    private val core = FakeCore().loggedIn().player()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var activity: Activity
    private lateinit var nav: TvNav
    private var fps = "24"
    private var filters = ""
    private var deferNext = false
    private var pending: Continuation<JsonElement>? = null

    @Before fun setup() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        core.on("player.opts") { opts(fps) }
        core.on("player.mpvGet") { buildJsonObject { put("name", "vf"); put("value", filters) } }
        core.ret("player.stopPlayback", JsonNull)
    }

    @After fun cleanup() { scope.cancel() }

    private fun opts(value: String) = buildJsonObject {
        put("container-fps", value); put("current-vo", "gpu"); put("dwidth", "1920"); put("dheight", "1080")
    }

    private fun mount(expectMatch: Boolean = true) {
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.opts" && deferNext) {
                    deferNext = false
                    // 忠实模拟无法撤回的 FFI 请求：取消后仍让旧响应完成。
                    return suspendCoroutine { pending = it }
                }
                return core.callJson(command, args, onPartial)
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        nav = TvNav().apply { push(TvRoute.Player("sh6", "测试", engine = "mpv")) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            activity = LocalContext.current as Activity
            TvFrame(app) { TvShell(nav) }
        }
        rule.runOnIdle { installTvModes(activity.windowManager.defaultDisplay) }
        advance(rule, 1800)
        assertEquals(if (expectMatch) 101 else 0, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun 同帧率切集保留模式不同帧率重判退出恢复() {
        mount()
        core.on("player.play") { a ->
            assertEquals("切集不能先恢复显示偏好", 101, activity.window.attributes.preferredDisplayModeId)
            if (a.str("item_id") == "sh8") fps = "25"
            buildJsonObject { put("media_source_id", "ea") }
        }
        press(rule, Key.MediaNext)
        advance(rule, 1600)
        assertEquals(101, activity.window.attributes.preferredDisplayModeId)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        press(rule, Key.MediaNext)
        advance(rule, 1600)
        assertEquals(102, activity.window.attributes.preferredDisplayModeId)
        assertEquals("sh8", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        press(rule, Key.MediaStop)
        advance(rule, 300)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        rule.onNode(hasTestTag("player.root")).assertDoesNotExist()
    }

    @Test fun 迟到的上一集帧率不能覆盖新片或退出恢复() {
        mount()
        rule.runOnIdle { deferNext = true }
        advance(rule, 1200)
        assertNotNull(pending)
        rule.runOnIdle { fps = "25" }
        press(rule, Key.MediaNext)
        advance(rule, 1800)
        assertEquals(102, activity.window.attributes.preferredDisplayModeId)
        rule.runOnIdle { pending!!.resume(opts("24")); pending = null }
        rule.runOnIdle { assertEquals("旧响应完成的这一刻也不能写回旧模式", 102, activity.window.attributes.preferredDisplayModeId) }
        advance(rule, 400)
        assertEquals(102, activity.window.attributes.preferredDisplayModeId)
        rule.runOnIdle { deferNext = true }
        advance(rule, 1200)
        assertNotNull(pending)
        press(rule, Key.MediaStop)
        advance(rule, 300)
        rule.runOnIdle { pending!!.resume(opts("24")); pending = null }
        advance(rule, 400)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun 补帧启用时恢复原偏好关闭后重新匹配() {
        mount()
        rule.runOnIdle { filters = "@lpinterp:lpinterp=multi=2" }
        advance(rule, 1500)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        assertFalse(core.calls.any { it.first == "player.setInterpLevel" })
        rule.runOnIdle { filters = "" }
        advance(rule, 1500)
        assertEquals(101, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun 补帧从起播启用时多个采样周期都不按源帧率降频() {
        filters = "@lpinterp:lpinterp=multi=2"
        mount(expectMatch = false)
        repeat(8) {
            advance(rule, 500)
            assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        }
        assertFalse(core.calls.any { it.first == "player.setInterpLevel" })
    }

    @Test fun 滤镜回读失败恢复偏好但不阻断播放() {
        mount()
        core.on("player.mpvGet") { throw CoreException("E_INTERNAL", "属性读取失败", false) }
        advance(rule, 1500)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        advance(rule, 3000)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        rule.onNode(hasTestTag("player.root")).assertExists()
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 未知帧率不立即闪屏超时后恢复无支持模式仍正常播放() {
        mount()
        rule.runOnIdle { fps = "N/A" }
        advance(rule, 2000)
        assertEquals(101, activity.window.attributes.preferredDisplayModeId)
        advance(rule, 10_000)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        rule.runOnIdle { fps = "27" }
        advance(rule, 1500)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        rule.onNode(hasTestTag("player.root")).assertExists()
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun Media3窗口管理期间关闭Surface提示离开时恢复且已释放实例不再操作() {
        val app = AppState(core, scope)
        val controller = PlayerController("exo") { _, _ -> JsonNull }
        var shown by mutableStateOf(true)
        var player: ExoPlayer? = null
        rule.setContent {
            activity = LocalContext.current as Activity
            player = rememberExoPlayer(true, null)
            if (shown) MatchTvRefreshRate(app, controller, player, 0)
        }
        rule.runOnIdle {
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player!!.videoChangeFrameRateStrategy)
            shown = false
        }
        rule.runOnIdle {
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, player!!.videoChangeFrameRateStrategy)
            shown = true
        }
        rule.runOnIdle { player!!.release(); shown = false }
        rule.runOnIdle { assertTrue(player!!.isReleased) }
    }

    @Test fun Media3帧率接到窗口自动回退后改用MPV帧率() {
        val app = AppState(core, scope)
        val controller = PlayerController("auto") { command, args -> core.callJson(command, args) }
        var strategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "isReleased" -> false
                "getPlaybackState" -> Player.STATE_READY
                "getVideoFormat" -> Format.Builder().setFrameRate(24f).build()
                "getCurrentTracks" -> Tracks.EMPTY
                "getVideoChangeFrameRateStrategy" -> strategy
                "setVideoChangeFrameRateStrategy" -> { strategy = args!![0] as Int; null }
                "stop" -> null
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "测试播放器"
                else -> error("未预期的方法 ${method.name}")
            }
        } as ExoPlayer
        controller.bind(player)
        controller.started()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            activity = LocalContext.current as Activity
            MatchTvRefreshRate(app, controller, if (controller.engine == "exo") player else null, 0)
        }
        rule.runOnIdle { installTvModes(activity.windowManager.defaultDisplay) }
        advance(rule, 1400)
        assertEquals(101, activity.window.attributes.preferredDisplayModeId)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, strategy)
        assertFalse("Media3 不应查询 MPV", core.calls.any { it.first == "player.opts" })
        rule.runOnIdle {
            runBlocking {
                assertTrue(controller.tryFallback(PlaybackException("解码失败", null,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), PlaybackSnapshot(0.0, false, 1.0, 1f)))
            }
            fps = "25"
        }
        rule.runOnIdle { controller.started() }
        advance(rule, 1400)
        assertEquals(102, activity.window.attributes.preferredDisplayModeId)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, strategy)
        assertTrue(core.calls.any { it.first == "player.opts" })
    }
}
