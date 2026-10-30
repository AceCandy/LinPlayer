package xyz.linplayer.app.tv

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.TopNavigation
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.menuKey
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LocalPosterScene
import xyz.linplayer.app.ui.switchTab
import xyz.linplayer.app.ui.theme.Lp
import java.util.UUID

/** 只表达宿主交互形态，页面请求和业务状态仍共用。 */
val LocalTvMode = staticCompositionLocalOf { false }

/** 首页有头图时顶栏使用叠图文字和渐隐遮罩，滚动后也不切成实色硬边。 */
internal val LocalTvBarOverlay = staticCompositionLocalOf<MutableState<Boolean>?> { null }

internal val LocalTvTopNavigation = staticCompositionLocalOf<FocusRequester?> { null }
/** 一级页右栏按返回时先交还给页面侧栏；返回 false 才继续回顶栏。 */
internal val LocalTvPageBack = staticCompositionLocalOf<MutableState<(() -> Boolean)?>> { mutableStateOf(null) }

/** 当前首页登记刷新动作，离页撤销；顶栏不复制页面的请求与私密分支。 */
internal val LocalTvHomeRefresh = staticCompositionLocalOf<MutableState<(() -> Unit)?>?> { null }
internal val LocalTvHomeScroll = staticCompositionLocalOf<MutableState<Int>?> { null }

/** 首页头图当前取色；一级页以它铺底，与首页保持同一氛围。 */
internal val LocalTvTone = staticCompositionLocalOf<MutableState<androidx.compose.ui.graphics.Color?>?> { null }

/** 电视浏览页左右边距；首页头图向两侧铺满时按它反推。 */
internal val TvPageSide = 16.dp

/** 横轨左右到尽头留在本行；双栏搜索可指定左侧操作区出口，上下仍按正常焦点搜索跨行。 */
@Composable
internal fun Modifier.tvFocusRow(exitLeft: FocusRequester? = null): Modifier = if (!LocalTvMode.current) this else focusProperties {
    onExit = {
        if (requestedFocusDirection == FocusDirection.Left && exitLeft != null) {
            if (!exitLeft.requestFocus()) cancelFocusChange()
        } else if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right)
            cancelFocusChange()
    }
}.focusGroup()

/** 绘制放大不改变焦点矩形；整张封面和按钮一起突出，而非裁剪框内缩放图片。 */
@Composable
internal fun Modifier.tvFocusScale(source: MutableInteractionSource, enabled: Boolean, tv: Boolean = LocalTvMode.current): Modifier {
    if (!tv) return this
    val focused by source.collectIsFocusedAsState()
    val amount by androidx.compose.animation.core.animateFloatAsState(
        if (focused && enabled) 1.04f else 1f,
        xyz.linplayer.app.ui.theme.lpTween(xyz.linplayer.app.ui.theme.T.T3), label = "tvFocusScale")
    return drawWithContent {
        val factor = minOf(amount, 1f + 8.dp.toPx() / size.maxDimension.coerceAtLeast(1f))
        scale(factor) { this@drawWithContent.drawContent() }
    }
}

/** 电视一级页：只有这些页显示顶栏，二级及更深的页面全屏沉浸。 */
internal fun androidx.navigation.NavDestination.isTvRootPage() =
    hasRoute<Route.Home>() || hasRoute<Route.Favorites>() || hasRoute<Route.Aggregate>() ||
        hasRoute<Route.Search>() || hasRoute<Route.Servers>() || hasRoute<Route.Settings>()

/** 电视浏览页留左右边距，一级页再避开 72dp 顶栏。挂在每一页上而不是外层容器，切页动画里两页各带各的边距。 */
@Composable
internal fun TvPageInset(content: @Composable () -> Unit) {
    if (!LocalTvMode.current) { content(); return }
    val root = LocalPosterScene.current?.entry?.destination?.isTvRootPage() != false
    Box(Modifier.fillMaxSize().padding(top = if (root) TvDim.navigationH else TvPageSide, start = TvPageSide, end = TvPageSide)) { content() }
}

/** 共用点击控件在电视上显示焦点，并恢复返回前的卡片。 */
@Composable
internal fun Modifier.tvFocus(
    source: MutableInteractionSource, onLongClick: (() -> Unit)? = null, enabled: Boolean = true,
    highlight: Boolean = true,
): Modifier {
    if (!LocalTvMode.current) return this
    val focused by source.collectIsFocusedAsState()
    val color = Lp.colors.fg
    val entry = LocalPosterScene.current?.entry
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val requester = remember { FocusRequester() }
    LaunchedEffect(entry, id, enabled) {
        // 返回时数据可能仍在刷新；只在可操作后恢复仍被记住的控件。
        if (enabled && entry?.savedStateHandle?.get<String>("tv.focus") == id) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    return focusRequester(requester).onFocusChanged {
        if (it.isFocused) {
            // 转场会临时把焦点交给内容，不能覆盖离页前停在导航的意图。
            if (entry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED)
                entry.savedStateHandle["tv.navigation"] = false
            entry?.savedStateHandle?.set("tv.focus", id)
        }
    }.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown) entry?.savedStateHandle?.set("tv.navigation", false)
        false
    }.then(if (onLongClick != null) Modifier.menuKey(onLongClick) else Modifier)
        .tvRaised(highlight && focused, glass = color)
}

/**
 * 立体焦点：光从上方来 —— 轮廓外投影、上亮下暗、顶边高光，渐入渐出。
 * 传 [glass] 时自带半透明胶囊底（文字入口、顶栏）；不传则只给控件自绘的反色实底叠光影。
 * [corner] 缺省取短边一半且不超过 24dp。只改绘制，不动焦点几何；须挂在裁剪之前，否则投影被裁掉。
 */
@Composable
internal fun Modifier.tvRaised(focused: Boolean, glass: Color? = null, corner: Dp? = null): Modifier {
    val amount by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f,
        xyz.linplayer.app.ui.theme.lpTween(xyz.linplayer.app.ui.theme.T.T3), label = "tvRaised")
    return drawWithCache {
        val radius = CornerRadius(minOf(size.minDimension / 2, (corner ?: 24.dp).toPx()))
        val outline = Path().apply { addRoundRect(RoundRect(size.toRect(), radius)) }
        val shadow = android.graphics.Paint()
        val edge = 1.5.dp.toPx()
        val inset = Offset(edge / 2, edge / 2)
        val inner = Size(size.width - edge, size.height - edge)
        val innerRadius = CornerRadius((radius.x - edge / 2).coerceAtLeast(0f))
        val shade = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.White.copy(alpha = .16f), .5f to Color.Transparent, 1f to Color.Black.copy(alpha = .16f))
        onDrawWithContent {
            val a = amount
            if (a == 0f) { drawContent(); return@onDrawWithContent }
            shadow.setShadowLayer(14.dp.toPx(), 0f, 7.dp.toPx(), android.graphics.Color.argb((120 * a).toInt(), 0, 0, 0))
            // 投影只画在轮廓外，透明底不会被垫黑
            clipPath(outline, ClipOp.Difference) {
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius.x, radius.y, shadow) }
            }
            if (glass != null) {
                drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to glass.copy(alpha = .34f * a), .55f to glass.copy(alpha = .15f * a), 1f to glass.copy(alpha = .1f * a)),
                    cornerRadius = radius)
                drawRoundRect(glass.copy(alpha = .22f * a), inset, inner, innerRadius, style = Stroke(edge))
            }
            drawContent()
            if (glass == null) clipPath(outline) { drawRect(shade, alpha = a) }
            // 顶边高光只留上沿与上方圆角
            clipRect(bottom = radius.x * .9f) {
                drawRoundRect((glass ?: Color.White).copy(alpha = .5f * a), inset, inner, innerRadius, style = Stroke(edge))
            }
        }
    }
}

/**
 * 海报类焦点：立体浮起 —— 上抬并最多放大 10%，厚落地投影加一圈暖色辉光，白边外再套强调色细环，
 * 表面斜扫一道高光；支点偏下让图多向上长、少压片名。只改绘制，不动焦点几何。
 */
@Composable
internal fun Modifier.tvPosterFocus(focused: Boolean, corner: Dp, tv: Boolean = LocalTvMode.current): Modifier {
    if (!tv) return this
    val amount by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f,
        xyz.linplayer.app.ui.theme.lpTween(xyz.linplayer.app.ui.theme.T.T3), label = "tvPosterFocus")
    val accent = xyz.linplayer.app.tv.kit.TvC.acc
    return drawWithCache {
        val radius = CornerRadius(corner.toPx())
        val outline = Path().apply { addRoundRect(RoundRect(size.toRect(), radius)) }
        val shadow = android.graphics.Paint()
        val glow = android.graphics.Paint()
        val pivot = Offset(size.width / 2, size.height * .7f)
        // 横向每侧最多外扩 4dp，连同 3dp 外环仍落在 10dp 卡间距内，不压邻卡；宽卡靠上抬与投影显立体
        val grow = minOf(.1f, 8.dp.toPx() / size.width.coerceAtLeast(1f))
        val lift = 8.dp.toPx()
        val stroke = 2.dp.toPx()
        val border = Stroke(stroke)
        val borderOffset = Offset(stroke / 2, stroke / 2)
        val borderSize = Size(size.width - stroke, size.height - stroke)
        val borderRadius = CornerRadius(radius.x - stroke / 2)
        val ring = 3.dp.toPx()
        val ringOffset = Offset(-ring / 2, -ring / 2)
        val ringSize = Size(size.width + ring, size.height + ring)
        val ringRadius = CornerRadius(radius.x + ring / 2)
        val sheen = androidx.compose.ui.graphics.Brush.linearGradient(
            .3f to Color.Transparent, .44f to Color.White.copy(alpha = .28f), .58f to Color.Transparent,
            start = Offset.Zero, end = Offset(size.width, size.height * .75f))
        onDrawWithContent {
            // 动画状态只在绘制阶段读取，避免每帧重建固定轮廓和画笔。
            val progress = amount
            if (progress == 0f) { drawContent(); return@onDrawWithContent }
            shadow.setShadowLayer(26.dp.toPx(), 0f, 14.dp.toPx(), android.graphics.Color.argb((190 * progress).toInt(), 0, 0, 0))
            glow.setShadowLayer(22.dp.toPx(), 0f, 0f, accent.copy(alpha = .45f * progress).toArgb())
            translate(top = -lift * progress) {
                scale(1f + grow * progress, pivot) {
                    // 投影和辉光只画在轮廓外，透明底的卡片不会被垫黑
                    clipPath(outline, ClipOp.Difference) {
                        drawIntoCanvas {
                            it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius.x, radius.y, shadow)
                            it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius.x, radius.y, glow)
                        }
                    }
                    this@onDrawWithContent.drawContent()
                    clipPath(outline) { drawRect(sheen, alpha = progress) }
                    drawRoundRect(accent.copy(alpha = .3f * progress), ringOffset, ringSize, ringRadius, style = Stroke(ring))
                    drawRoundRect(Color.White.copy(alpha = .95f * progress), borderOffset,
                        borderSize, borderRadius, style = border)
                }
            }
        }
    }
}

/** 顶栏只调用手机导航，不另维护TV页面栈。 */
@Composable
internal fun SharedTopNavigation(nav: NavHostController, activeTab: Int, content: FocusRequester, overlay: Boolean = false,
    modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val privateSpace by app.privateSpace.collectAsState()
    var name by remember(session) { mutableStateOf("服务器") }
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(session) {
        name = Account.list(runCatching { app.call("account.listAccounts") }.getOrNull())
            .firstOrNull { it.isActive }?.name ?: "服务器"
    }
    val current = when {
        entry?.destination?.hasRoute<Route.Search>() == true -> 0
        entry?.destination?.hasRoute<Route.Servers>() == true -> 6
        entry?.destination?.hasRoute<Route.Settings>() == true || entry?.destination?.hasRoute<Route.SettingsSub>() == true -> 7
        activeTab == 1 -> 4
        activeTab == 2 -> 3
        else -> 1
    }
    val focus = LocalTvTopNavigation.current ?: remember { FocusRequester() }
    fun openHome() {
        nav.switchTab(Route.Home)
        nav.currentBackStackEntry?.savedStateHandle?.let {
            it["tv.home.top"] = true
            // 从顶栏回首页焦点留在顶栏；不清掉的话旧卡片会抢回焦点把列表滚下去
            it.remove<String>("tv.focus")
        }
    }
    LaunchedEffect(Unit) {
        if (entry?.savedStateHandle?.get<Boolean>("tv.navigation") == true ||
            entry?.savedStateHandle?.get<String>("tv.focus") == null) {
            if (entry?.destination?.hasRoute<Route.Home>() == true) entry?.savedStateHandle?.set("tv.home.top", true)
            withFrameNanos { }; focus.requestFocus()
        }
    }
    // 一级页内容区按返回先回到顶栏当前项；焦点已在顶栏时交给下层返回/退出
    var navFocused by remember { mutableStateOf(false) }
    val rootPage = entry?.destination?.isTvRootPage() == true
    val pageBack = LocalTvPageBack.current
    BackHandler(enabled = rootPage && !navFocused) {
        if (pageBack.value?.invoke() != true) {
            if (entry?.destination?.hasRoute<Route.Home>() == true) entry?.savedStateHandle?.set("tv.home.top", true)
            focus.requestFocus()
        }
    }
    val refresh = LocalTvHomeRefresh.current?.value
    TopNavigation(current, privateSpace = privateSpace, serverName = name, aggregateLabel = "聚合视界", overlay = overlay,
        modifier = modifier.onFocusChanged {
            navFocused = it.hasFocus
            if (it.hasFocus) {
                entry?.savedStateHandle?.set("tv.navigation", true)
                entry?.savedStateHandle?.remove<String>("tv.focus")
            }
        },
        currentFocusRequester = focus,
        onServer = { if (privateSpace) app.leavePrivateSpace() else nav.navigate(Route.Servers) { launchSingleTop = true } },
        onPrivateSpace = { if (!privateSpace) scope.launch {
            runCatching { app.enterPrivateSpace() }.onFailure { app.report(it) }
        } },
        onContent = {
            entry?.savedStateHandle?.set("tv.navigation", false)
            content.requestFocus()
        },
        onSelect = { index ->
            when (index) {
                0 -> nav.navigate(Route.Search()) { launchSingleTop = true }
                1 -> if (entry?.destination?.hasRoute<Route.Home>() == true) refresh?.invoke() else openHome()
                3 -> nav.switchTab(Route.Favorites)
                4 -> nav.switchTab(Route.Aggregate)
                7 -> nav.navigate(Route.Settings) { launchSingleTop = true }
            }
        },
        onFocusSelect = { index ->
            when (index) {
                1 -> if (entry?.destination?.hasRoute<Route.Home>() != true) openHome()
                3 -> if (entry?.destination?.hasRoute<Route.Favorites>() != true) nav.switchTab(Route.Favorites)
                4 -> if (entry?.destination?.hasRoute<Route.Aggregate>() != true) nav.switchTab(Route.Aggregate)
            }
        })
}

/** 根页返回与手机伴侣事件共用实际导航栈；弹窗和页内返回仍优先。 */
@Composable
internal fun SharedTvNavigation(nav: NavHostController) {
    val app = LocalApp.current
    val activity = LocalContext.current as? Activity
    var exitAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry?.id) { exitAt = Long.MIN_VALUE / 2 }
    BackHandler {
        if (nav.previousBackStackEntry != null) {
            exitAt = Long.MIN_VALUE / 2
            nav.popBackStack()
        } else {
            if (app.privateSpace.value) app.leavePrivateSpace()
            else {
                val now = SystemClock.uptimeMillis()
                if (now - exitAt < 2000) activity?.finish()
                else { exitAt = now; app.toast("再按一次返回退出") }
            }
        }
    }
    CompanionBridge(onHome = { nav.switchTab(Route.Home) }, onOpen = { data ->
        if (!app.privateSpace.value) {
            val id = data.str("item_id")
            val server = data.str("server_id")
            if (id != null && (server.isNullOrBlank() || switchServerIfNeeded(app, server)))
                nav.navigate(Route.Detail(id, data.str("type") ?: "Movie"))
        }
    })
}
