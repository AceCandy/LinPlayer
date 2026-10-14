package xyz.linplayer.app.ui.player

import kotlinx.serialization.Serializable

/** 详情页本次预选，仅随当前媒体版本起播；index为空表示明确关闭字幕。 */
@Serializable
internal data class InitialSubtitle(
    val sourceId: String,
    val index: Long? = null,
    val title: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val external: Boolean = false,
)

/** 统一服务端codec与Media3原始MIME，不把同语言的不同字幕格式视为同一轨。 */
internal fun subtitleCodec(codec: String?): String? = when (val value = codec?.lowercase()?.takeIf { it.isNotBlank() }) {
    "ass", "ssa", "text/x-ssa" -> "ass"
    "srt", "subrip", "application/x-subrip" -> "srt"
    "vtt", "webvtt", "text/vtt" -> "vtt"
    "hdmv_pgs_subtitle", "application/pgs" -> "pgs"
    "dvd_subtitle", "application/vobsub" -> "vobsub"
    else -> value
}
