package xyz.linplayer.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.ui.pages.args

/** 密度随当前语料及开关更新；输入变化立即清空，迟到结果不能恢复旧图。 */
@Composable
internal fun rememberDanmakuHeatmap(app: AppState, duration: Double): List<Float> {
    val layout = DanmakuStyle.layout.value
    val offset = DanmakuStyle.offset.doubleValue
    val show = DanmakuStyle.enabled.value && DanmakuStyle.heatmap.value && layout != null && duration.isFinite() && duration > 0
    var heat by remember(app, layout, offset, duration, show) { mutableStateOf<List<Float>>(emptyList()) }
    LaunchedEffect(app, layout, offset, duration, show) {
        if (!show) return@LaunchedEffect
        val result = runCatching { app.call("player.danmakuHeatmap", args("buckets" to 120, "duration" to duration)) }.getOrNull()
        currentCoroutineContext().ensureActive()
        heat = result.arr().mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() }
    }
    return heat
}

/** 手机和TV共用进度条上方的弹幕密度绘制。 */
@Composable
internal fun DanmakuHeatmap(heat: List<Float>, modifier: Modifier) {
    if (heat.isEmpty()) return
    Canvas(modifier.semantics { contentDescription = "弹幕热力图" }) {
        val width = size.width / heat.size
        heat.forEachIndexed { i, value ->
            val height = value.coerceIn(0f, 1f) * size.height
            if (height > .5f) drawRect(Color(0xFFFF9D3F).copy(alpha = .4f),
                Offset(i * width, size.height - height), Size(width + .5f, height))
        }
    }
}
