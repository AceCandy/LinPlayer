package xyz.linplayer.app.tv.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

// ---------------------------------------------------------------- 导航轨(§3.1)

val railItems = listOf(
    "搜索" to LpIcons.search, "首页" to LpIcons.home, "媒体库" to LpIcons.grid,
    "收藏" to LpIcons.heart, "发现" to LpIcons.trophy, "下载" to LpIcons.download,
    "服务器" to LpIcons.server, "设置" to LpIcons.settings,
)

/** 顶部主导航。索引沿用路由契约,只改变排列;焦点移动不切页。 */
@Composable
fun TopNavigation(
    current: Int, privateSpace: Boolean = false, focused: Int = -1,
    serverName: String = "服务器", onServer: () -> Unit = {}, onPrivateSpace: () -> Unit = {},
    modifier: Modifier = Modifier, onContent: () -> Boolean = { false }, onSelect: (Int) -> Unit = {},
) {
    val order = if (privateSpace) listOf(6, 1, 3, 4, 0) else listOf(6, 1, 3, 4, 0, 7)
    val reqs = remember { List(railItems.size) { FocusRequester() } }
    Row(
        modifier.fillMaxWidth().height(TvDim.navigationH).background(TvC.bg)
            .padding(horizontal = TvDim.safeH)
            .focusProperties { if (focused < 0) onEnter = { reqs[current.takeIf { it in order } ?: 1].requestFocus() } }
            .onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) onContent() else false
            }.focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSp.x8),
    ) {
        order.forEachIndexed { position, i ->
            if (i == 0) Spacer(Modifier.weight(1f))
            val focusModifier = Modifier.focusRequester(reqs[i]).focusProperties {
                left = if (position > 0) reqs[order[position - 1]] else FocusRequester.Cancel
                right = if (position < order.lastIndex) reqs[order[position + 1]] else FocusRequester.Cancel
                up = FocusRequester.Cancel
            }
            if (i == 6) {
                Surface(
                    onClick = onServer, onLongClick = onPrivateSpace,
                    modifier = focusModifier.width(180.dp).height(40.dp).testTag("nav.server")
                        .menuKey(onPrivateSpace).initialFocus(focused == 6),
                    shape = ClickableSurfaceDefaults.shape(TvR.pill),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = TvC.fg2,
                        focusedContainerColor = TvC.fg.copy(alpha = .12f).compositeOver(TvC.bg), focusedContentColor = TvC.fg),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, TvC.fg.copy(alpha = .55f)), shape = TvR.pill)),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = TvSp.x12), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                        Text(serverName, modifier = Modifier.weight(1f), fontSize = tvType.body, fontWeight = TvW.medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(LpIcons.chevD, null, Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(TvSp.x16))
            } else {
                val (label, icon) = railItems[i]
                NavigationItem(label, icon, on = i == current || (i == 1 && current == 2), iconOnly = i !in 1..4,
                    focused = focused == i,
                    badge = if (privateSpace) null else xyz.linplayer.app.plugin.PluginNav.badges[railTargets[i]],
                    modifier = focusModifier.testTag("rail.$i"), onClick = { onSelect(i) })
            }
        }
    }
}

/** 第 i 项对应的官方路由名,插件角标继续按它认入口。 */
private val railTargets = listOf(
    "search", "home", "library", "favorites", "aggregate", "downloads", "servers", "settings")

/** 当前栏目短横与焦点底色分别表达位置和操作目标。 */
@Composable
fun NavigationItem(
    label: String, icon: ImageVector, on: Boolean, iconOnly: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, badge: String? = null,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    var hasFocus by remember { mutableStateOf(false) }
    val focusFill = TvC.fg.copy(alpha = .12f).compositeOver(TvC.bg)
    val focusBorder = Border(BorderStroke(1.dp, TvC.fg.copy(alpha = .55f)), shape = TvR.pill)
    Box {
        Surface(
            onClick = onClick,
            modifier = modifier.size(if (iconOnly) 40.dp else 76.dp, 40.dp).initialFocus(focused)
                .onFocusChanged { hasFocus = it.isFocused }
                .semantics { selected = on; if (iconOnly) contentDescription = label },
            shape = ClickableSurfaceDefaults.shape(TvR.pill),
            border = ClickableSurfaceDefaults.border(border = if (fake) focusBorder else Border.None, focusedBorder = focusBorder),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = .97f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (fake) focusFill else Color.Transparent,
                contentColor = if (on || fake) TvC.fg else TvC.fg2,
                focusedContainerColor = focusFill, focusedContentColor = TvC.fg,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (iconOnly) Icon(icon, null, Modifier.size(20.dp))
                else Text(label, fontSize = tvType.body, fontWeight = if (on) TvW.semi else TvW.medium)
                if (on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                    .size(14.dp, 2.dp).clip(TvR.pill).background(TvC.acc))
                if (badge != null) Box(Modifier.align(Alignment.TopEnd)) { Badge(badge, TvC.bad, Color.White) }
            }
        }
        if (iconOnly && (hasFocus || fake)) TvText(label, tvType.meta, TvC.fg2,
            Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = 16.dp.toPx() }, maxLines = 1)
    }
}

/** 草稿与生产共用顶部导航,页面不因焦点位置变化重排。 */
@Composable
fun RailShell(current: Int, railFocus: Int = -1, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        Box(Modifier.fillMaxSize().padding(top = TvDim.navigationH), content = content)
        TopNavigation(current, focused = railFocus)
    }
}

/** 带轨页面的标准内容区:左 32、右 48、上下 27。 */
fun Modifier.contentArea() = this.fillMaxSize().padding(start = TvSp.x32, end = TvDim.safeH, top = TvDim.safeV, bottom = TvDim.safeV)

@Composable
fun PageHead(title: String, count: String? = null, sub: String? = null) {
    val t = tvType
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            TvText(title, t.headline, TvC.fg, weight = TvW.semi)
            if (count != null) {
                Spacer(Modifier.width(TvSp.x12))
                TvText(count, t.meta, TvC.fg3, Modifier.padding(bottom = 3.dp))
            }
        }
        if (sub != null) TvText(sub, t.body, TvC.fg2)
    }
}

// ---------------------------------------------------------------- 侧面板(§5.3)

@Composable
fun SidePanel(title: String, back: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier.width(TvDim.panelW).heightIn(max = TvDim.panelMaxH)
            .shadow(12.dp, TvR.lg).clip(TvR.lg).background(TvC.surface2).padding(vertical = TvSp.x12),
    ) {
        Row(Modifier.padding(horizontal = TvSp.x16).padding(bottom = TvSp.x6), verticalAlignment = Alignment.CenterVertically) {
            if (back) {
                Icon(LpIcons.back, null, Modifier.size(16.dp), tint = TvC.fg3)
                Spacer(Modifier.width(TvSp.x4))
            }
            TvText(title, tvType.title, TvC.fg, weight = TvW.semi)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), content = content)
        // 内容超出时底部一个 ▾:不画的话最后一项被切掉一半,看着像面板画坏了
        if (scroll.canScrollForward) Box(Modifier.fillMaxWidth().padding(top = TvSp.x2), contentAlignment = Alignment.Center) {
            Icon(LpIcons.chevD, null, Modifier.size(16.dp), tint = TvC.fg3)
        }
    }
}

/** 分组标题,不可聚焦。 */
@Composable
fun PanelGroup(title: String) {
    TvText(title, tvType.meta, TvC.fg3, Modifier.padding(start = TvSp.x16, top = TvSp.x8, bottom = TvSp.x2))
}

/**
 * 面板项 / 设置行。
 * ★ 步进项([onStep] 非空):聚焦后 ←/→ 调值,确认无动作;调值时焦点不移出面板。
 */
@Composable
fun PanelItem(
    label: String, value: String? = null, selected: Boolean = false, focused: Boolean = false, fake: Boolean = false,
    danger: Boolean = false, chevron: Boolean = false, step: Boolean = false, enabled: Boolean = true,
    sub: String? = null, switch: Boolean? = null, check: Boolean = true,
    modifier: Modifier = Modifier, onStep: ((Int) -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val t = tvType
    if (!enabled) {
        Row(Modifier.fillMaxWidth().heightIn(min = t.rowH).padding(horizontal = TvSp.x16, vertical = TvSp.x6),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TvText(label, t.body, TvC.fg3, maxLines = 2)
                if (sub != null) TvText(sub, t.meta, TvC.fg3, maxLines = 3)
            }
            if (value != null) TvText(value, t.meta, TvC.fg3)
        }
        return
    }
    val stepKeys = if (onStep == null) Modifier else Modifier.onPreviewKeyEvent {
        val d = when (it.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
        if (d == 0) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyDown) onStep(d)
        true
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.padding(horizontal = TvSp.x6).fillMaxWidth().heightIn(min = t.rowH).initialFocus(focused)
            .then(stepKeys).menuKey(onLongClick),
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (fake) TvC.focus else Color.Transparent,
            contentColor = when {
                fake -> TvC.onFocus
                danger -> TvC.bad
                selected -> TvC.fg
                else -> TvC.fg2
            },
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = t.rowH)) {
            if (selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(t.rowH * 0.45f).clip(TvR.pill).background(TvC.acc))
            Row(Modifier.fillMaxWidth().heightIn(min = t.rowH).padding(horizontal = TvSp.x12, vertical = TvSp.x6),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = t.body, fontWeight = if (selected) TvW.semi else TvW.medium, maxLines = 3)
                    if (sub != null) Text(sub, fontSize = t.meta, maxLines = 3, modifier = Modifier.graphicsLayer { alpha = .75f })
                }
                if (step) Text("−", fontSize = t.title, modifier = Modifier.padding(horizontal = TvSp.x6))
                if (value != null) Text(value, fontSize = t.meta, maxLines = 1)
                if (step) Text("+", fontSize = t.title, modifier = Modifier.padding(start = TvSp.x6))
                if (switch != null) Toggle(switch)
                // 导航性质的列表(设置分类、发现分类)只要竖条不要勾:勾读作「已勾选」,而那里是「在看这一类」
                if (selected && check) Icon(LpIcons.check, null, Modifier.padding(start = TvSp.x6).size(16.dp))
                if (chevron) Icon(LpIcons.chevR, null, Modifier.padding(start = TvSp.x4).size(14.dp))
            }
        }
    }
}

@Composable
fun Toggle(on: Boolean) {
    Box(Modifier.padding(start = TvSp.x8).size(32.dp, 18.dp).clip(TvR.pill).background(if (on) TvC.acc else TvC.surface3)) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp).size(14.dp).clip(TvR.pill)
            .background(if (on) TvC.onAcc else TvC.fg3))
    }
}

/** 面板靠右、垂直居中、右边距 48,无遮罩。 */
@Composable
fun BoxScope.PanelSlot(content: @Composable () -> Unit) {
    Box(Modifier.align(Alignment.CenterEnd).padding(end = TvDim.safeH)) { content() }
}

// ---------------------------------------------------------------- 反馈

/** 静态色块,不做闪光:弱机上每帧重画一整屏。 */
@Composable
fun Skel(modifier: Modifier, shape: Shape = TvR.md, color: Color = TvC.surface1) {
    Box(modifier.clip(shape).background(color))
}

@Composable
fun BoxScope.TvToast(text: String, dot: Color = TvC.ok) {
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
            .shadow(8.dp, TvR.md).clip(TvR.md).background(TvC.surface2)
            .padding(horizontal = TvSp.x20, vertical = TvSp.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(TvR.pill).background(dot))
        Spacer(Modifier.width(TvSp.x8))
        TvText(text, tvType.body, TvC.fg)
    }
}

@Composable
fun FullState(
    icon: ImageVector, title: String, sub: String? = null, detail: String? = null,
    buttons: List<String>, tone: Color = TvC.fg3, focusFirst: Boolean = true, modifier: Modifier = Modifier,
    onButton: (Int) -> Unit = {},
) {
    val t = tvType
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(TvR.lg).background(tone.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = tone)
        }
        Spacer(Modifier.height(TvSp.x16))
        TvText(title, t.title, TvC.fg, weight = TvW.semi)
        if (sub != null) { Spacer(Modifier.height(TvSp.x6)); TvText(sub, t.body, TvC.fg2, maxLines = 3) }
        if (detail != null) { Spacer(Modifier.height(TvSp.x4)); TvText(detail, t.meta, TvC.fg3, maxLines = 3) }
        Spacer(Modifier.height(TvSp.x20))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            buttons.forEachIndexed { i, b -> TvButton(b, focused = focusFirst && i == 0, onClick = { onButton(i) }) }
        }
    }
}

/** 行内错误(§5.5):一句 `meta fg3`,不打断其它块。 */
@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
    TvText("没加载出来:$message", tvType.meta, TvC.fg3, modifier, maxLines = 2)
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(TvR.pill).background(color))
}

/** 行标题。link = 可聚焦(媒体库行),聚焦时反色 + ›。 */
@Composable
fun RowTitle(
    text: String, link: Boolean = false, focused: Boolean = false, fake: Boolean = false, trailing: String? = null,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    val t = tvType
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (link) {
            Surface(
                onClick = onClick,
                modifier = modifier.initialFocus(focused),
                shape = ClickableSurfaceDefaults.shape(TvR.sm),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (fake) TvC.focus else Color.Transparent,
                    contentColor = if (fake) TvC.onFocus else TvC.fg,
                    focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
                ),
            ) {
                Row(Modifier.padding(horizontal = TvSp.x8, vertical = TvSp.x2), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, fontSize = t.title, fontWeight = TvW.semi)
                    Icon(LpIcons.chevR, null, Modifier.padding(start = TvSp.x4).size(14.dp))
                }
            }
        } else {
            TvText(text, t.title, TvC.fg, Modifier.padding(horizontal = TvSp.x8, vertical = TvSp.x2), weight = TvW.semi)
        }
        if (trailing != null) TvText(trailing, t.meta, TvC.fg3)
    }
}

/** 输入框外观(族 E)。草稿与真输入框 [TvTextField] 共用这一份画法。 */
@Composable
fun InputBox(text: String, placeholder: String = "", icon: ImageVector? = null, focused: Boolean = false, width: Dp, caret: Boolean = focused) {
    val t = tvType
    Row(
        Modifier.width(width).height(t.controlH).clip(TvR.sm)
            .background(if (focused) TvC.surface2 else TvC.surface3)
            .then(if (focused) Modifier.border(2.dp, TvC.focus, TvR.sm) else Modifier)
            .padding(horizontal = TvSp.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(t.iconS), tint = TvC.fg3); Spacer(Modifier.width(TvSp.x8)) }
        if (text.isEmpty()) TvText(placeholder, t.body, TvC.fg3) else TvText(text, t.body, TvC.fg)
        if (caret) Box(Modifier.padding(start = 1.dp).width(2.dp).height(t.body.value.dp + 4.dp).background(TvC.acc))
    }
}
