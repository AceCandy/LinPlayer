package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.displayBlock
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.Dim2
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.theme.Sp

/**
 * 换源(D233 D259 D261):在其它允许聚合的 Emby 服务器里找同一部,候选边找边出;
 * 按「同一部 / 可能是 / 不像」分档。选中后切到那台服务器并打开它的详情。
 */
@Composable
internal fun EmbySwitchSource(nav: NavController, itemId: String, onClose: () -> Unit) {
    val app = LocalApp.current
    val privateSpace by app.privateSpace.collectAsState()
    if (privateSpace) return
    val scope = rememberCoroutineScope()
    val session = remember(itemId) { app.session.value }
    val cands = remember { mutableStateListOf<JsonObject>() }
    var done by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, retry) {
        cands.clear(); done = false; failure = null
        val d = runCatching { app.call("emby.itemDetail", args("item_id" to itemId)).obj()!! }
            .getOrElse { app.report(it); onClose(); return@LaunchedEffect }
        val title = d.str("series_name")?.takeIf { it.isNotEmpty() } ?: d.str("name") ?: ""
        val result = app.displayBlock("source.switchCandidates",
            args("server_id" to (app.session.value?.server ?: ""), "title" to title, "year" to (d.dbl("year") ?: 0.0)),
            retainOnFailure = false) { part -> cands.addAll(part.arr().mapNotNull { it.obj() }) }
        when (result) {
            is Block.Ok -> { cands.clear(); cands.addAll(result.value.arr().mapNotNull { it.obj() }) }
            is Block.Fail -> failure = result.message
            else -> Unit
        }
        done = true
    }
    fun pick(c: JsonObject) {
        if (picking || app.privateSpace.value) return
        picking = true
        scope.launch {
            var switched = false
            try {
                check(app.session.value == session) { "当前服务器已切换，请重新打开换源" }
                val serverId = c.str("server_id")?.takeIf { it.isNotBlank() } ?: error("换源候选缺少 server_id")
                val e = c["emby_item"].obj() ?: error("换源候选缺少条目")
                val id = e.str("id")?.takeIf { it.isNotBlank() } ?: error("换源候选缺少 id")
                val epoch = app.spaceRevision.value
                val accounts = app.call("account.setActiveServer", args("server_id" to serverId))
                switched = true
                app.refreshSession()
                if (app.privateSpace.value || app.spaceRevision.value != epoch) throw CancellationException("浏览空间已切换")
                val selected = accounts.arr().mapNotNull { it.obj() }.firstOrNull { it.str("server") == serverId && it.bool("active") }
                val next = app.session.value
                check(next != null && selected.str("line_url") == next.server && selected.str("user_id") == next.userId) {
                    "目标服务器会话尚未就绪，请重试"
                }
                nav.navigate(Route.Home) { popUpTo(nav.graph.id) }
                nav.navigate(Route.Detail(id, e.str("type_") ?: "Series"))
                onClose()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                failure = e.message ?: "换源失败"
                if (switched) {
                    nav.navigate(Route.Home) { popUpTo(nav.graph.id) }
                    app.toast(failure!!)
                    onClose()
                }
            }
            finally { picking = false }
        }
    }
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(tv, cands.isNotEmpty(), failure) {
        if (tv && (cands.isNotEmpty() || failure != null)) {
            androidx.compose.runtime.withFrameNanos { }
            focus.requestFocus()
        }
    }
    LpDialog({ if (!picking) onClose() }, "换源") {
        Column(Modifier.focusRequester(focus), verticalArrangement = Arrangement.spacedBy(Sp.x2)) {
            if (!done) Dim3("找其它服务器…")
            else if (cands.isEmpty() && failure == null) Dim2("别的服务器里都没找到这部片。可以给更多服务器打开「允许聚合」。")
            if (picking) Dim3("正在切换…")
            failure?.let { xyz.linplayer.app.ui.components.ErrorState(it, { retry++ }) }
            LazyColumn(Modifier.height(360.dp)) {
                items(cands) { c ->
                    val tier = when ((c.long("tier") ?: 0L).toInt()) { 0 -> "同一部"; 1 -> "可能是"; else -> "不像" }
                    OptRow(c["emby_item"].obj().str("name") ?: "", { pick(c) }, sub = "${c.str("server_name")} · $tier")
                }
            }
        }
    }
}
