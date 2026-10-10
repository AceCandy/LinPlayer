package xyz.linplayer.app.ui.player

import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.str

/** 连播只取当前季；无法确认当前集时保留错误，不能把加载失败当季末。 */
internal suspend fun playbackSeason(app: AppState, detail: JsonObject?, itemId: String): List<Item> {
    checkNotNull(detail) { "无法加载播放信息，请重试" }
    if (detail.str("type_") != "Episode") return emptyList()
    val season = detail.str("season_id")?.takeIf { it.isNotBlank() }
    val number = detail.long("season_no")
    val parent = season ?: detail.str("series_id")?.takeIf { it.isNotBlank() && number != null }
    checkNotNull(parent) { "无法确认当前季，请重试" }
    val episodes = app.seasonEpisodes(parent).filter { number == null || it.seasonNo == number }
    check(episodes.any { it.id == itemId }) { "当前集不在分集列表中，请重试" }
    return episodes.sortedBy { it.episodeNo }
}
