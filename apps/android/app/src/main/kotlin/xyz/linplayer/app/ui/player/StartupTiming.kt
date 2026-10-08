package xyz.linplayer.app.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import xyz.linplayer.app.ui.Route

/** 只在本进程、这次导航中传递点击起点，恢复返回栈不复用旧的单调时间戳。主线程访问。 */
internal object PlaybackClickTimes {
    private val times = WeakHashMap<NavBackStackEntry, Long>()

    fun navigate(nav: NavController, route: Route.Player) {
        val clickedAt = SystemClock.elapsedRealtime()
        nav.navigate(route)
        nav.currentBackStackEntry?.let { times[it] = clickedAt }
    }

    fun take(entry: NavBackStackEntry): Long? = times.remove(entry)
}

internal enum class StartupOrigin { CLICK, TARGET, PAGE }
internal enum class StartupPhase { REQUEST, REQUEST_COMPLETE, LOAD, FIRST_FRAME, FAILED }

/** 一次内核尝试的起播阶段；不能将 MPV 命令返回当作地址解析结束或首帧。 */
internal class StartupTiming(
    private val startedAt: Long,
    private val origin: StartupOrigin,
    private val media3: Boolean,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val emit: (String) -> Unit,
) {
    private val attempt = attempts.incrementAndGet()
    private var requestAt: Long? = null
    private var completeAt: Long? = null
    private var loadAt: Long? = null
    private var closed = false

    private fun report(phase: StartupPhase, now: Long, extra: String = "") {
        emit("phase=startup_${phase.name.lowercase()} attempt=$attempt engine=${if (media3) "media3" else "mpv"}" +
            " origin=${origin.name.lowercase()} total_ms=${now - startedAt}$extra")
    }

    fun request() {
        if (closed || requestAt != null) return
        val now = nowMs()
        requestAt = now
        report(StartupPhase.REQUEST, now)
    }

    fun requestComplete() {
        val request = requestAt ?: return
        if (closed || completeAt != null) return
        val now = nowMs()
        completeAt = now
        report(StartupPhase.REQUEST_COMPLETE, now, " request_ms=${now - request} first_frame_supported=$media3")
        if (!media3) close()
    }

    fun load() {
        if (closed || !media3 || completeAt == null || loadAt != null) return
        val now = nowMs()
        loadAt = now
        report(StartupPhase.LOAD, now, " address_to_load_ms=${now - completeAt!!}")
    }

    fun firstFrame() {
        val load = loadAt ?: return
        if (closed) return
        val now = nowMs()
        report(StartupPhase.FIRST_FRAME, now, " load_to_frame_ms=${now - load}")
        close()
    }

    fun failed() {
        if (closed || requestAt == null) return
        report(StartupPhase.FAILED, nowMs())
        close()
    }

    fun playerFailed() { if (loadAt != null) failed() }

    fun close() { closed = true }

    private companion object { val attempts = AtomicLong() }
}

/** 先于页面 load 登记真实首帧回调；后台、错误和旧拥有者退出后不接收迟到结果。 */
@Composable
internal fun ObserveStartupTiming(player: ExoPlayer?, timing: StartupTiming) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, timing) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = timing.firstFrame()
            override fun onPlayerError(error: PlaybackException) = timing.playerFailed()
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
        }
    }
    DisposableEffect(timing, owner) {
        val visibility = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) timing.close()
        }
        owner.lifecycle.addObserver(visibility)
        onDispose {
            timing.close()
            owner.lifecycle.removeObserver(visibility)
        }
    }
}
