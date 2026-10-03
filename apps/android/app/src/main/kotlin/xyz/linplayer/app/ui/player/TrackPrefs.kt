package xyz.linplayer.app.ui.player

/** 两种内核共用的偏好口径:正则优先,语言次之,没有命中时保留服务端默认。 */
internal data class TrackPrefs(
    val subLang: String? = null, val audioLang: String? = null,
    val subRegex: String = "", val audioRegex: String = "",
    val subEnabled: Boolean = true,
)

internal data class TrackCandidate(val title: String, val lang: String, val selected: Boolean)

internal fun preferredTrackIndex(tracks: List<TrackCandidate>, lang: String?, pattern: String, subtitle: Boolean): Int {
    val regex = pattern.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    if (regex != null) tracks.indexOfFirst { regex.containsMatchIn("${it.title} ${it.lang}".trim()) }.let { if (it >= 0) return it }
    if (!lang.isNullOrBlank()) tracks.indexOfFirst { it.lang.equals(lang.trim(), ignoreCase = true) }.let { if (it >= 0) return it }
    return if (subtitle && tracks.none { it.selected } && tracks.isNotEmpty()) 0 else -1
}
