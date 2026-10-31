package xyz.linplayer.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * 「时间真的往前走了」:两次状态之间往前走了一点,但没大到像一次跳转。
 *
 * ☠ 续播时第一次状态就从 0 跳到续播点。拿「比上一次大」判的话起播即放行:
 *   黑幕提前撤,一帧没出就 eof 的起播失败被当成「播完」静默退出。
 * ★ 上限 2 秒:状态 4 Hz,4 倍速一拍最多走 1 秒,留一倍余量。
 */
internal fun advancedNaturally(prev: Double, now: Double): Boolean = now - prev in 0.05..2.0

/** 首帧只解除画面遮挡，不替代自然推进及播完判据。 */
@Composable
internal fun rememberRenderedFirstFrame(player: Player?, playbackKey: Any): Boolean {
    val rendered = remember(player, playbackKey) { mutableStateOf(false) }
    DisposableEffect(player, playbackKey) {
        var active = true
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // 停止后仍可能收到已排队的首帧通知，不能提前解除下一次起播的遮罩。
                if (active && player?.playbackState != Player.STATE_IDLE) rendered.value = true
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (active) rendered.value = false
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (active && playbackState == Player.STATE_IDLE) rendered.value = false
            }
        }
        player?.addListener(listener)
        onDispose {
            active = false
            player?.removeListener(listener)
        }
    }
    return rendered.value
}
