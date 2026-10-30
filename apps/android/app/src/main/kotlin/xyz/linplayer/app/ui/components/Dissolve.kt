package xyz.linplayer.app.ui.components

import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.tv.tvRaised

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LocalPrivateSpace
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/*
 * 「去边界」工具箱(草稿 Draft 04)。
 *
 * ☠ **边界感是要极力避免的**【用户定 2026-09-06】。这不是审美偏好,是版式契约:
 *   凡是「一块图 + 一条硬边 + 下面另一种颜色」的地方,都要换成图自己化进背景色。
 *
 * ★ 做法上有两条路,**只有一条是对的**:
 *   ① 在图上盖一层「透明 → 背景色」的渐变 —— 错。背景色是**从封面取的色**,
 *     而遮罩色是现调的,两者永远差一点;结果边界不但没消失,还从一条硬边
 *     变成一块隐约的深色矩形,更难看。
 *   ② 直接把图自己的 **alpha** 推到 0([dissolve]) —— 对。图没了之后露出来的
 *     就是页面的底,不存在「差一点」这回事。
 */

// ---------------------------------------------------------------- 溶解

/**
 * 把这一层的下沿溶掉:[from] 处开始变淡,[to] 处彻底透明(比例,0~1)。
 *
 * ☠ 必须配 `CompositingStrategy.Offscreen`。`DstIn` 是拿画笔的 alpha 去乘目标层,
 *   不离屏合成的话它乘的是**整个已经画好的窗口** —— 表现是屏幕被挖了个洞。
 */
fun Modifier.dissolve(from: Float = 0.55f, to: Float = 1f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                from to Color.Black, to to Color.Transparent,
                startY = 0f, endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** 把这一层的上沿溶掉：顶部 [edge] 内由透明过渡到实，滚上去的内容化进上方标题而不是被一刀切掉。 */
fun Modifier.dissolveTop(edge: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = edge.toPx()),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * 页面底色:从封面取的色在顶上最浓,到 [depth] 处收干净回底色。
 *
 * ★ 每部片进来颜色都不一样 —— 这一条比任何动效都更让页面「活」。
 */
fun toneScene(tone: Color, bg: Color, depth: Float = 0.78f): Brush = Brush.verticalGradient(
    0.00f to tone.copy(alpha = .40f),
    0.30f * depth to tone.copy(alpha = .18f),
    0.62f * depth to tone.copy(alpha = .05f),
    depth to bg,
    1.00f to bg,
)

/** 电视首页头图取色铺满整页；收藏等一级页沿用同一层，切页不跳回纯色底。 */
fun homeToneScene(tone: Color, bg: Color): Brush =
    toneScene(tone, tone.copy(alpha = .08f).compositeOver(bg), depth = 1f)

/**
 * 一团散在角上的辉光(草稿 06 / 07 的顶部色斑)。
 *
 * ★ **不用 `Modifier.blur`**:它要 API 31,低版本上静默不生效 —— 那正是
 *   「浅色修好了深色没修」那一类只在部分机型现形的坑。径向渐变本身就是软的。
 */
fun Modifier.glow(color: Color, alpha: Float = .34f): Modifier = this.background(
    Brush.radialGradient(
        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .3f), Color.Transparent)
    )
)

/**
 * 让这一项**无视父容器的左右内边距**,铺满整宽。
 *
 * ★ 网格里那块「铺到屏幕边」的库头就靠它:格子要 16dp 的内边距,而库头一格都不能留 ——
 *   否则图的两侧各露一条底色,那就是又一条边界。
 */
fun Modifier.bleed(horizontal: androidx.compose.ui.unit.Dp): Modifier = this.layout { m, cs ->
    val pad = horizontal.roundToPx()
    val p = m.measure(cs.copy(minWidth = 0, maxWidth = cs.maxWidth + pad * 2))
    layout(cs.maxWidth, p.height) { p.place(-pad, 0) }
}

// ---------------------------------------------------------------- 实色表面

/** 共用面板表面；solid > 1 使用更强层级的底色。 */
@Composable
fun Modifier.glass(
    corner: androidx.compose.ui.unit.Dp = R.md,
    solid: Float = 1f,
): Modifier {
    val c = Lp.colors
    val surface = (if (solid > 1f) c.s2 else c.s1).compositeOver(c.bg)
    return this.clip(RoundedCornerShape(corner)).background(surface)
}

// ---------------------------------------------------------------- 分层替代描边

/** 内容卡片通过实色层级区分，不叠加玻璃高光和描边。 */
@Composable
fun Layer(
    m: Modifier = Modifier,
    strong: Boolean = false,
    corner: androidx.compose.ui.unit.Dp = R.md,
    content: @Composable () -> Unit,
) {
    val c = Lp.colors
    Box(
        m.clip(RoundedCornerShape(corner))
            .background((if (strong) c.s2 else c.s1).compositeOver(c.bg))
    ) { content() }
}

// ---------------------------------------------------------------- 版式零件

/** 章节标题：只靠字重和留白分层，不加装饰竖条；首页轨道标题同一手法。 */
@Composable
fun SectionTitle(
    text: String,
    m: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Lp.colors
    Row(
        m.fillMaxWidth().padding(start = Sp.x16, end = Sp.x8, top = Sp.x16, bottom = Sp.x10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = c.fg, style = LpText.section,
            maxLines = if (trailing != null) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(Sp.x8))
            trailing()
        }
    }
}

/** 眉标:小字 + 字距。三级标题的第一级(草稿 03/04 的 kicker)。 */
@Composable
fun Kicker(text: String, m: Modifier = Modifier, color: Color? = null) =
    Text(
        text, m, color = color ?: Lp.colors.fg2, style = LpText.badge, maxLines = 1,
    )

/** 无边框 chip。**未选中是一层填充,不是一个空心框** —— 一排空心框是方寸感最重的地方。 */
@Composable
fun ToneChip(
    label: String,
    on: Boolean,
    m: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Lp.colors
    // 选中与 MediaFilterChip 同一规则：淡强调底 + 强调字；隐私空间有霓虹描边，选中仍用实底
    val solid = LocalPrivateSpace.current
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    Box(
        m.tvRaised(focused, corner = R.pill).privateNeonBorder(R.pill).clip(RoundedCornerShape(R.pill))
            .onFocusChanged { hasFocus = it.isFocused }
            .semantics { selected = on }
            .background(if (focused) c.fg else if (!on) c.s2 else if (solid) c.acc else c.accDim)
            .pressable(onClick, onLongClick, tvHighlight = false)
            .padding(horizontal = Sp.x16, vertical = Sp.x8),
    ) {
        Text(label, color = if (focused) c.bg else if (!on) c.fg2 else if (solid) c.accFg else c.acc,
            style = LpText.filter, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
        if (on && xyz.linplayer.app.tv.LocalTvMode.current) Box(Modifier.align(Alignment.BottomCenter)
            .offset(y = Sp.x4).size(16.dp, 2.dp).background(c.acc))
    }
}

/**
 * 详情主按钮使用媒体蓝紫色，短按与长按沿用各自播放入口。
 */
@Composable
fun PrimaryAction(
    text: String,
    m: Modifier = Modifier,
    icon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    val ink = if (focused) c.bg else c.mediaOnAccent
    Row(
        m.fillMaxWidth().heightIn(min = Dim.tap).onFocusChanged { hasFocus = it.isFocused }
            .tvRaised(focused, corner = R.pill).privateNeonBorder(R.pill).clip(RoundedCornerShape(R.pill))
            .background(if (focused) c.fg else c.mediaAccent)
            .pressable(onClick, onLongClick, tvHighlight = false)
            .padding(horizontal = Sp.x20, vertical = Sp.x12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = ink)
            Spacer(Modifier.width(Sp.x8))
        }
        Text(
            text, Modifier.weight(1f, fill = false), color = ink, style = LpText.list, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 图标动作:一颗圆钮 + 一行字。
 * ★ **没有「分享」**【用户定 2026-09-06】—— 我们本来就分享不了,那颗按钮是纯装饰。
 */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    m: Modifier = Modifier,
    on: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    Column(
        m.heightIn(min = Dim.tap).onFocusChanged { hasFocus = it.isFocused }
            .tvRaised(focused, corner = R.md)
            .clip(RoundedCornerShape(R.md)).background(if (focused) c.fg else Color.Transparent)
            .pressable(onClick, tvHighlight = false)
            .padding(horizontal = Sp.x12, vertical = Sp.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(38.dp).privateNeonBorder(R.pill).clip(RoundedCornerShape(R.pill))
                .background(if (focused) Color.Transparent else if (on) c.mediaAccent.copy(alpha = .18f) else c.s1),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(19.dp), tint = if (focused) c.bg else if (on) c.mediaIcon else c.fg2) }
        Spacer(Modifier.height(Sp.x6))
        Text(label, color = if (focused) c.bg else if (on) c.mediaIcon else c.fg3, style = LpText.badge, maxLines = 1)
    }
}

/**
 * 浮在图上的毛玻璃圆钮。**顶栏不占一行** —— 它整个飘在 Hero 上。
 *
 * ★ 尺寸 44dp【用户定 2026-09-07:「顶部的三个按钮都太小了」】。
 *   36dp 已经低于安卓的 48dp 命中区建议,而它还浮在一张大图上 —— 视觉上更小。
 */
@Composable
fun GlassIcon(
    icon: ImageVector,
    desc: String,
    m: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    Box(
        m.size(48.dp).onFocusChanged { hasFocus = it.isFocused }
            .tvRaised(focused, corner = R.pill).buttonSkin(if (focused) c.fg else c.chip, R.pill)
            .pressable(onClick, tvHighlight = false),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(24.dp), tint = if (focused) c.bg else c.fg2) }
}

/**
 * 一行数据(评分 / 年份 / 集数 / 时长),中间发丝竖线。
 * **不是用「·」串起来的一行灰字** —— 那是上一稿最明显的塌陷点。
 */
@Composable
fun DataStrip(cells: List<Pair<String, String>>, m: Modifier = Modifier) {
    val c = Lp.colors
    if (cells.isEmpty()) return
    Row(m.padding(horizontal = Sp.x16), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(
                Modifier.padding(horizontal = Sp.x12).size(1.dp, 22.dp).background(c.line2)
            )
            Column {
                Text(
                    value, color = if (i == 0) c.acc else c.fg,
                    fontSize = if (i == 0) 21.sp else 17.sp, fontWeight = FontWeight.Bold,
                )
                Text(label, color = c.fg3, fontSize = 10.5.sp, letterSpacing = 0.6.sp)
            }
        }
    }
}
