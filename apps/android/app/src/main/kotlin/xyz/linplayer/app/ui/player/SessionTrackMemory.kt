package xyz.linplayer.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Session
import xyz.linplayer.app.data.str

/** 本次连续播放的账号与剧集边界；不跨服或跨账号沿用手选。 */
internal data class SessionTrackScope(val server: String, val userId: String, val seriesId: String)

/** 关闭字幕是明确意图，不能与缺少可辨识轨道身份混淆。 */
internal data class SessionTrackChoice(val identity: TrackIdentity? = null, val off: Boolean = false)

/** 由播放页持有，只保留当前剧的成功手选；离页释放，不持久化。 */
internal class SessionTrackMemory {
    private var scope: SessionTrackScope? = null
    private var choices by mutableStateOf<Map<String, SessionTrackChoice>>(emptyMap())

    fun enter(next: SessionTrackScope?) {
        if (scope != next) { choices = emptyMap(); scope = next }
    }

    fun record(key: SessionTrackScope?, kind: String, choice: SessionTrackChoice) {
        if (key != null && key == scope) choices = choices + (kind to choice)
    }

    fun choicesFor(key: SessionTrackScope?): Map<String, SessionTrackChoice> =
        if (key != null && key == scope) choices else emptyMap()
}

/** 只认真实Episode上下文；电影、本地与未知剧ID不传播记忆。 */
internal fun sessionTrackScope(session: Session?, detail: JsonObject?, itemId: String): SessionTrackScope? {
    val account = session ?: return null
    val series = detail.str("series_id")?.takeIf { it.isNotBlank() } ?: return null
    if (detail.str("id") != itemId || detail.str("type_") != "Episode" || account.server.isBlank() || account.userId.isBlank()) return null
    return SessionTrackScope(account.server, account.userId, series)
}
