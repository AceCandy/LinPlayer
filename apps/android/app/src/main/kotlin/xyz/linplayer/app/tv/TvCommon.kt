package xyz.linplayer.app.tv

import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.menuKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.pages.Version
import xyz.linplayer.app.ui.pages.fmtRate
import xyz.linplayer.app.ui.pages.fmtSize

/* 页面之间共用的卡片口径、列表行、规格文案。 */

/** 卡片标题口径【继承 PC】:分集恒带剧名「剧名 · S1E5」,缺季号回落集名。 */
fun cardTitleOf(it: Item): String = when {
    it.isEpisode && it.seriesName != null && it.seasonNo != null && it.episodeNo != null ->
        "${it.seriesName} · S${it.seasonNo}E${it.episodeNo}"
    it.isEpisode && it.seriesName != null -> "${it.seriesName} · ${it.name}"
    else -> it.name
}

/**
 * 整行一个焦点的列表行(族 B 行型:聚焦反白,不放大)。
 * ★ 行内**不放按钮**:一行三个小按钮让左右键变成噩梦(§7.10)。行内文字吃 Surface 的 contentColor,
 *   聚焦反白时状态色一律让位给 onFocus —— 琥珀字压在白底上对比度只有约 2:1。
 */
@Composable
fun TvListRow(
    modifier: Modifier = Modifier, height: Dp = 64.dp, dim: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    androidx.tv.material3.Surface(
        onClick = onClick, onLongClick = onLongClick, interactionSource = src,
        modifier = modifier.fillMaxWidth().heightIn(min = height).menuKey(onLongClick)
            .then(if (dim) Modifier.graphicsLayer { alpha = .72f } else Modifier).tvRaised(focused, corner = 10.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvR.md),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.99f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvC.surface1, contentColor = TvC.fg,
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = TvSp.x12, vertical = TvSp.x8),
            verticalAlignment = Alignment.CenterVertically) { content(focused) }
    }
}

/** 行内文字:颜色跟 Surface 走(聚焦反色),只给透明度。 */
@Composable
fun RowText(text: String, size: androidx.compose.ui.unit.TextUnit, alpha: Float = 1f, maxLines: Int = 1, mono: Boolean = false) {
    androidx.tv.material3.Text(
        text, fontSize = size, maxLines = maxLines, modifier = Modifier.graphicsLayer { this.alpha = alpha },
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- 版本规格文案(§7.6)

/** 分辨率档:「2160p HDR10」。SDR 不写。 */
/** 编码名按惯用写法:`truehd` 全大写成「TRUEHD」,没人这么叫。 */
internal fun codecName(c: String?): String? {
    val k = c?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (k) {
        "truehd" -> "TrueHD"; "eac3" -> "E-AC3"; "dca" -> "DTS"; "opus" -> "Opus"; "h265" -> "HEVC"; "avc" -> "H264"
        "subrip" -> "SRT"; "pgssub", "hdmv_pgs_subtitle" -> "PGS"
        else -> k.uppercase()
    }
}

internal fun resLabel(v: Version): String {
    val s = v.of("Video").firstOrNull() ?: return "—"
    val h = s.height ?: return "—"
    val w = s.width ?: 0
    val p = when {
        h >= 2000 || w >= 3800 -> "2160p"
        h >= 1000 || w >= 1900 -> "1080p"
        h >= 700 || w >= 1270 -> "720p"
        else -> "${h}p"
    }
    val range = s.range?.takeIf { it.isNotBlank() && !it.equals("SDR", true) }
    return if (range != null) "$p $range" else p
}

/** 「HEVC · 24.6 Mbps · 8.4 GB」。**码率必须给**【用户定 2026-07-20】:4 Mbps 的 2160p 不如 15 Mbps 的 1080p。 */
internal fun specLabel(v: Version): String = listOfNotNull(
    codecName(v.of("Video").firstOrNull()?.codec),
    fmtRate(v.bitrate ?: v.of("Video").firstOrNull()?.bitrate),
    fmtSize(v.sizeBytes),
).joinToString(" · ")
