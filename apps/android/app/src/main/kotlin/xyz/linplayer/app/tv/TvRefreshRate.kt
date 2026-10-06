package xyz.linplayer.app.tv

import android.app.Activity
import android.view.Display
import android.view.Window
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.player.PlayerController
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.round

private fun matches(refreshRate: Float, fps: Float): Boolean {
    if (!fps.isFinite() || fps <= 0 || !refreshRate.isFinite() || refreshRate <= 0) return false
    val multiple = round(refreshRate / fps)
    // 容差以 Hz 计，不能把 23.976/24 或 59.94/60 误当成同一帧率。
    return multiple >= 1 && abs(refreshRate - fps * multiple) <= 0.01f
}

/** 只挑同分辨率的整倍频；已匹配时保留当前模式，避免多余切屏。 */
internal fun matchingTvMode(current: Display.Mode, modes: Array<Display.Mode>, fps: Float): Display.Mode? {
    val candidates = modes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && matches(it.refreshRate, fps)
    }
    return candidates.firstOrNull { it.modeId == current.modeId } ?: candidates.minByOrNull { it.refreshRate }
}

/** 显示偏好归播放页拥有，切集不释放；离页后迟到的帧率不能再修改窗口。 */
internal class TvRefreshRate(private val window: Window, private val display: Display) {
    private val original = window.attributes.preferredDisplayModeId
    private var closed = false

    fun match(fps: Float) {
        if (closed) return
        // 设备可忽略模式请求；该辅助功能失败不能中断播放。
        runCatching {
            val current = display.mode
            val modes = display.supportedModes
            val requested = modes.firstOrNull { it.modeId == window.attributes.preferredDisplayModeId }
            if (requested != null && requested.physicalWidth == current.physicalWidth &&
                requested.physicalHeight == current.physicalHeight && matches(requested.refreshRate, fps)) return
            val mode = matchingTvMode(current, modes, fps)
            if (mode == null) restore()
            else if (mode.modeId != current.modeId || window.attributes.preferredDisplayModeId != 0) setMode(mode.modeId)
        }
    }

    fun restore() { if (!closed) runCatching { setMode(original) } }

    fun close() { restore(); closed = true }

    private fun setMode(id: Int) {
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == id) return
        attributes.preferredDisplayModeId = id
        window.attributes = attributes
    }
}

/** Media3 准备好当前媒体后才读帧率；未知格式不沿用旧片源或猜测帧率。 */
@OptIn(UnstableApi::class)
internal fun tvVideoFrameRate(player: ExoPlayer): Float? {
    if (player.isReleased || player.playbackState != Player.STATE_READY) return null
    player.videoFormat?.frameRate?.takeIf { it.isFinite() && it > 0 }?.let { return it }
    for (group in player.currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until group.length) if (group.isTrackSelected(i)) {
            group.getTrackFormat(i).frameRate.takeIf { it.isFinite() && it > 0 }?.let { return it }
        }
    }
    return null
}

/** TV 双内核共用窗口匹配；手机继续使用原有 Surface 策略。 */
@OptIn(UnstableApi::class)
@Composable
internal fun MatchTvRefreshRate(app: AppState, controller: PlayerController, exo: ExoPlayer?, attempt: Int) {
    val activity = LocalContext.current as? Activity
    val rates = remember(activity) {
        activity?.window?.let { window -> window.decorView.display?.let { TvRefreshRate(window, it) } }
    }
    DisposableEffect(rates) { onDispose { rates?.close() } }
    DisposableEffect(rates, exo) {
        val strategy = if (rates != null) exo?.videoChangeFrameRateStrategy else null
        if (strategy != null) exo?.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        onDispose {
            if (strategy != null && exo != null && !exo.isReleased) exo.setVideoChangeFrameRateStrategy(strategy)
        }
    }
    val ready = controller.ready
    LaunchedEffect(rates, controller, exo, attempt, ready) {
        if (!ready || rates == null) return@LaunchedEffect
        var unknown = 0
        while (true) {
            delay(500)
            val fps = if (exo != null) tvVideoFrameRate(exo) else {
                val opts = runCatching { app.call("player.opts") }.getOrNull().obj()
                coroutineContext.ensureActive()
                val filters = runCatching { app.call("player.mpvGet", args("name" to "vf")) }.getOrNull().obj()
                coroutineContext.ensureActive()
                if (!controller.ready) return@LaunchedEffect
                // 补帧按输出帧率工作，不能按源帧率降频；读取失败也保留原偏好。
                if (filters == null || filters.str("value").orEmpty().contains("lpinterp")) {
                    rates.restore()
                    delay(500)
                    continue
                }
                opts.str("container-fps")?.toFloatOrNull()
            }
            coroutineContext.ensureActive()
            if (!controller.ready) return@LaunchedEffect
            if (fps != null && fps.isFinite() && fps > 0) { unknown = 0; rates.match(fps) }
            else if (++unknown >= 10) rates.restore()
            delay(500)
        }
    }
}
