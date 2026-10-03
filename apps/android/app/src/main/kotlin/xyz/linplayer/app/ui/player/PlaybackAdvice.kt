package xyz.linplayer.app.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** 保留播放器真实错误,只为已知原因补充对应操作建议。 */
internal fun playbackAdvice(error: PlaybackException): String {
    val http = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    val advice = when {
        http == 401 -> "登录状态失效,请重新登录后播放。"
        http == 403 -> "服务器拒绝访问,请检查账号权限或选择其它版本。"
        http == 404 -> "服务器找不到媒体,请刷新条目或选择其它版本。"
        error.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT) ->
            "请检查网络或切换线路后重试。"
        error.errorCode in 4001..4006 -> "设备无法解码当前版本,请换用 mpv 内核或选择其它编码版本。"
        error.errorCode in 7000..7001 -> "视频处理失败,请换用 mpv 内核后重试。"
        else -> ""
    }
    return "ExoPlayer: ${error.errorCodeName} ${error.message.orEmpty()}" +
        if (advice.isEmpty()) "" else "\n$advice"
}
