package xyz.linplayer.app.tv

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
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

/** 当前首页登记刷新动作，离页撤销；顶栏不复制页面的请求与私密分支。 */
internal val LocalTvHomeRefresh = staticCompositionLocalOf<MutableState<(() -> Unit)?>?> { null }
internal val LocalTvHomeScroll = staticCompositionLocalOf<MutableState<Int>?> { null }

/** 首页头图当前取色；一级页以它铺底，与首页保持同一氛围。 */
internal val LocalTvTone = staticCompositionLocalOf<MutableState<androidx.compose.ui.graphics.Color?>?> { null }

/** 电视浏览页左右边距；首页头图向两侧铺满时按它反推。 */
internal val TvPageSide = 16.dp

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
        if (it.isFocused) entry?.savedStateHandle?.set("tv.focus", id)
    }.then(if (onLongClick != null) Modifier.menuKey(onLongClick) else Modifier)
        .drawWithContent {
            // 半透明胶囊整块托底，比底部短线醒目；不画外框
            if (focused && highlight) drawRoundRect(color.copy(alpha = .2f),
                cornerRadius = CornerRadius(minOf(size.height / 2, 24.dp.toPx())))
            drawContent()
        }
}

/** 海报类焦点：明显放大、浅色描边加投影，支点偏下让图多向上长、少压片名；只改绘制，不动焦点几何。 */
@Composable
internal fun Modifier.tvPosterFocus(focused: Boolean, corner: Dp): Modifier {
    if (!LocalTvMode.current) return this
    val amount by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f,
        xyz.linplayer.app.ui.theme.lpTween(xyz.linplayer.app.ui.theme.T.T3), label = "tvPosterFocus")
    return drawWithContent {
        if (amount == 0f) { drawContent(); return@drawWithContent }
        val radius = CornerRadius(corner.toPx())
        val outline = Path().apply { addRoundRect(RoundRect(size.toRect(), radius)) }
        scale(1f + .08f * amount, Offset(size.width / 2, size.height * .7f)) {
            // 投影只画在轮廓外，透明底的卡片不会被垫黑
            clipPath(outline, ClipOp.Difference) {
                drawIntoCanvas {
                    it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius.x, radius.y,
                        android.graphics.Paint().apply {
                            setShadowLayer(16.dp.toPx(), 0f, 6.dp.toPx(),
                                android.graphics.Color.argb((150 * amount).toInt(), 0, 0, 0))
                        })
                }
            }
            this@drawWithContent.drawContent()
            val stroke = 2.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = .9f * amount), Offset(stroke / 2, stroke / 2),
                Size(size.width - stroke, size.height - stroke), CornerRadius(radius.x - stroke / 2), style = Stroke(stroke))
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
        if (entry?.savedStateHandle?.get<String>("tv.focus") == null) {
            withFrameNanos { }; focus.requestFocus()
        }
    }
    // 一级页内容区按返回先回到顶栏当前项；焦点已在顶栏时交给下层返回/退出
    var navFocused by remember { mutableStateOf(false) }
    val rootPage = entry?.destination?.isTvRootPage() == true
    BackHandler(enabled = rootPage && !navFocused) {
        if (entry?.destination?.hasRoute<Route.Home>() == true) entry?.savedStateHandle?.set("tv.home.top", true)
        focus.requestFocus()
    }
    val refresh = LocalTvHomeRefresh.current?.value
    TopNavigation(current, privateSpace = privateSpace, serverName = name, aggregateLabel = "聚合视界", overlay = overlay,
        modifier = modifier.onFocusChanged { navFocused = it.hasFocus },
        currentFocusRequester = focus,
        onServer = { if (privateSpace) app.leavePrivateSpace() else nav.navigate(Route.Servers) { launchSingleTop = true } },
        onPrivateSpace = { if (!privateSpace) scope.launch {
            runCatching { app.enterPrivateSpace() }.onFailure { app.report(it) }
        } },
        onContent = { content.requestFocus() },
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
