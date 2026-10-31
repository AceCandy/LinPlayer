package xyz.linplayer.app.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpText

/** 来源/校时属于当前内容；样式在核心全局保存，子页只负责展示与提交。 */
@Composable
internal fun DanmakuPanel(app: AppState, scope: CoroutineScope, itemId: String, page: String,
    onPage: (String) -> Unit, onSearch: () -> Unit) {
    val ready = DanmakuStyle.loaded.value
    var more by remember(page) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (page != "quick") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage("quick") }) {
                Icon(LpIcons.back, "返回弹幕菜单", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(when (page) {
                "style" -> "显示设置"
                "offset" -> "时间偏移"
                else -> "更换弹幕"
            },
                Modifier.weight(1f), color = Color.White, style = LpText.action)
        }
        DanmakuStyle.loadError.value?.let {
            PlayerMenuNote(it)
            PlayerMenuRow("重试", { scope.launch { DanmakuStyle.load(app) } })
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when (page) {
                "quick" -> {
                    item("on") { DanmakuToggle("显示弹幕", DanmakuStyle.enabled.value, ready) {
                        scope.launch { toggleDanmaku(app, itemId, it) }
                    } }
                    item("source") { PlayerMenuNote("当前来源：${DanmakuStyle.source.value.ifBlank { "未匹配" }}", maxLines = 2) }
                    item("offset") { PlayerMenuRow("时间偏移", { onPage("offset") },
                        badge = "%.1f秒".format(DanmakuStyle.offset.doubleValue), icon = LpIcons.sync) }
                    item("style") { PlayerMenuRow("显示设置", { onPage("style") }, icon = LpIcons.settings) }
                    item("match") { PlayerMenuRow("更换弹幕", { onPage("match") }, icon = LpIcons.search, divider = false) }
                }
                "offset" -> {
                    item("step") { StepRow("偏移", "%.1f 秒".format(DanmakuStyle.offset.doubleValue)) { up ->
                        scope.launch { DanmakuStyle.setOffset(app, DanmakuStyle.offset.doubleValue + if (up) .5 else -.5) }
                    } }
                    item("note") { PlayerMenuNote("负数提前，正数延后；每次调整 0.5 秒。") }
                    item("reset") { PlayerMenuRow("重置弹幕时间", { scope.launch { DanmakuStyle.setOffset(app, 0.0) } }, divider = false) }
                }
                "match" -> {
                    item("source") { PlayerMenuNote("当前来源：${DanmakuStyle.source.value.ifBlank { "未匹配" }}") }
                    item("search") { PlayerMenuRow("搜索弹幕", onSearch, icon = LpIcons.search) }
                    item("again") { PlayerMenuRow("重新匹配这一集", {
                        scope.launch { loadDanmakuFor(app, itemId, loud = true, ignoreMemory = true) }
                    }) }
                    item("forget") { PlayerMenuRow("清除当前匹配记忆", {
                        scope.launch { resolveDanmakuFor(app, itemId, forget = true, loud = true) }
                    }, divider = false) }
                }
                "style" -> {
                    item("area") { PlayerMenuRow("显示区域", {
                        scope.launch { DanmakuStyle.set(app, "area") { DanmakuStyle.nextArea(DanmakuStyle.area.doubleValue) } }
                    }, badge = DanmakuStyle.areaLabel(DanmakuStyle.area.doubleValue), enabled = ready, divider = false) }
                    item("opacity") { PlayerStyleSlider("不透明度", DanmakuStyle.opacity.doubleValue.toFloat(), .1f..1f, 8,
                        enabled = ready, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${kotlin.math.round(it * 100).toInt()}%" }) {
                        scope.launch { DanmakuStyle.set(app, "opacity", it.toDouble()) }
                    } }
                    item("scale") { PlayerStyleSlider("字体大小", DanmakuStyle.scale.doubleValue.toFloat(), .1f..3f, 28,
                        enabled = ready, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${kotlin.math.round(it * 100).toInt()}%" }) {
                        scope.launch { DanmakuStyle.set(app, "scale", it.toDouble()) }
                    } }
                    item("speed") { PlayerStyleSlider("弹幕速度", DanmakuStyle.speed.doubleValue.toFloat(), .1f..3f, 28,
                        enabled = ready, resetKey = DanmakuStyle.saveRevision.intValue, format = { "%.1f×".format(it) }) {
                        scope.launch { DanmakuStyle.set(app, "speed", it.toDouble()) }
                    } }
                    item("more") { PlayerMenuRow("更多设置", { more = !more }, badge = if (more) "收起" else "展开", divider = false) }
                    if (more) listOf(
                        Triple("粗体", "bold", DanmakuStyle.bold),
                        Triple("合并重复", "merge", DanmakuStyle.merge),
                        Triple("防止重叠", "prevent_overlap", DanmakuStyle.preventOverlap),
                        Triple("进度条热力图", "heatmap", DanmakuStyle.heatmap),
                    ).forEach { (label, key, state) ->
                        item(key) { DanmakuToggle(label, state.value, ready) {
                            scope.launch { DanmakuStyle.set(app, key) { !state.value } }
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DanmakuToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled,
        role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Color.White.copy(alpha = if (enabled) .9f else .4f), style = LpText.action)
        Switch(checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Lp.colors.accFg, checkedTrackColor = Lp.colors.acc,
                uncheckedThumbColor = Color.White.copy(alpha = .7f), uncheckedTrackColor = Color.White.copy(alpha = .15f)))
    }
}
