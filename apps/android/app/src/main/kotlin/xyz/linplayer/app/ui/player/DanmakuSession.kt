package xyz.linplayer.app.ui.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.pages.args

/** 同一播放目标的挂载串行，晚到的自动匹配不能覆盖手选结果。 */
internal object DanmakuSession {
    var generation = 0L
    val applyLock = Mutex()
    var requestJob: kotlinx.coroutines.Job? = null
    fun invalidate() {
        generation++
        requestJob?.cancel()
        requestJob = null
    }
}

internal suspend fun resolveDanmakuFor(
    app: AppState, itemId: String, selection: JsonObject? = null,
    ignoreMemory: Boolean = false, forget: Boolean = false, loud: Boolean = false,
): Boolean {
    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
    if (DanmakuSession.requestJob !== job) DanmakuSession.requestJob?.cancel()
    DanmakuSession.requestJob = job
    val generation = ++DanmakuSession.generation
    val session = app.session.value
    fun valid() = generation == DanmakuSession.generation && session == app.session.value
    val identity = args("server" to (session?.server ?: ""), "user_id" to (session?.userId ?: ""),
        "token" to (session?.token ?: ""))
    try {
        val coreGeneration = if (forget) 0L else DanmakuSession.applyLock.withLock {
            currentCoroutineContext().ensureActive()
            if (!valid()) return false
            DanmakuStyle.layout.value = null
            DanmakuStyle.source.value = ""
            DanmakuStyle.offset.doubleValue = 0.0
            app.call("player.danmakuBegin").obj().long("generation") ?: error("缺少弹幕播放代数")
        }
        val detail = try {
            if (session == null) null else app.call("emby.itemDetail", JsonObject(identity + args("item_id" to itemId))).obj()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // 本机视频没有媒体库身份，手选仍可临时加载，但不写匹配记忆。
            if (selection == null) throw e else null
        }
        currentCoroutineContext().ensureActive()
        if (!valid()) return false
        val input = buildJsonObject {
            put("title", detail.str("series_name") ?: detail.str("name") ?: "本机视频")
            if (session != null && detail != null) put("item_id", itemId)
            for (key in listOf("series_id", "season_id", "episode_no", "season_no", "genres", "bgm_id")) {
                detail?.get(key)?.let { put(key, it) }
            }
        }
        val request = JsonObject(identity + buildJsonObject {
            put("input", input)
            put("ignore_memory", ignoreMemory)
            selection?.let { put("selection", it) }
        })
        if (forget) {
            app.call("danmaku.forget", JsonObject(identity + mapOf("input" to input)))
            if (valid()) app.toast("已清除本集及本季匹配记忆")
            return true
        }
        val result = app.call("danmaku.resolve", request).obj()
        currentCoroutineContext().ensureActive()
        if (!valid()) return false
        val match = result?.get("match").obj()
        val items = result?.get("items") ?: JsonArray(emptyList())
        DanmakuSession.applyLock.withLock {
            currentCoroutineContext().ensureActive()
            if (!valid()) return false
            app.call("player.danmakuSet", JsonObject(mapOf("items" to items, "generation" to JsonPrimitive(coreGeneration))))
            currentCoroutineContext().ensureActive()
            if (!valid()) return false
            DanmakuStyle.offset.doubleValue = 0.0
            DanmakuStyle.source.value = listOfNotNull(match.str("source_name"), match.str("anime_title"),
                match.str("episode_title")).joinToString(" · ")
            if ((result?.get("warnings") as? JsonArray)?.isNotEmpty() == true)
                DanmakuStyle.source.value += " · 部分弹幕加载失败"
            if (selection != null && match != null) {
                app.call("player.setDanmakuEnabled", args("enabled" to true))
                DanmakuStyle.enabled.value = true
            }
            DanmakuStyle.reloadLayout(app)
        }
        if (loud) app.toast(if (match == null) "这一集没匹配到弹幕，可以手动搜索" else "已挂载弹幕")
        return match != null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { if (valid()) { DanmakuStyle.source.value = "弹幕加载失败"; if (loud) app.report(e) }; return false }
}

internal fun danmakuSelection(sourceId: String, anime: JsonObject, episode: JsonObject) = buildJsonObject {
    put("source_id", sourceId)
    for (key in listOf("anime_id", "anime_title")) anime[key]?.let { put(key, it) }
    for (key in listOf("episode_id", "episode_title")) episode[key]?.let { put(key, it) }
}
