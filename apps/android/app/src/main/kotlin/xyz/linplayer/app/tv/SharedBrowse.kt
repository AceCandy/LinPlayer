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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
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

/** 电视浏览页左右边距；首页头图向两侧铺满时按它反推。 */
internal val TvPageSide = 32.dp

/** 电视浏览页避开 72dp 顶栏并留左右边距。挂在每一页上而不是外层容器，切页动画里两页各带各的边距。 */
@Composable
internal fun TvPageInset(content: @Composable () -> Unit) {
    if (!LocalTvMode.current) { content(); return }
    Box(Modifier.fillMaxSize().padding(top = TvDim.navigationH, start = TvPageSide, end = TvPageSide)) { content() }
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
    val accent = Lp.colors.acc
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
            if (focused && highlight) drawRoundRect(color.copy(alpha = .12f), cornerRadius = CornerRadius(8.dp.toPx()))
            drawContent()
            if (focused && highlight) {
                val width = minOf(28.dp.toPx(), size.width / 2)
                drawRoundRect(accent, Offset((size.width - width) / 2, size.height - 4.dp.toPx()),
                    Size(width, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
}

/** 顶栏只调用手机导航，不另维护TV页面栈。 */
@Composable
internal fun SharedTopNavigation(nav: NavHostController, activeTab: Int, content: FocusRequester, overlay: Boolean = false) {
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
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    TopNavigation(current, privateSpace = privateSpace, serverName = name, aggregateLabel = "聚合视界", overlay = overlay,
        onRefresh = LocalTvHomeRefresh.current?.value?.takeIf { entry?.destination?.hasRoute<Route.Home>() == true },
        currentFocusRequester = focus,
        onServer = { if (privateSpace) app.leavePrivateSpace() else nav.navigate(Route.Servers) { launchSingleTop = true } },
        onPrivateSpace = { if (!privateSpace) scope.launch {
            runCatching { app.enterPrivateSpace() }.onFailure { app.report(it) }
        } },
        onContent = { content.requestFocus() },
        onSelect = { index ->
            when (index) {
                0 -> nav.navigate(Route.Search()) { launchSingleTop = true }
                1 -> nav.switchTab(Route.Home)
                3 -> nav.switchTab(Route.Favorites)
                4 -> nav.switchTab(Route.Aggregate)
                7 -> nav.navigate(Route.Settings) { launchSingleTop = true }
            }
        },
        onFocusSelect = { index ->
            when (index) {
                1 -> if (entry?.destination?.hasRoute<Route.Home>() != true) nav.switchTab(Route.Home)
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
    BackHandler {
        if (!nav.popBackStack()) {
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
