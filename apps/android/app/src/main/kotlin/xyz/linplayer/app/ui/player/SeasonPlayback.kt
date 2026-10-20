package xyz.linplayer.app.ui.player

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.arr

/** 队列由核心实时确认；保留详情加载屏障，不把失败当季末。 */
internal suspend fun playbackSeason(app: AppState, detail: JsonObject?, itemId: String): List<Item> {
    checkNotNull(detail) { "无法加载播放信息，请重试" }
    return app.call("emby.playbackSeason", JsonObject(mapOf("item_id" to JsonPrimitive(itemId)))).arr().mapNotNull { Item.from(it) }
}
