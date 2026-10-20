package xyz.linplayer.app.ui.player

import kotlinx.serialization.json.*
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

/** 两种内核共用的偏好口径:正则优先,语言次之,未指定偏好时优先简体字幕，再保留服务端默认。 */
internal data class TrackPrefs(
    val subLang: String? = null, val audioLang: String? = null,
    val subRegex: String = "", val audioRegex: String = "",
    val subEnabled: Boolean = true,
)

internal data class TrackCandidate(val title: String, val lang: String, val selected: Boolean)

internal suspend fun xyz.linplayer.app.data.AppState.preferredTrackIndex(tracks: List<TrackCandidate>, lang: String?, pattern: String, subtitle: Boolean): Int {
    val kind = if (subtitle) "sub" else "audio"
    val rows = tracks.mapIndexed { index, track -> buildJsonObject {
        put("id", index.toString()); put("kind", kind); put("title", track.title)
        put("lang", track.lang); put("selected", track.selected)
    } }
    val result = call("player.selectTrackPreferences", buildJsonObject {
        put("tracks", JsonArray(rows))
        put("prefs", buildJsonObject {
            put("sub_enabled", true)
            put("sub_lang", if (subtitle) lang.orEmpty() else "")
            put("sub_regex", if (subtitle) pattern else "")
            put("audio_lang", if (!subtitle) lang.orEmpty() else "")
            put("audio_regex", if (!subtitle) pattern else "")
        })
    })
    return result.obj().str(kind)?.toIntOrNull() ?: -1
}
