package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StartupTimingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun 阶段包含点击等待且首帧必须晚于加载并只报一次() {
        var now = 300L
        val logs = mutableListOf<String>()
        val timing = StartupTiming(100, StartupOrigin.CLICK, true, { now }, logs::add)
        timing.firstFrame()
        timing.request()
        now = 800
        timing.requestComplete()
        now = 850
        timing.load()
        now = 1_200
        timing.firstFrame()
        timing.firstFrame()
        assertEquals(4, logs.size)
        assertTrue(logs[0].contains("origin=click total_ms=200"))
        assertTrue(logs[1].contains("request_ms=500 first_frame_supported=true"))
        assertTrue(logs[2].contains("address_to_load_ms=50"))
        assertTrue(logs[3].contains("total_ms=1100 load_to_frame_ms=350"))
    }

    @Test fun MPV只记请求返回且首帧未知失败与关闭拒绝迟到阶段() {
        val logs = mutableListOf<String>()
        val mpv = StartupTiming(0, StartupOrigin.TARGET, false, { 100 }, logs::add)
        mpv.request(); mpv.requestComplete(); mpv.load(); mpv.firstFrame()
        assertEquals(2, logs.size)
        assertTrue(logs.last().contains("engine=mpv origin=target"))
        assertTrue(logs.last().endsWith("first_frame_supported=false"))
        val exo = StartupTiming(0, StartupOrigin.PAGE, true, { 200 }, logs::add)
        exo.request(); exo.failed(); exo.requestComplete(); exo.load(); exo.firstFrame()
        assertEquals(4, logs.size)
        assertTrue(logs.last().startsWith("phase=startup_failed"))
        val closed = StartupTiming(0, StartupOrigin.PAGE, true, { 200 }, logs::add)
        closed.close(); closed.request(); closed.requestComplete(); closed.firstFrame()
        assertEquals(4, logs.size)
    }

    @Test fun 偏好晚到创建播放器后仍监听真实首帧且离页撤销() {
        val enabled = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val logs = mutableListOf<String>()
        val timing = StartupTiming(0, StartupOrigin.PAGE, true, { 100 }, logs::add)
        var player: ExoPlayer? = null
        rule.setContent {
            player = rememberExoPlayer(enabled.value, null)
            if (visible.value) ObserveStartupTiming(player, timing)
        }
        rule.runOnIdle { enabled.value = true }
        lateinit var listener: Player.Listener
        lateinit var original: ExoPlayer
        rule.runOnIdle {
            original = player!!
            listener = listeners(original).single { it.javaClass.name.contains("StartupTiming") }
            timing.request()
            listener.onPlayerError(PlaybackException("旧媒体错误", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
            timing.requestComplete(); timing.load()
            listener.onRenderedFirstFrame(); listener.onRenderedFirstFrame()
            assertEquals(4, logs.size)
            visible.value = false
        }
        rule.runOnIdle {
            assertFalse(listeners(original).contains(listener))
            listener.onRenderedFirstFrame()
            assertEquals(4, logs.size)
        }
    }

    @Test fun 详情导航点击起点只消费一次且普通恢复入口无点击值() {
        lateinit var nav: NavController
        var clickedAt: Long? = null
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Home) {
                composable<Route.Home> { }
                composable<Route.Player> { entry -> clickedAt = remember(entry) { PlaybackClickTimes.take(entry) } }
            }
        }
        rule.runOnIdle {
            PlaybackClickTimes.navigate(nav, Route.Player("test", "test"))
        }
        rule.runOnIdle {
            val entry = nav.currentBackStackEntry!!
            assertNotNull(clickedAt)
            assertNull(PlaybackClickTimes.take(entry))
            nav.navigate(Route.Player("other", "test"))
        }
        rule.runOnIdle {
            assertNull(clickedAt)
            assertNull(PlaybackClickTimes.take(nav.currentBackStackEntry!!))
        }
    }

    @Test fun 后台事件立即关闭测量且恢复前台不会接受迟到首帧() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val logs = mutableListOf<String>()
        val timing = StartupTiming(0, StartupOrigin.PAGE, true, { 100 }, logs::add)
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { ObserveStartupTiming(null, timing) }
        }
        rule.runOnIdle {
            timing.request(); timing.requestComplete(); timing.load()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            timing.firstFrame()
            assertEquals(3, logs.size)
        }
    }

    private fun listeners(player: ExoPlayer): List<Player.Listener> {
        val set = ReflectionHelpers.getField<Any>(player, "listeners")
        val holders = ReflectionHelpers.getField<Set<Any>>(set, "listeners")
        return holders.map { ReflectionHelpers.getField(it, "listener") }
    }
}
