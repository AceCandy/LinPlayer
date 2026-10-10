package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.CachedHomeImage
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.View
import xyz.linplayer.app.data.browseBlock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LongShotTarget
import xyz.linplayer.app.ui.components.CardAction
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.Hairline
import xyz.linplayer.app.ui.components.LpMenu
import xyz.linplayer.app.ui.components.LpMenuItem
import xyz.linplayer.app.ui.components.LpImmersive
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.MediaRowHeader
import xyz.linplayer.app.ui.components.LpRowSkeleton
import xyz.linplayer.app.ui.components.homePosterEntrance
import androidx.compose.ui.platform.testTag
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.lpTween
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/**
 * 首页(U1.3)：紧凑服务器栏下依次展示媒体库、继续观看和各库最新。
 *
 * ☠ **各块并发拉取、各自渲染,不设屏障**(SPEC §8.0 第 6 步)——
 * 这是**契约不是优化**。实测串行等待比并发慢 5.5 倍,而用户会把它描述成
 * 「不秒加载」并归咎于动画。
 *
 * 可见的媒体库最新轨独立并发，屏幕外栏目滚到时再加载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    // 截长屏认的就是这个滚动容器(设置里开了才画按钮,见 LongShot)
    LongShotTarget(list)


    /* ☠ 这几份数据以前是 `remember`,而 `remember` 的寿命是 composition ——
       点进任何一页再返回,首页**整个重拉一遍**(骨架闪一次)。
       底栏的 saveState/restoreState 保得住滚动位置(rememberSaveable),保不住它们。 */
    var resume by keepState<Block<List<Item>>>("home.resume") { Block.Loading }
    var views by keepState<Block<List<View>>>("home.views") { Block.Loading }
    var latest by keepState<Map<String, List<Item>>>("home.latest") { emptyMap() }
    var collections by keepState<Block<List<Item>>>("home.collections") { Block.Loading }
    var accounts by keepState<List<Account>>("home.accounts") { emptyList() }
    var reload by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val currentSession by app.session.collectAsState()
    val motionAccount = currentSession?.let { it.server to it.userId }
    var banners by remember(motionAccount) { mutableStateOf<List<CachedHomeImage>>(emptyList()) }
    val bannerItems = (resume.valueOrNull.orEmpty() + latest.values.flatten() + collections.valueOrNull.orEmpty())
        .distinctBy { it.id }
    LaunchedEffect(motionAccount, bannerItems, reload) {
        // 候选只取首页已有真实作品，图片复用核心与Coil缓存，不额外请求推荐数据。
        val found = bannerItems.filter { it.type in setOf("Movie", "Series", "Episode") }.shuffled().take(6)
            .mapNotNull { item -> app.imageUrl(item.id, if (item.hasBackdrop) "Backdrop" else "Primary", 720)
                ?.let { CachedHomeImage(item, it, item.hasBackdrop) } }
        currentCoroutineContext().ensureActive()
        if (app.session.value?.let { it.server to it.userId } == motionAccount) {
            if (banners.isEmpty() && found.isNotEmpty() && !list.isScrollInProgress &&
                list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0)
                list.requestScrollToItem(0)
            banners = found
        }
    }
    val bannerVisible by remember { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.any { it.key == "banners" }
    } }
    var canHideResume by remember(currentSession?.server, currentSession?.userId) { mutableStateOf(false) }
    LaunchedEffect(currentSession?.server, currentSession?.userId) {
        if (currentSession == null) return@LaunchedEffect
        val perm = runCatching { app.call("emby.permissions") }.getOrNull().obj()
        canHideResume = perm != null && perm["capabilities"].obj()?.get("hide_resume")?.toString() != "false"
    }
    /* 插件声明的首页栏目(SPEC 6.1,D156 D303)。
       ☠ 这张表以前**声明了没人画** —— 插件写了 homeSections,核心层没有取它的命令,
       壳自然也画不出来,而 manifest 合法、lp check 通过、贡献点清单里还列着它。 */
    var pluginSections by keepState<List<JsonObject>>("home.pluginSections") { emptyList() }
    LaunchedEffect(reload) {
        pluginSections = runCatching { app.call("plugin.homeSections") }.getOrNull()
            .arr().mapNotNull { it.obj() }
            .filter { !it.str("id").isNullOrEmpty() && !it.str("plugin_id").isNullOrEmpty() }
    }
    /** 顶栏服名展开的服务器选择菜单。 */
    var pickServer by remember { mutableStateOf(false) }

    // 保留已展示内容，返回首页时并行刷新服务端快照。
    LaunchedEffect(owner, currentSession?.server, currentSession?.userId, reload) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                coroutineScope {
                    launch {
                        val response = app.browseBlock("emby.listResume", args("limit" to 12)) {
                            if (resume !is Block.Ok) resume = Block.Ok(Item.list(it))
                        }.map { Item.list(it) }
                        if (response is Block.Ok || resume !is Block.Ok || response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND")) resume = response
                    }
                    // 服名入口读取完整本地账号表，不走网络。
                    launch { accounts = Account.list(app.block("account.listAccounts").valueOrNull) }
                    launch {
                        val response = app.browseBlock("emby.views") {
                            if (views !is Block.Ok) views = Block.Ok(View.list(it))
                        }.map { View.list(it) }
                        if (response is Block.Ok || views !is Block.Ok || response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND")) views = response
                    }
                }
            } finally {
                refreshing = false
            }
            awaitCancellation()
        }
    }

    // 屏幕外栏目不回源；任务归页面所有，滚离单个栏目不会反复取消重拉。
    LaunchedEffect(currentSession?.server, currentSession?.userId, reload) {
        val requested = mutableSetOf<String>()
        snapshotFlow {
            Triple(list.layoutInfo.visibleItemsInfo.map { it.key }, views.valueOrNull, latest)
        }.collect { (keys, visibleViews, _) ->
            for (key in keys) {
                if (key == "collections" && requested.add("collections")) {
                    launch {
                        val response = app.browseBlock("emby.listCollections") {
                            if (collections !is Block.Ok) collections = Block.Ok(Item.list(it))
                        }.map { Item.list(it) }
                        if (response is Block.Ok || collections !is Block.Ok || response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND")) collections = response
                    }
                }
                val view = visibleViews?.firstOrNull { key == "latest-${it.id}" } ?: continue
                if (requested.add(view.id)) {
                    launch {
                        val r = app.browseBlock("emby.listLatest", args("parent_id" to view.id, "limit" to 16)) {
                            if (view.id !in latest) latest = latest + (view.id to Item.list(it))
                        }
                        currentCoroutineContext().ensureActive()
                        if (r is Block.Fail && r.code in setOf("E_AUTH", "E_NOTFOUND")) latest = latest - view.id
                        r.valueOrNull?.let { latest = latest + (view.id to Item.list(it)) }
                    }
                }
            }
        }
    }

    // 屏蔽条目后**整页重拉,不在 UI 逐个过滤** —— 首页留存各栏目列表,
    // 挨个过滤 = 把核心层的规则在 UI 再抄一遍,抄错还不报错
    LaunchedEffect(Unit) {
        app.invalidate.collect { if (it == "library" || it == "accounts" || it == "all") reload++ }
    }

    var librariesHeight by remember(motionAccount) { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val resumeHeaderHeight = with(density) {
        if (UiPrefs.hideHomeLibraries.value) 0.dp else Sp.x26 + LpText.title.lineHeight.toDp() + Sp.x10
    }
    val heroExtension = if (resume.valueOrNull.orEmpty().isNotEmpty())
        (if (UiPrefs.hideHomeLibraries.value) 0.dp else librariesHeight) + resumeHeaderHeight +
            xyz.linplayer.app.ui.components.rowImageHeight(true) / 2
    else 0.dp

    val open: (Item) -> Unit = { nav.navigate(Route.Detail(it.id, it.type)) }
    var pendingPlayback by remember(motionAccount) { mutableStateOf<Item?>(null) }
    LaunchedEffect(motionAccount, pendingPlayback) {
        val item = pendingPlayback ?: return@LaunchedEffect
        try {
            val target = homePlaybackTarget(app, item)
            currentCoroutineContext().ensureActive()
            xyz.linplayer.app.ui.player.PlaybackClickTimes.navigate(nav, Route.Player(target.id, target.cardTitle))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { app.report(e) }
        finally { pendingPlayback = null }
    }
    val play: (Item) -> Unit = { item -> if (pendingPlayback == null) pendingPlayback = item }
    val menu: (Item) -> List<CardAction> = { cardActions(app, scope, it) }
    // 继续观看多一项「取消观看记录」(用户 2026-09-18:「我不想看 我也不想标记为已观看」)。
    // 打 HideFromResume,进度和已看状态都不动;成功后只从这一条里摘掉,别的块不受影响。
    val resumeMenu: (Item) -> List<CardAction> = { item ->
        cardActions(app, scope, item) + if (!canHideResume) emptyList() else listOf(CardAction("取消观看记录") {
            scope.launch {
                runCatching { app.call("emby.hideResume", args("item_id" to item.id, "hide" to true)) }
                    .onSuccess {
                        resume = resume.map { l -> l.filter { it.id != item.id } }
                        app.toast("已从继续观看中移除", ToastKind.Ok)
                    }
                    .onFailure { app.report(it) }
            }
        })
    }

    val switchServer: (Account) -> Unit = { a ->
        // 已经是这一台就什么都不做:再打一次 setActiveServer 会让整页白重拉
        if (!a.isActive) scope.launch {
            runCatching { app.call("account.setActiveServer", args("server_id" to a.id)) }
                .onSuccess {
                    /* ☠ 先把各块打回骨架。`PageCache.clear()`(在 boot 里)清的是那张哈希表,
                       **清不掉当前 composition 手里的那几个 MutableState** —— 不打回去的话,
                       新服务器的数据到位之前,屏幕上摆的是上一台的媒体库。那是界面在撒谎。 */
                    resume = Block.Loading
                    collections = Block.Loading
                    views = Block.Loading; latest = emptyMap()
                    // ★ **必须等 refreshSession**:首页各块读的是新会话,
                    //   不等的话它们拿旧服务器的凭据去拉内容
                    app.refreshSession()
                    reload++
                    app.toast("已切到「${a.name}」", ToastKind.Ok)
                }
                .onFailure { app.report(it) }
        }
    }

    LpImmersive(barHeight = 56.dp, barHorizontalPadding = Sp.x16,
        barBackground = if (xyz.linplayer.app.ui.components.rememberScrolled(list)) Lp.colors.bg else Color.Transparent, bar = {
        // 服名使用剩余宽度，长名称省略，避免挤出设置按钮。
        var anchorH by remember { mutableStateOf(0) }
        Box(Modifier.weight(1f).onSizeChanged { anchorH = it.height }) {
            ServerChip(accounts.firstOrNull { it.isActive }) { pickServer = !pickServer }
            ServerMenu(
                open = pickServer, anchorH = anchorH, accounts = accounts,
                onClose = { pickServer = false },
                onPick = { a -> pickServer = false; switchServer(a) },
                // ☠ 这里必须是 navigate 不是 switchTab:2026-09-12 起服务器**不是 Tab 了**,
                //    switchTab 会把它当成一根平级栈弹到起点 —— 表现是返回键回不到首页
                onManage = { pickServer = false; nav.navigate(Route.Servers) },
                onAdd = { pickServer = false; nav.navigate(Route.AddServer) },
            )
        }
        Box(Modifier.size(48.dp).pressable({ nav.navigate(Route.Settings) }),
            contentAlignment = Alignment.Center) {
            Icon(LpIcons.settings, "设置", Modifier.size(24.dp), tint = Lp.colors.fg2)
        }
    }) { pad ->
        // 地基块失败才整页报错:只有 emby.views 是地基
        val v = views
        if (v is Block.Fail && !v.isSilent) {
            ErrorState(v.message, { reload++ }, Modifier.fillMaxSize())
            return@LpImmersive
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { if (!refreshing) { refreshing = true; reload++ } },
            modifier = Modifier.fillMaxSize().padding(
                top = if (banners.isEmpty()) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp else 0.dp,
            ),
        ) {
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                if (banners.isNotEmpty()) item("banners") {
                    CachedHomeBanner(banners, bannerVisible && !list.isScrollInProgress, open,
                        backgroundExtension = heroExtension, onPlay = play, primaryImage = { app.imageUrl(it.id, "Primary", 720) },
                        logoImage = { if (it.hasLogo) app.imageUrl(it.id, "Logo", 120) else null })
                }
                if (!UiPrefs.hideHomeLibraries.value) item("views") {
                    Box(Modifier.fillMaxWidth().onSizeChanged {
                        librariesHeight = with(density) { it.height.toDp() }
                    }) {
                        when (v) {
                            is Block.Loading -> LazyRow(
                                Modifier.padding(top = Sp.x4), contentPadding = PaddingValues(horizontal = Sp.x16),
                                horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                            ) { items(3) { Box(Modifier.size(158.dp, 90.dp).clip(RoundedCornerShape(R.md)).background(Lp.colors.s1)) } }
                            is Block.Ok -> if (v.value.isEmpty()) EmptyState(
                                "这个账号下没有媒体库", "在服务器上建一个库,或者换一台服务器试试。",
                            ) else ViewsRow(v.value, nav)
                            is Block.Fail -> Unit
                        }
                    }
                }

                item("resume") {
                    RowBlock(if (UiPrefs.hideHomeLibraries.value) "" else "继续观看", resume, thumb = true, app = app, open = open, menu = resumeMenu,
                        homeAccount = motionAccount, resume = true)
                }

                // 每个媒体库一条「最新」轨。未到的画骨架 —— 否则首屏下半是空的
                v.valueOrNull.orEmpty().forEach { view ->
                    item("latest-${view.id}") {
                        /* ☠ 影片轨道用 **2:3 竖版海报**,`thumb` 是「16:9 剧照卡」的开关,
                           只有分集(继续观看)才该开。传成 true 的话首页下半整片变横图,
                           而 Emby 给的 Primary 本来就是竖的,横过来是被 Crop 裁掉一条。 */
                        /* ★ 轨道标题就是**库名本身**,不缀「· 最新」【用户定 2026-09-06】:
                           首页从上到下五六条轨全带同一个后缀,那个词一个字的信息都不提供,
                           只是把每条标题拉长、把库名挤窄。 */
                        val items = latest[view.id]
                        if (items == null) LpRowSkeleton(view.name, thumb = false)
                        else if (items.isNotEmpty()) LpRow(
                            view.name, items,
                            { app.imageUrl(it.id, "Primary", 330) }, open, thumb = false, menu = menu,
                            onMore = { nav.navigate(Route.Library(view.id, view.name)) }, homeAccount = motionAccount,
                        )
                    }
                }
                item("collections") {
                    RowBlock("合集", collections, thumb = false, app = app, open = open, menu = menu,
                        homeAccount = motionAccount)
                }
                // 插件栏目排在官方栏目**后面**(D156:新装的追加到末尾)
                items(pluginSections, key = { "ps:" + it.str("plugin_id") + ":" + it.str("id") }) { sec ->
                    PluginHomeSection(sec, nav, motionAccount)
                }
                item("tail") { Spacer(Modifier.height(Sp.x26)) }
            }
        }
    }

}

/** 首页播放沿用既有Player入口，剧集按详情页的季/分集契约解析，不使用缓存进度起播。 */
internal suspend fun homePlaybackTarget(app: xyz.linplayer.app.data.AppState, item: Item): Item {
    if (!item.isSeries) return item
    val seasons = Item.list(app.call("emby.seriesSeasons", args("series_id" to item.id)))
    var first: Item? = null
    for (season in seasons) {
        val episodes = app.seasonEpisodes(season.id)
        if (first == null) first = episodes.firstOrNull()
        episodes.firstOrNull { !it.played }?.let { return it }
    }
    return first ?: throw IllegalStateException("没有可播放的分集")
}

/** 全宽电影Hero；触摸/纵向滚动/离屏/后台暂停，手动操作后重新计满轮播间隔。 */
@Composable
internal fun CachedHomeBanner(
    images: List<CachedHomeImage>,
    visible: Boolean,
    open: (Item) -> Unit,
    onPlay: (Item) -> Unit = open,
    primaryImage: (Item) -> String? = { null },
    logoImage: (Item) -> String? = { null },
    backgroundExtension: Dp = 0.dp,
) {
    if (images.isEmpty()) return
    val c = Lp.colors
    val owner = LocalLifecycleOwner.current
    var index by remember(images) { mutableStateOf(0) }
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var manualRevision by remember { mutableStateOf(0) }
    val motion = xyz.linplayer.app.ui.theme.LocalMotionScale.current
    LaunchedEffect(owner, visible, images, touching, dragging, manualRevision, motion) {
        if (!visible || touching || dragging || images.size < 2 || motion <= 0f) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(5000)
                index = (index + 1) % images.size
            }
        }
    }
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val available = (LocalConfiguration.current.screenHeightDp.dp - status - bottom).coerceAtLeast(0.dp)
    val contentMin = with(LocalDensity.current) { status + 56.dp + 64.sp.toDp() + 16.sp.toDp() + 84.dp }
    val heroHeight = (available * .42f).coerceIn(260.dp, 310.dp)
        .coerceAtMost(available * .65f).coerceAtLeast(contentMin)
    val fade = lpTween<Float>(400, LinearEasing)
    Box(Modifier.fillMaxWidth().height(heroHeight).testTag("home.banners")
        .pointerInput(images) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                touching = true
                try {
                    while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) { }
                } finally { touching = false; manualRevision++ }
            }
        }
        .pointerInput(images) {
            var travel = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; travel = 0f },
                onDragCancel = { dragging = false; manualRevision++ },
                onDragEnd = {
                    if (kotlin.math.abs(travel) >= 40.dp.toPx())
                        index = (index + (if (travel < 0) 1 else images.size - 1)) % images.size
                    dragging = false; manualRevision++
                },
            ) { change, amount -> change.consume(); travel += amount }
        }) {
        // 背景可溢出到后续栏目，测量占位仍是原Hero；前景手势和控件不会向下移动。
        AnimatedContent(images[index], modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
            val height = heroHeight.roundToPx()
            val expanded = height + backgroundExtension.roundToPx()
            val placeable = measurable.measure(constraints.copy(minHeight = expanded, maxHeight = expanded))
            layout(constraints.maxWidth, height) { placeable.placeRelative(0, 0) }
        }, contentKey = { it.url },
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "homeHero") { image ->
            var failed by remember(image.url) { mutableStateOf(false) }
            var logoFailed by remember(image.item.id) { mutableStateOf(false) }
            val url = if (failed) primaryImage(image.item) else image.url
            val density = LocalDensity.current
            var foregroundHeight by remember(image.item.id) { mutableStateOf(heroHeight / 2) }
            val fullHeight = heroHeight + backgroundExtension
            val titleStart = ((heroHeight - foregroundHeight) / fullHeight).coerceIn(0f, 1f)
            val heroEnd = heroHeight / fullHeight
            Box(Modifier.fillMaxSize().testTag("home.hero.background")) {
                NetImage(url, null, Modifier.fillMaxSize(), 0.dp, scale = ContentScale.Crop,
                    onLoadResult = { loaded ->
                        if (!loaded && !failed && image.backdrop) failed = true
                        // 即使海报也缺失仍保留标题与播放入口，静态底色避免布局跳动。
                    })
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to (if (c.isDark) Color.Black.copy(alpha = .6f) else c.bg.copy(alpha = .92f)),
                    titleStart * .45f to Color.Transparent, titleStart to c.bg.copy(alpha = .91f),
                    heroEnd to c.bg.copy(alpha = .94f), 1f to c.bg)))
                Box(Modifier.fillMaxWidth().height(heroHeight).pressable({ open(image.item) })) {
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .onSizeChanged { foregroundHeight = with(density) { it.height.toDp() } }
                        .padding(start = Sp.x16, end = Sp.x16, bottom = Sp.x26),
                        verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
                        val logo = logoImage(image.item).takeUnless { logoFailed }
                        if (logo != null) NetImage(logo, image.item.cardTitle, Modifier.width(180.dp).height(56.dp),
                            0.dp, ContentScale.Fit, onLoadResult = { if (!it) logoFailed = true })
                        else Text(image.item.cardTitle, color = c.fg, style = LpText.hero,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val info = listOfNotNull(image.item.year?.toString(), image.item.genres.firstOrNull()).joinToString(" · ")
                        if (info.isNotBlank()) Text(info, color = c.fg2, style = LpText.caption,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(R.md)).background(c.acc)
                            .pressable({ onPlay(image.item) }).padding(horizontal = Sp.x20, vertical = Sp.x10),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                            Icon(LpIcons.play, null, Modifier.size(20.dp), tint = c.accFg)
                            Text(if (image.item.resumeSecs > 0 && !image.item.played) "继续播放" else "播放",
                                color = c.accFg, style = LpText.action)
                        }
                    }
                }
            }
        }
        if (images.size > 1) Row(Modifier.align(Alignment.BottomEnd).testTag("home.hero.indicators")
            .padding(end = Sp.x16, bottom = Sp.x10),
            horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
            images.forEachIndexed { page, _ ->
                Box(Modifier.size(if (page == index) 26.dp else 16.dp, 3.dp).clip(RoundedCornerShape(R.pill))
                    .background(if (page == index) c.acc else Color.White.copy(alpha = .3f)))
            }
        }
    }
}

/** 紧凑服名入口：无底色和箭头，共用服务器图标，长名称省略。 */
@Composable
private fun ServerChip(account: Account?, onClick: () -> Unit) {
    val c = Lp.colors
    val icon = account?.id?.let { rememberServerIcon(it) }
    Row(
        Modifier.heightIn(min = 56.dp)
            .pressable(onClick)
            .padding(end = Sp.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (icon != null) androidx.compose.foundation.Image(
                icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            ) else Icon(LpIcons.server, null, Modifier.size(24.dp), tint = c.acc)
        }
        Spacer(Modifier.width(Sp.x8))
        Text(
            account?.name ?: "服务器", Modifier.weight(1f, fill = false),
            color = c.fg, style = LpText.title,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 换服务器 = **从服名底下展开的一张列表**【用户定 2026-09-07】。
 *
 * ☠ 上一版是一个居中弹窗。弹窗是「打断你,让你回答一个问题」;而换服务器是
 *   顶栏那颗按钮的**展开态** —— 它不该盖住整页,也不该让人先看一遍标题。
 */
@Composable
private fun ServerMenu(
    open: Boolean,
    anchorH: Int,
    accounts: List<Account>,
    onClose: () -> Unit,
    onPick: (Account) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
) {
    LpMenu(open, onClose, Alignment.TopStart, androidx.compose.ui.unit.IntOffset(0, anchorH + 8), solid = true) {
        accounts.forEach { a ->
            LpMenuItem(
                a.name, { onPick(a) },
                sub = a.remark?.takeIf { it.isNotBlank() } ?: a.userName,
                selected = a.isActive,
            )
        }
        if (accounts.isNotEmpty()) Hairline(Modifier.padding(vertical = Sp.x6))
        LpMenuItem("管理服务器…", onManage)
        LpMenuItem("添加服务器…", onAdd)
    }
}

@Composable
private fun RowBlock(
    title: String,
    block: Block<List<Item>>,
    thumb: Boolean,
    app: xyz.linplayer.app.data.AppState,
    open: (Item) -> Unit,
    menu: (Item) -> List<CardAction>,
    homeAccount: Pair<String, String>?,
    resume: Boolean = false,
) {
    when (block) {
        is Block.Loading -> LpRowSkeleton(title, thumb)
        // 空轨整条不画:一条只有标题的空轨比没有它更让人困惑
        is Block.Ok -> if (block.value.isNotEmpty()) LpRow(
            title, block.value,
            { app.imageUrl(it.id, "Primary", if (thumb) 220 else 330) },
            open, m = if (resume) Modifier.testTag("home.resume") else Modifier,
            thumb = thumb, menu = menu, resume = resume, homeAccount = homeAccount,
        )
        // 各块各自 catch:一个区块失败不整页报错
        is Block.Fail -> Unit
    }
}

/**
 * 媒体库入口条(照 PC 端:**封面卡,不是一行图标加一行字**)。
 *
 * ☠ **封面严禁裁剪**【用户定 2026-09-06】:各家库的封面比例五花八门(方的、16:9 的、
 *   海报比例的),`Crop` 会把库名的字直接切掉半边。所以是 `Fit` + 一块底色垫底,
 *   宁可两侧留边也不切。
 * ★ 未加载或缺少封面时居中显示库名，加载成功后只展示封面。
 */
/**
 * 插件的一条首页栏目(D303)。
 *
 * `kind=custom` 整块交给插件自己画(轮播、日历、流量这类);
 * `kind=items` 插件只给条目,由壳画成和数据源一样的海报行 —— 主题自动跟随。
 *
 * ★ 拿不到就**整条不画**:插件栏目是锦上添花,不该在首页上留一行空标题,
 *   更不该因为某个插件抽风就把首页弄坏。
 */
@Composable
private fun PluginHomeSection(sec: JsonObject, nav: NavController, homeAccount: Pair<String, String>?) {
    val app = LocalApp.current
    val pid = sec.str("plugin_id") ?: return
    val sid = sec.str("id") ?: return
    val title = sec.str("title")?.takeIf { it.isNotEmpty() } ?: sid
    if (sec.str("kind") == "custom") {
        Column(Modifier.fillMaxWidth().padding(top = Sp.x20)) {
            MediaRowHeader(title)
            xyz.linplayer.app.ui.plugin.PluginSurface(pid, sec.str("block")?.takeIf { it.isNotEmpty() } ?: sid,
                modifier = Modifier.padding(horizontal = Sp.x16))
        }
        return
    }
    var items by remember(pid, sid) { mutableStateOf<List<JsonObject>?>(null) }
    LaunchedEffect(pid, sid) {
        items = runCatching { app.call("plugin.homeItems", args("plugin_id" to pid, "id" to sid)) }
            .getOrNull().arr().mapNotNull { it.obj() } ?: emptyList()
    }
    val got = items ?: return
    if (got.isEmpty()) return
    val shape = sec.str("shape") ?: "portrait"
    val row = rememberLazyListState()
    Column(Modifier.fillMaxWidth().padding(top = Sp.x20)) {
        MediaRowHeader(title)
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = Sp.x16),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        ) {
            itemsIndexed(got, key = { _, it -> it.str("id") ?: "" }) { index, x ->
                SourceCard(x, {
                    // 条目带来源(D282),按它回到对应数据源的详情页
                    nav.navigate(Route.SourceDetail(x.str("source") ?: "", x.str("id") ?: ""))
                }, Modifier.width(if (shape == "landscape") 200.dp else 120.dp)
                    .then(if (homeAccount != null) Modifier.homePosterEntrance(x.str("id") ?: "", index, homeAccount, row) else Modifier),
                    shape = shape)
            }
        }
    }
}

@Composable
private fun ViewsRow(views: List<View>, nav: NavController) {
    val c = Lp.colors
    val app = LocalApp.current
    LazyRow(
        Modifier.fillMaxWidth().padding(top = Sp.x4).testTag("home.libraries"),
        contentPadding = PaddingValues(horizontal = Sp.x16),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
    ) {
        items(views, key = { it.id }) { v ->
            Box(
                Modifier.size(158.dp, 90.dp).clip(RoundedCornerShape(R.md)).background(c.s1)
                    .pressable({ nav.navigate(Route.Library(v.id, v.name)) }),
                contentAlignment = Alignment.Center,
            ) {
                NetImage(if (v.hasPrimary) app.imageUrl(v.id, "Primary", 260) else null, v.name,
                    Modifier.fillMaxSize(), R.md, ContentScale.Fit,
                    placeholder = {
                        Text(v.name, Modifier.fillMaxWidth().padding(Sp.x12), color = c.fg,
                            style = LpText.card, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    })
            }
        }
    }
}

/**
 * 命令参数。
 *
 * ☠ **JsonElement 必须原样透传。** 少了这一支的时候,数组和对象会走 `toString()`
 *   变成一个**字符串** —— 核心层那边 `strList` 只认 JSON 数组,拿到字符串直接当空表。
 *   表现是 `emby.search` 的「包括集」开关点了没反应,而且两边都不报错。
 */
internal fun args(vararg pairs: Pair<String, Any>): JsonObject =
    JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is kotlinx.serialization.json.JsonElement -> v
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

/** `listOf("a","b")` → JSON 数组。核心层的 strList 只认这个形状。 */
internal fun jsonArrayOf(items: List<String>) =
    kotlinx.serialization.json.JsonArray(items.map { JsonPrimitive(it) })

internal fun <T, Rn> Block<T>.map(f: (T) -> Rn): Block<Rn> = when (this) {
    is Block.Loading -> Block.Loading
    is Block.Fail -> this
    is Block.Ok -> Block.Ok(f(value))
}

/**
 * 卡片长按菜单。**这份定义是全站唯一的**(UI_MOBILE.md §4.3)——
 * 各页自己拼一套会长出「A 页有『标记已看』B 页没有」这种不一致。
 */
internal fun cardActions(
    app: xyz.linplayer.app.data.AppState,
    scope: kotlinx.coroutines.CoroutineScope,
    item: Item,
    serverId: String? = null,
): List<CardAction> = listOf(
    CardAction(if (item.played) "标为未看" else "标为已看") {
        scope.launch {
            runCatching {
                app.call("emby.setPlayed", args(*listOfNotNull("item_id" to item.id, "played" to !item.played, serverId?.let { "server_id" to it }).toTypedArray()))
            }.onFailure { app.report(it) }
        }
    },
    CardAction("收藏") {
        scope.launch {
            runCatching {
                app.call("emby.setFavorite", args(*listOfNotNull("item_id" to item.id, "fav" to true, serverId?.let { "server_id" to it }).toTypedArray()))
            }.onSuccess { app.toast("已加入收藏", xyz.linplayer.app.data.ToastKind.Ok) }
                .onFailure { app.report(it) }
        }
    },
) + if (serverId == null) listOf(
    CardAction("下载") {
        scope.launch {
            runCatching { app.call("download.enqueue", args("item_id" to item.id)) }
                .onSuccess { app.toast("已加入下载队列", xyz.linplayer.app.data.ToastKind.Ok) }
                .onFailure { app.report(it) }
        }
    }
) else emptyList()
