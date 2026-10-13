package xyz.linplayer.app.tv

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.ui.pages.moved

/** TV 使用独立侧栏和明确的上下移动动作，所有操作均可通过遥控器完成。 */
@Composable
internal fun BoxScope.DanmakuSourcesPanel(onClose: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { sources = app.call("danmaku.getDanmakuConfig").arr().mapNotNull { it.obj() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = e.message ?: "读取失败" }
    }
    suspend fun save(next: List<JsonObject>) {
        app.call("danmaku.setDanmakuConfig", JsonObject(mapOf("sources" to JsonArray(next))))
        sources = next
    }
    fun update(next: List<JsonObject>) {
        if (busy) return
        scope.launch {
            busy = true
            try { save(next); status = "" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "保存失败" }
            finally { busy = false }
        }
    }
    val editing = draft
    if (editing != null) {
        DanmakuSourceEditor(editing, { draft = null }) { source ->
            app.call("danmaku.testSource", JsonObject(mapOf("source" to source)))
            save(if (sources.any { it.str("id") == source.str("id") }) sources.map {
                if (it.str("id") == source.str("id")) source else it
            } else sources + source)
            draft = null
        }
        return
    }
    val index = sources.indexOfFirst { it.str("id") == selected }
    val source = sources.getOrNull(index)
    TvSidePanel(if (source == null) "弹幕源优先级" else source.str("name") ?: "弹幕源", onClose,
        back = if (source == null) null else ({ selected = null })) {
        if (status.isNotBlank()) PanelItem(status, enabled = false)
        if (source == null) {
            PanelItem("添加源", focused = true, onClick = {
                draft = JsonObject(mapOf("id" to JsonPrimitive(java.util.UUID.randomUUID().toString())))
            })
            sources.forEach { s ->
                PanelItem(s.str("name") ?: "弹幕源", value = if (s.bool("disabled")) "停用" else "启用",
                    chevron = true, onClick = { selected = s.str("id") })
            }
        } else {
            PanelItem("启用", switch = !source.bool("disabled"), focused = true, onClick = {
                update(sources.mapIndexed { i, s -> if (i == index) JsonObject(s + ("disabled" to JsonPrimitive(!s.bool("disabled")))) else s })
            })
            PanelItem("上移", enabled = index > 0 && !busy, onClick = { update(sources.moved(index, index - 1)) })
            PanelItem("下移", enabled = index < sources.lastIndex && !busy, onClick = { update(sources.moved(index, index + 1)) })
            if (!source.bool("official")) {
                PanelItem("编辑", onClick = { draft = source })
                PanelItem("删除", onClick = { update(sources.filterIndexed { i, _ -> i != index }); selected = null })
            }
        }
    }
}

@Composable
private fun BoxScope.DanmakuSourceEditor(initial: JsonObject, onBack: () -> Unit, onSave: suspend (JsonObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    TvSidePanel("编辑弹幕源", onBack) {
        @Composable fun field(key: String, label: String) {
            TvTextField(draft.str(key) ?: "", { draft = JsonObject(draft + (key to JsonPrimitive(it))) }, label, 218.dp,
                Modifier.memo("dm.source.$key"), password = key == "token" || key == "app_secret")
        }
        field("name", "名称")
        field("api_url", "API 地址")
        PanelItem("高级鉴权", switch = advanced, onClick = { advanced = !advanced })
        if (advanced) {
            val modes = listOf("none", "headerToken", "queryToken", "dandanplaySignature", "pathToken")
            val labels = listOf("自动识别", "请求头令牌", "查询参数令牌", "签名", "路径令牌（包含在地址中）")
            val index = modes.indexOf(draft.str("auth_type") ?: "none").coerceAtLeast(0)
            PanelItem("鉴权方式", value = labels[index], onClick = {
                draft = JsonObject(draft + ("auth_type" to JsonPrimitive(modes[(index + 1) % modes.size])))
            })
            if (index == 1 || index == 2) field("token", "Token")
            if (index == 3) { field("app_id", "App ID"); field("app_secret", "App Secret") }
        }
        if (error.isNotBlank()) PanelItem(error, enabled = false)
        PanelItem(if (busy) "测试中…" else "测试并保存", enabled = !busy, onClick = {
            scope.launch {
                busy = true; error = ""
                try { onSave(draft) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "连接测试失败" }
                finally { busy = false }
            }
        })
    }
}
