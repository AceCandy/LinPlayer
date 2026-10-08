package xyz.linplayer.app.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.linplayer.app.core.Logs

internal enum class SeekSampleSource { PAGE, SERVICE }
internal enum class SeekTimingEnd { SUPERSEDED, INTERRUPTED, BACKGROUND, FAILED, CANCELLED, TIMEOUT }

/** 只测命令与时钟观测，不证明目标视频帧已呈现；与显示 pending 的生命周期独立。 */
internal class SeekTiming(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val emit: (String) -> Unit,
) {
    private data class Attempt(
        val revision: Long, val target: Double, val engine: String, val id: Long, val start: Long,
        var submitted: Boolean = false, var positioned: Boolean = false, var baseline: Double? = null,
    )
    private var pending: Attempt? = null
    private var active = true

    private fun report(a: Attempt, phase: String, extra: String = "") {
        emit("phase=seek_$phase attempt=${a.id} engine=${a.engine} elapsed_ms=${nowMs() - a.start}$extra")
    }

    fun request(revision: Long, target: Double, engine: String) {
        interrupt(SeekTimingEnd.SUPERSEDED)
        if (!active) return
        val a = Attempt(revision, target, if (engine == "exo") "media3" else "mpv", attempts.incrementAndGet(), nowMs())
        pending = a
        report(a, "request")
    }

    fun submitted(revision: Long) {
        val a = pending?.takeIf { it.revision == revision && !it.submitted } ?: return
        a.submitted = true
        report(a, "submitted")
    }

    fun failed(revision: Long, cancelled: Boolean) {
        if (pending?.revision == revision) interrupt(if (cancelled) SeekTimingEnd.CANCELLED else SeekTimingEnd.FAILED)
    }

    fun sample(position: Double, buffering: Boolean, paused: Boolean?, source: SeekSampleSource) {
        expire()
        val a = pending?.takeIf { it.submitted } ?: return
        if (!position.isFinite() || buffering) { a.baseline = null; return }
        val extra = " sample_source=${source.name.lowercase()}"
        if (!a.positioned) {
            if (kotlin.math.abs(position - a.target) > 1.0) return
            a.positioned = true
            report(a, "target_observed", extra + " paused=${paused?.toString() ?: "unknown"}")
        }
        if (paused == true) { pending = null; return }
        if (paused == null) { a.baseline = null; return }
        val baseline = a.baseline
        if (baseline == null || position < baseline) a.baseline = position
        else if (position - baseline >= 0.25) {
            report(a, "clock_advanced", extra + " frame_verified=false")
            pending = null
        }
    }

    fun expire() {
        val a = pending ?: return
        if (nowMs() - a.start >= 15_000) interrupt(SeekTimingEnd.TIMEOUT)
    }

    fun interrupt(reason: SeekTimingEnd = SeekTimingEnd.INTERRUPTED) {
        val a = pending ?: return
        report(a, reason.name.lowercase())
        pending = null
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) interrupt(SeekTimingEnd.BACKGROUND)
    }

    private companion object { val attempts = AtomicLong() }
}

/** 手机页面拥有诊断；共用控制器提交入口覆盖媒体会话，后台与离页清理旧测量。 */
@Composable
internal fun ObserveSeekTiming(controller: PlayerController) {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val timing = remember(controller) {
        SeekTiming { message -> scope.launch(Dispatchers.IO) { Logs.d("lp-playback", message) } }
    }
    DisposableEffect(controller, owner, timing) {
        timing.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        controller.seekTiming = timing
        val visibility = LifecycleEventObserver { _, _ ->
            timing.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(visibility)
        onDispose {
            timing.interrupt()
            timing.setActive(false)
            if (controller.seekTiming === timing) controller.seekTiming = null
            owner.lifecycle.removeObserver(visibility)
        }
    }
    LaunchedEffect(timing) {
        while (true) { delay(250); timing.expire() }
    }
}
