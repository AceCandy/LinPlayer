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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpText
import kotlin.math.roundToInt

/** 来源/校时属于当前内容；样式在核心全局保存，子页只负责展示与提交。 */
@Composable
internal fun DanmakuPanel(app: AppState, scope: CoroutineScope, itemId: String, page: String,
    onPage: (String) -> Unit, onSearch: () -> Unit) {
    val ready = DanmakuStyle.loaded.value
    val accent = Lp.colors.acc
    fun set(key: String, value: Any) {
        if (ready) scope.launch(start = CoroutineStart.UNDISPATCHED) { DanmakuStyle.set(app, key, value) }
    }
    Column(Modifier.fillMaxWidth()) {
        if (page != "quick") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage("quick") }) {
                Icon(LpIcons.back, "返回弹幕菜单", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(when (page) { "style" -> "弹幕样式"; "offset" -> "时间偏移"; else -> "搜索与匹配" },
                Modifier.weight(1f), color = Color.White, style = LpText.action)
            if (page == "style") Text("全局生效", Modifier.padding(end = 16.dp),
                color = Color.White.copy(alpha = .6f), style = LpText.caption)
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
                    item("source") { PlayerMenuNote(DanmakuStyle.source.value.ifBlank { "尚未匹配" }, maxLines = 2) }
                    item("offset") { PlayerMenuRow("时间偏移", { onPage("offset") },
                        badge = "%.1f秒".format(DanmakuStyle.offset.doubleValue), icon = LpIcons.sync) }
                    item("style") { PlayerMenuRow("弹幕样式", { onPage("style") }, icon = LpIcons.settings) }
                    item("match") { PlayerMenuRow("搜索与匹配", { onPage("match") }, icon = LpIcons.search) }
                }
                "offset" -> {
                    item("step") { StepRow("偏移", "%.1f 秒".format(DanmakuStyle.offset.doubleValue)) { up ->
                        scope.launch { DanmakuStyle.setOffset(app, DanmakuStyle.offset.doubleValue + if (up) .5 else -.5) }
                    } }
                    item("note") { PlayerMenuNote("负数提前，正数延后；每次调整0.5秒。") }
                    item("reset") { PlayerMenuRow("重置弹幕时间", { scope.launch { DanmakuStyle.setOffset(app, 0.0) } }) }
                }
                "match" -> {
                    item("source") { PlayerMenuNote(DanmakuStyle.source.value.ifBlank { "尚未匹配" }) }
                    item("search") { PlayerMenuRow("搜索弹幕", onSearch, icon = LpIcons.search) }
                    item("again") { PlayerMenuRow("重新匹配这一集", {
                        scope.launch { loadDanmakuFor(app, itemId, loud = true, ignoreMemory = true) }
                    }) }
                    item("forget") { PlayerMenuRow("清除匹配记忆", {
                        scope.launch { resolveDanmakuFor(app, itemId, forget = true, loud = true) }
                    }) }
                }
                "style" -> {
                    item("roll") { PlayerStyleSlider("滚动行数", DanmakuStyle.rollLines.intValue.toFloat(), 0f..20f, 19,
                        enabled = ready, accent = accent, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${it.roundToInt()}行" }) { set("roll_lines", it.roundToInt()) } }
                    item("top") { PlayerStyleSlider("顶部行数", DanmakuStyle.topLines.intValue.toFloat(), 0f..20f, 19,
                        enabled = ready, accent = accent, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${it.roundToInt()}行" }) { set("top_lines", it.roundToInt()) } }
                    item("bottom") { PlayerStyleSlider("底部行数", DanmakuStyle.bottomLines.intValue.toFloat(), 0f..20f, 19,
                        enabled = ready, accent = accent, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${it.roundToInt()}行" }) { set("bottom_lines", it.roundToInt()) } }
                    item("scale") { PlayerStyleSlider("字号", DanmakuStyle.scale.doubleValue.toFloat(), .1f..3f, 28,
                        enabled = ready, accent = accent, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${(it * 100).roundToInt()}%" }) { set("scale", (it * 10).roundToInt() / 10.0) } }
                    item("opacity") { PlayerStyleSlider("不透明度", DanmakuStyle.opacity.doubleValue.toFloat(), .1f..1f, 8,
                        enabled = ready, accent = accent, resetKey = DanmakuStyle.saveRevision.intValue, format = { "${(it * 100).roundToInt()}%" }) { set("opacity", (it * 10).roundToInt() / 10.0) } }
                    item("speed") { PlayerStyleSlider("滚动速度", DanmakuStyle.speed.doubleValue.toFloat(), .1f..3f, 28,
                        enabled = ready, accent = accent, resetKey = DanmakuStyle.saveRevision.intValue, format = { "%.1f×".format(it) }) { set("speed", (it * 10).roundToInt() / 10.0) } }
                    item("bold") { DanmakuToggle("粗体", DanmakuStyle.bold.value, ready) { set("bold", it) } }
                    item("merge") { DanmakuToggle("合并重复", DanmakuStyle.merge.value, ready) { set("merge", it) } }
                    item("overlap") { DanmakuToggle("防止重叠", DanmakuStyle.preventOverlap.value, ready) { set("prevent_overlap", it) } }
                    item("heat") { DanmakuToggle("进度条热力图", DanmakuStyle.heatmap.value, ready) { set("heatmap", it) } }
                    item("area") { PlayerMenuRow("滚动范围", { set("area", DanmakuStyle.nextArea(DanmakuStyle.area.doubleValue)) },
                        badge = DanmakuStyle.areaLabel(DanmakuStyle.area.doubleValue), enabled = ready) }
                    item("note") { PlayerMenuNote("行数为0时隐藏该类弹幕。防止重叠开启时，排不下的弹幕会略过。屏蔽词在设置里添加。", maxLines = 6) }
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
