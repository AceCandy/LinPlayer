package xyz.linplayer.app.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

internal const val BUFFER_MIB = 1024L * 1024
internal const val BUFFER_MIN_MIB = 64
internal const val BUFFER_MAX_MIB = 512

/** 容量是压缩媒体缓冲目标；保留默认起播时间，不限制解码/画面总内存。 */
@OptIn(UnstableApi::class)
internal fun playbackLoadControl(targetBytes: Long): DefaultLoadControl = DefaultLoadControl.Builder().apply {
    if (targetBytes > 0) {
        setTargetBufferBytes(targetBytes.toInt())
        setPrioritizeTimeOverSizeThresholds(false)
    }
}.build()
