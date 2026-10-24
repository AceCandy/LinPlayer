package xyz.linplayer.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 扩展页必须显示不支持原因，不能沿用普通可选功能的静默降级。 */
suspend fun AppState.extensionBlock(command: String, args: JsonObject? = null,
    serverId: String? = null,
    retainOnFailure: Boolean = true,
    onCached: (JsonElement) -> Unit,
): Block<JsonElement> {
    val primary = serverId
    val request = JsonObject(args.orEmpty() + listOfNotNull(primary?.let { "server_id" to JsonPrimitive(it) }).toMap())
    fun withSource(value: JsonElement): JsonElement = if (primary == null) value else
        JsonArray(value.arr().map { row -> row.obj()?.let {
            JsonObject(it + ("server_id" to JsonPrimitive(primary)))
        } ?: row })
    val result = displayBlock(command, request, retainOnFailure = retainOnFailure, onCached = { onCached(withSource(it)) })
    return when {
        result is Block.Ok -> Block.Ok(withSource(result.value))
        result is Block.Fail && result.code == "E_UNSUPPORTED" -> Block.Fail("E_INVALID", result.message)
        else -> result
    }
}

/** 点击实时精确匹配；换账号后不执行旧会话导航。 */
suspend fun AppState.openExtension(entry: JsonObject, open: (String, String) -> Unit) {
    val identity = session.value
    val primary = aggregatePrimary.value
    when (val result = block("emby.extResolve", JsonObject(entry.filterKeys {
        it in setOf("source", "external_id", "media_type", "server_id")
    }))) {
        is Block.Ok -> {
            currentCoroutineContext().ensureActive()
            if (session.value == identity && aggregatePrimary.value == primary) result.value.obj()?.let {
                val id = it.str("item_id") ?: return@let
                val type = it.str("item_type") ?: return@let
                val server = entry.str("server_id")
                if (server != null && server != identity?.server && !activateMediaServer(server)) return@let
                open(id, type)
            }
        }
        is Block.Fail -> if (session.value == identity) toast(result.message)
        else -> Unit
    }
}

/** 普通详情沿当前会话读取，跨服打开前必须先完成服务器切换。 */
suspend fun AppState.activateMediaServer(serverId: String): Boolean {
    return try {
        val changed = call("account.setActiveServer", JsonObject(mapOf("server_id" to JsonPrimitive(serverId))))
        val target = changed.arr().mapNotNull { it.obj() }.firstOrNull { it.str("server") == serverId && it.bool("active") }
            ?: error("未能确认目标服务器，请重试")
        refreshSession()
        currentCoroutineContext().ensureActive()
        check(session.value != null && session.value?.server == target.str("line_url") &&
            session.value?.userId == target.str("user_id")) { "目标服务器会话尚未就绪，请重试" }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
        false
    }
}
