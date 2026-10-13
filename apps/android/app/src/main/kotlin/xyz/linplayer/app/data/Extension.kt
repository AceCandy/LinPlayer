package xyz.linplayer.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 扩展页必须显示不支持原因，不能沿用普通可选功能的静默降级。 */
suspend fun AppState.extensionBlock(command: String, args: JsonObject? = null,
    onCached: (JsonElement) -> Unit,
): Block<JsonElement> {
    val result = displayBlock(command, args, onCached = onCached)
    return if (result is Block.Fail && result.code == "E_UNSUPPORTED")
        Block.Fail("E_INVALID", result.message) else result
}

/** 点击实时精确匹配；换账号后不执行旧会话导航。 */
suspend fun AppState.openExtension(entry: JsonObject, open: (String, String) -> Unit) {
    val identity = session.value
    when (val result = block("emby.extResolve", JsonObject(entry.filterKeys {
        it in setOf("source", "external_id", "media_type")
    }))) {
        is Block.Ok -> {
            currentCoroutineContext().ensureActive()
            if (session.value == identity) result.value.obj()?.let {
                open(it.str("item_id") ?: return@let, it.str("item_type") ?: return@let)
            }
        }
        is Block.Fail -> if (session.value == identity) toast(result.message)
        else -> Unit
    }
}
