package xyz.linplayer.app.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

internal const val BUFFER_MIB = 1024L * 1024
internal const val BUFFER_MIN_MIB = 64
internal const val BUFFER_MAX_MIB = 512

/** 流媒体首播和seek后以半秒媒体缓冲放行，断流恢复与本地策略保持原值。 */
@OptIn(UnstableApi::class)
internal fun playbackLoadControl(targetBytes: Long): DefaultLoadControl = DefaultLoadControl.Builder().apply {
    if (targetBytes > 0) {
        setTargetBufferBytes(targetBytes.toInt())
        setPrioritizeTimeOverSizeThresholds(false)
        // 原通用容量配置会让本地播放沿用流媒体门槛，分类设置后显式保留它。
        setBufferDurationsMsForLocalPlayback(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }
    setBufferDurationsMsForStreaming(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 500, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
}.build()
