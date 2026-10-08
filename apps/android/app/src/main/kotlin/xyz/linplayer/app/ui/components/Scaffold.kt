package xyz.linplayer.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import xyz.linplayer.app.ui.theme.lpSpring
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T

/**
 * 底栏占掉的高度。**内容从底栏下面穿过去**,所以每个列表都要按它留白。
 *
 * ★ 挂在 CompositionLocal 上而不是让每页自己判:漏一页的表现是
 *   「最后一张卡被底栏压住一半」—— 那种 bug 只有滚到底才看得见。
 */
val LocalTabClearance = compositionLocalOf { 0.dp }

/**
 * 每一页的外壳。
 *
 * ★ **topbar 随滚动实体化**:一上来不画线,滚了才出底 —— 一上来就画会把首屏切一刀。
 * ★ 安全区:内容画到底,只给列表的 contentPadding 加导航条 + 底栏高度 ——
 *   整体 `safeDrawing` 会让内容在导航条上方戛然而止。
 */
@Composable
fun LpScaffold(
    title: String? = null,
    m: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrolled: Boolean = false,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = Lp.colors
    Box(m.fillMaxSize().background(xyz.linplayer.app.ui.plugin.pageBg(c.bg))) {
        Column(Modifier.fillMaxSize()) {
            // ★ 没有标题**不等于没有 topbar**:首页就是「无标题但右上角有入口」。
            LpTopBar(title, subtitle, onBack, scrolled, actions)
            Box(Modifier.weight(1f)) { content(contentInsets()) }
            bottomBar()
        }
    }
}

/**
 * 沉浸外壳(草稿 01/02/03/04):**内容从 y=0 开始**,顶栏浮在图上。
 *
 * ☠ 这是「把状态栏那块黑顶飞」的做法【用户定 2026-09-06】。
 *   用 [LpScaffold] 的话状态栏是一条 `Spacer` 占位 —— 图只能从它下面开始,
 *   于是屏幕顶上永远留着一条纯色带,那就是用户说的「方寸感、边界感」。
 *   这里改成 `Box` 叠层:图铺满,顶栏连同状态栏高度一起浮在上面。
 */
@Composable
fun LpImmersive(
    m: Modifier = Modifier,
    bar: @Composable RowScope.() -> Unit = {},
    barHeight: Dp = Dim.topBar,
    barHorizontalPadding: Dp = Sp.x12,
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = Lp.colors
    Box(m.fillMaxSize().background(xyz.linplayer.app.ui.plugin.pageBg(c.bg))) {
        content(contentInsets())
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                Modifier.fillMaxWidth().height(barHeight).padding(horizontal = barHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Sp.x8),
            ) { bar() }
        }
    }
}

/** 内容区该留的白:系统导航条 + 底栏。 */
@Composable
private fun contentInsets(): PaddingValues = PaddingValues(
    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        LocalTabClearance.current + Sp.x12
)

@Composable
fun LpTopBar(
    title: String?,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrolled: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val c = Lp.colors
    val bg by animateColorAsState(
        if (scrolled) c.bg else Color.Transparent,
        androidx.compose.animation.core.tween(T.T4, easing = LpEasing.standard),
        label = "tbBg",
    )
    Column(Modifier.fillMaxWidth().background(bg)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            Modifier.fillMaxWidth().height(Dim.topBar).padding(horizontal = Sp.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                LpIconButton(LpIcons.back, "返回", onClick = onBack)
            } else {
                Spacer(Modifier.width(Sp.x12))
            }
            Column(Modifier.weight(1f)) {
                if (title != null) Text(
                    title, color = c.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) Text(
                    subtitle, color = c.fg3, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            actions()
        }
        /* ★ 实体化时**不画发丝线**,改成从底色渐隐下来一小段。
           一条线是把页面切两半;一段渐隐是让内容化进栏里 —— 同一件事,后者没有边界。 */
        if (scrolled) Box(
            Modifier.fillMaxWidth().height(Sp.x12)
                .background(Brush.verticalGradient(listOf(c.bg, Color.Transparent)))
        )
    }
}

/** 滚了没有。**只看「有没有滚出第一项的顶」**,不看具体偏移 —— 每帧读偏移会每帧重组。 */
@Composable
fun rememberScrolled(state: LazyListState): Boolean {
    val v by remember(state) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 4 }
    }
    return v
}

@Composable
fun rememberScrolled(state: androidx.compose.foundation.lazy.grid.LazyGridState): Boolean {
    val v by remember(state) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 4 }
    }
    return v
}

/** 悬浮图标底栏。三个 Tab 保留独立返回栈，搜索为独立动作；外部空白不拦截页面触摸。 */
@Composable
fun LpTabBar(current: Int, onSearch: () -> Unit, onPick: (Int) -> Unit) {
    val c = Lp.colors
    Column(
        Modifier.fillMaxWidth().padding(top = Sp.x6, bottom = Dim.tabFloatGap +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.width(Dim.tabWidth).height(Dim.tabBar)
                .shadow(6.dp, RoundedCornerShape(R.pill))
                .clip(RoundedCornerShape(R.pill)).background(c.mediaPanel)
                .padding(horizontal = Sp.x6, vertical = 5.dp)
                .selectableGroup().testTag("phone.tabs"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab("首页", LpIcons.home, current == 0,
                Modifier.weight(1f), badge("home")) { onPick(0) }
            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(R.pill))
                .pressable(onSearch), contentAlignment = Alignment.Center) {
                Icon(LpIcons.search, "搜索", Modifier.size(26.dp), tint = c.mediaIcon)
            }
            Tab("聚合视界", LpIcons.layers, current == 1, Modifier.weight(1f), badge("aggregate")) { onPick(1) }
            // 服务器管理仍在聚合页；第三个入口是日常使用的收藏。
            Tab("收藏", LpIcons.star, current == 2,
                Modifier.weight(1f), badge("favorites")) { onPick(2) }
        }
    }
}

/** 图标保留可读名称及选中语义，圆底与轻微缩放提示当前页。 */
@Composable
private fun Tab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    m: Modifier,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val c = Lp.colors
    val src = remember { MutableInteractionSource() }
    val z by animateFloatAsState(if (on) 1f else 0f, lpSpring(visibilityThreshold = .001f), label = "tabOn")
    Box(
        m.fillMaxSize().clip(RoundedCornerShape(R.pill))
            .pressFeedback(src)
            .selectable(selected = on, role = Role.Tab, interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer { scaleX = z; scaleY = z; alpha = z }
                .size(Dim.tap).clip(RoundedCornerShape(R.pill)).background(c.mediaAccent)
        )
        Icon(
            icon, label,
            Modifier.size(28.dp).graphicsLayer { val s = 1f + z * .08f; scaleX = s; scaleY = s },
            tint = if (on) c.mediaOnAccent else c.mediaIcon,
        )
        if (badge != null) Text(
            badge, color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopEnd)
                .clip(RoundedCornerShape(R.pill)).background(c.bad)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** 插件挂在这个入口上的角标(D158);没有就是 null。 */
private fun badge(target: String) = xyz.linplayer.app.plugin.PluginNav.badges[target]

/** 让页面能拿到底栏高度做自己的留白(网格 / 自绘列表)。 */
@Composable
fun tabClearance(): Dp = LocalTabClearance.current
