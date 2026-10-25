package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.tv.LocalTvMode

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import xyz.linplayer.app.ui.components.LpPullIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.CachedHomeImage
import xyz.linplayer.app.data.cachedHomeImages
import xyz.linplayer.app.data.homeHeroCandidates
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.View
import xyz.linplayer.app.data.displayBlock
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
import xyz.linplayer.app.ui.components.sharedPoster
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.LpEasing
import androidx.compose.ui.unit.IntOffset
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.LpIcons
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.components.privateNeonBorder
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
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val privateSpace by app.privateSpace.collectAsState()
    androidx.activity.compose.BackHandler(enabled = privateSpace) { app.leavePrivateSpace() }
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
    var banners by keepState<List<CachedHomeImage>>("home.banners:$motionAccount") { emptyList() }
    val heroRevision by app.userStateRevision.collectAsState()
    val bannerItems = remember(resume, latest, collections) {
        (resume.valueOrNull.orEmpty() + latest.values.flatten() + collections.valueOrNull.orEmpty()).distinctBy { it.id }
    }
    LaunchedEffect(motionAccount, bannerItems, reload, heroRevision) {
        // 只探测核心只读缓存路由，随机新增候选不触发远程图片下载。
        if (currentSession == null) return@LaunchedEffect
        val epoch = app.spaceRevision.value
        val pool = Item.list(app.block("emby.cachedItems").valueOrNull)
        val candidates = app.homeHeroCandidates(bannerItems + pool)
        // 返回时保留首项及候选顺序，避免恢复滚动位置后又删除/插入Hero。
        val retained = banners.mapNotNull { previous -> candidates.firstOrNull { it.id == previous.item.id } }
        val retainedIds = retained.map { it.id }.toSet()
        banners = banners.filter { it.item.id in retainedIds }
        fun update(found: List<CachedHomeImage>, complete: Boolean) {
            if (app.session.value?.let { it.server to it.userId } != motionAccount || app.spaceRevision.value != epoch) return
            val byId = found.associateBy { it.item.id }
            val kept = banners.mapNotNull { byId[it.item.id] ?: it.takeUnless { complete } }
            val keptIds = kept.map { it.item.id }.toSet()
            val next = kept + found.filterNot { it.item.id in keptIds }
            if (banners.isEmpty() && next.isNotEmpty() && !list.isScrollInProgress &&
                list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) list.requestScrollToItem(0)
            banners = next
        }
        val cachedImages = app.cachedHomeImages(retained + candidates.filterNot { it.id in retainedIds }.shuffled()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { update(it, complete = false) }
        }
        currentCoroutineContext().ensureActive()
        update(cachedImages, complete = true)
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
                        val response = app.displayBlock("emby.listResume", args("limit" to 12)) {
                            if (resume !is Block.Ok) resume = Block.Ok(Item.list(it))
                        }.map { Item.list(it) }
                        if (response is Block.Ok || resume !is Block.Ok || response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND", "E_PERMISSION", "E_INVALID", "E_UNSUPPORTED")) resume = response
                    }
                    // 服名入口读取完整本地账号表，不走网络。
                    launch { accounts = Account.list(app.block("account.listAccounts").valueOrNull) }
                    launch {
                        val response = app.displayBlock("emby.views", args("home" to true)) {
                            if (views !is Block.Ok) views = Block.Ok(View.list(it))
                        }.map { View.list(it) }
                        if (response is Block.Ok || views !is Block.Ok || response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND", "E_PERMISSION", "E_INVALID", "E_UNSUPPORTED")) views = response
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
                        val response = app.displayBlock("emby.listCollections") {
                            if (collections !is Block.Ok) collections = Block.Ok(Item.list(it))
                        }.map { Item.list(it) }
                        if (response is Block.Ok || collections !is Block.Ok || response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND", "E_PERMISSION", "E_INVALID", "E_UNSUPPORTED")) collections = response
                    }
                }
                val view = visibleViews?.firstOrNull { key == "latest-${it.id}" } ?: continue
                if (requested.add(view.id)) {
                    launch {
                        val r = app.displayBlock("emby.listLatest", args("parent_id" to view.id, "limit" to 16)) {
                            if (view.id !in latest) latest = latest + (view.id to Item.list(it))
                        }
                        currentCoroutineContext().ensureActive()
                        if (r is Block.Fail && r.code in setOf("E_AUTH", "E_NOTFOUND", "E_PERMISSION", "E_INVALID", "E_UNSUPPORTED")) latest = latest - view.id
                        r.valueOrNull?.let { latest = latest + (view.id to Item.list(it)) }
                    }
                }
            }
        }
    }

    // 屏蔽条目后**整页重拉,不在 UI 逐个过滤** —— 首页留存各栏目列表,
    // 挨个过滤 = 把核心层的规则在 UI 再抄一遍,抄错还不报错
    LaunchedEffect(Unit) {
        app.invalidate.collect { if (it == "library" || it == "accounts" || it == "all" || it == "resume") reload++ }
    }

    var librariesHeight by remember(motionAccount) { mutableStateOf(90.dp + Sp.x4) }
    val density = LocalDensity.current
    val resumeStripHeight = xyz.linplayer.app.ui.components.rowHeight(true)
    val textMeasurer = rememberTextMeasurer()
    val initialResumeHeaderHeight = with(density) {
        Sp.x26 + maxOf(16.dp, textMeasurer.measure("继续观看", LpText.rail).size.height.toDp()) + Sp.x10
    }
    var resumeHeaderHeight by remember(motionAccount, density, initialResumeHeaderHeight) {
        mutableStateOf(initialResumeHeaderHeight)
    }
    val hiddenHeroSpace = if (banners.isNotEmpty() && UiPrefs.hideHomeLibraries.value)
        librariesHeight + if (resume.valueOrNull.orEmpty().isNotEmpty()) resumeHeaderHeight else 0.dp
    else 0.dp
    val heroExtension = if (resume.valueOrNull.orEmpty().isNotEmpty())
        (if (UiPrefs.hideHomeLibraries.value) 0.dp else librariesHeight + resumeHeaderHeight) +
            xyz.linplayer.app.ui.components.rowImageHeight(true) * 3 / 4
    else 0.dp

    /** 头图背景是否延伸到媒体库与续播标题下方。 */
    val heroUnderRows = banners.isNotEmpty() && heroExtension > 0.dp
    val open: (Item) -> Unit = { nav.navigate(Route.Detail(it.id, it.type)) }
    val menu: @Composable (Item) -> List<CardAction> = { rememberCardActions(app, scope, it) }
    // 继续观看多一项「取消观看记录」(用户 2026-09-18:「我不想看 我也不想标记为已观看」)。
    // 打 HideFromResume,进度和已看状态都不动;成功后只从这一条里摘掉,别的块不受影响。
    val resumeMenu: @Composable (Item) -> List<CardAction> = { item ->
        val base = rememberCardActions(app, scope, item)
        remember(base, canHideResume) { base + if (!canHideResume) emptyList() else listOf(CardAction("取消观看记录") {
            scope.launch {
                runCatching { app.call("emby.hideResume", args("item_id" to item.id, "hide" to true)) }
                    .onSuccess {
                        resume = resume.map { l -> l.filter { it.id != item.id } }
                        app.toast("已从继续观看中移除", ToastKind.Ok)
                    }
                    .onFailure { app.report(it) }
            }
        }) }
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

    LpImmersive(barHeight = if (tv) 0.dp else 56.dp, barHorizontalPadding = Sp.x16,
        barBackground = if (!tv && xyz.linplayer.app.ui.components.rememberScrolled(list)) {
            if (banners.isNotEmpty()) Color.Black.copy(alpha = .74f) else Lp.colors.bg.copy(alpha = .9f)
        } else Color.Transparent, bar = {
        if (!tv) {
        // 服名使用剩余宽度，长名称省略，避免挤出设置按钮。
        var anchorH by remember { mutableStateOf(0) }
        Box(Modifier.weight(1f).onSizeChanged { anchorH = it.height }) {
            ServerChip(accounts.firstOrNull { it.isActive },
                onImage = banners.isNotEmpty(),
                onLongClick = { if (!privateSpace) scope.launch {
                    try { app.enterPrivateSpace() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { app.report(e) }
                } },
            ) { if (privateSpace) app.leavePrivateSpace() else pickServer = !pickServer }
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
        if (!privateSpace) Box(Modifier.size(48.dp).pressable({ nav.navigate(Route.Settings) }),
            contentAlignment = Alignment.Center) {
            Icon(LpIcons.settings, "设置", Modifier.size(24.dp),
                tint = if (banners.isNotEmpty()) Color.White else Lp.colors.fg2)
        }
        }
    }) { pad ->
        // 地基块失败才整页报错:只有 emby.views 是地基
        val v = views
        if (v is Block.Fail && !v.isSilent) {
            ErrorState(v.message, { reload++ }, Modifier.fillMaxSize())
            return@LpImmersive
        }

        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            state = pullState,
            indicator = { LpPullIndicator(pullState, refreshing) },
            onRefresh = { if (!refreshing) {
                refreshing = true; reload++
                if (!privateSpace) scope.launch { app.block("emby.extCapabilities", args("refresh" to true)) }
            } },
            modifier = Modifier.fillMaxSize().padding(
                top = if (banners.isEmpty() && !tv) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp else 0.dp,
            ),
        ) {
            // 首屏什么都没有时只画一个居中加载图，内容到了整体淡入，不让各栏各自转圈
            val firstLoad = banners.isEmpty() && v is Block.Loading
            val contentAlpha by animateFloatAsState(if (firstLoad) 0f else 1f, lpTween(T.T4), label = "homeContent")
            LazyColumn(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }, list, contentPadding = pad) {
                if (banners.isNotEmpty()) item("banners") {
                    CachedHomeBanner(banners, bannerVisible && !list.isScrollInProgress, open,
                        backgroundExtension = heroExtension,
                        fadeLength = xyz.linplayer.app.ui.components.rowImageHeight(true) / 2,
                        // 分集在头图上显示剧名，简介也取所属剧的
                        loadOverview = { item, emit ->
                            val live = app.displayBlock("emby.itemDetail", args("item_id" to (item.seriesId ?: item.id))) { cached ->
                                cached.obj().str("overview")?.let(emit)
                            }
                            live.valueOrNull.obj().str("overview")?.let(emit)
                        },
                        foregroundExtension = hiddenHeroSpace,
                        originalImage = { if (it.hasBackdrop) app.imageUrl(it.id, "Backdrop", original = true) else null },
                        primaryImage = { item -> app.imageUrl(item.id, "Primary", original = true).takeUnless { item.hasPrimary == false } },
                        logoImage = { if (it.hasLogo) app.imageUrl(it.id, "Logo", 120)?.replaceFirst("/img?", "/img-cache?") else null })
                }
                if (!UiPrefs.hideHomeLibraries.value) item("views") {
                    Box(Modifier.fillMaxWidth().onSizeChanged {
                        librariesHeight = with(density) { it.height.toDp() }
                    }) {
                        when (v) {
                            is Block.Loading -> LazyRow(
                                Modifier.padding(top = Sp.x4), contentPadding = PaddingValues(horizontal = Sp.x16),
                                horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                            ) { items(3) { Box(Modifier.size(libraryCardSize()).clip(RoundedCornerShape(R.md)).background(Lp.colors.s1)) } }
                            is Block.Ok -> if (v.value.isEmpty()) EmptyState(
                                "这个账号下没有媒体库", "在服务器上建一个库,或者换一台服务器试试。",
                            ) else ViewsRow(v.value, nav, onImage = heroUnderRows)
                            is Block.Fail -> Unit
                        }
                    }
                }

                item("resume") {
                    Box(Modifier.fillMaxWidth().onSizeChanged {
                        if (!UiPrefs.hideHomeLibraries.value)
                            resumeHeaderHeight = (with(density) { it.height.toDp() } - resumeStripHeight).coerceAtLeast(0.dp)
                    }) {
                        // 深浅主题一致：头图延伸到续播图片中点，压在背景上的标题按叠图规则用白字。
                        androidx.compose.runtime.CompositionLocalProvider(
                            xyz.linplayer.app.ui.components.LocalRowHeaderOnImage provides heroUnderRows) {
                            RowBlock(if (UiPrefs.hideHomeLibraries.value) "" else "继续观看", resume, thumb = true, app = app, open = open, menu = resumeMenu,
                                homeAccount = motionAccount, resume = true)
                        }
                    }
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
                        if (items == null) HomeRowLoading(view.name, thumb = false)
                        else if (items.isNotEmpty()) LpRow(
                            view.name, items,
                            { it, h -> app.imageUrl(it.id, "Primary", h) }, open, thumb = false, menu = menu,
                            onMore = { nav.navigate(Route.Library(view.id, view.name)) }, homeAccount = motionAccount,
                        )
                    }
                }
                item("collections") {
                    RowBlock("合集", collections, thumb = false, app = app, open = open, menu = menu,
                        homeAccount = motionAccount)
                }
                // 插件栏目排在官方栏目**后面**(D156:新装的追加到末尾)
                items(if (privateSpace) emptyList() else pluginSections, key = { "ps:" + it.str("plugin_id") + ":" + it.str("id") }) { sec ->
                    PluginHomeSection(sec, nav, motionAccount)
                }
                item("tail") { Spacer(Modifier.height(Sp.x26)) }
            }
            if (firstLoad) xyz.linplayer.app.ui.components.LoadingState()
        }
    }

}

/** 头图评分用暖金色，和白字信息行区分开。 */
private val HeroScore = Color(0xFFFFC94D)

/** 全宽电影Hero；触摸/纵向滚动/离屏/后台暂停，手动操作后重新计满轮播间隔。点按整张进详情，不另设播放键。 */
@Composable
internal fun CachedHomeBanner(
    images: List<CachedHomeImage>,
    visible: Boolean,
    open: (Item) -> Unit,
    originalImage: (Item) -> String? = { null },
    primaryImage: (Item) -> String? = { null },
    logoImage: (Item) -> String? = { null },
    backgroundExtension: Dp = 0.dp,
    foregroundExtension: Dp = 0.dp,
    /** 背景延伸到下方栏目时，末端融入底色的渐变长度。 */
    fadeLength: Dp = 0.dp,
    /** 读取简介：可先回调缓存再回调实时值；列表数据不带简介。 */
    loadOverview: suspend (Item, (String) -> Unit) -> Unit = { _, _ -> },
) {
    if (images.isEmpty()) return
    val c = Lp.colors
    val textShadow = Shadow(Color.Black, blurRadius = with(LocalDensity.current) { 6.dp.toPx() })
    val owner = LocalLifecycleOwner.current
    val ids = images.map { it.item.id }
    var order by rememberSaveable { mutableStateOf(ids) }
    var currentId by rememberSaveable { mutableStateOf(ids.first()) }
    val currentIndex = ids.indexOf(currentId).takeIf { it >= 0 } ?: 0
    val image = images[currentIndex]
    val overviews = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val requested = remember { mutableSetOf<String>() }
    val latestLoad by androidx.compose.runtime.rememberUpdatedState(loadOverview)
    // 读取挂在组件作用域：换图不取消已发出的请求，每部只取一次
    val overviewScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val posterMotion = xyz.linplayer.app.ui.components.LocalPosterMotion.current
    val posterScene = xyz.linplayer.app.ui.components.LocalPosterScene.current
    // 当前与下一张一起取，轮到下一张时简介已在，文字不再二次跳动
    LaunchedEffect(currentId, order) {
        val position = order.indexOf(currentId)
        val next = order.getOrNull((position + 1) % order.size.coerceAtLeast(1))
        listOfNotNull(currentId, next).distinct().mapNotNull { id -> images.firstOrNull { it.item.id == id }?.item }
            .filter { requested.add(it.id) }
            .forEach { item -> overviewScope.launch { runCatching { latestLoad(item) { overviews[item.id] = it } } } }
        // 推移时新图整幅可见，提前解码下一张，避免推入一块空底
        images.firstOrNull { it.item.id == next && next != currentId }?.let {
            coil3.SingletonImageLoader.get(context).enqueue(coil3.request.ImageRequest.Builder(context).data(it.url).build())
        }
    }
    SideEffect {
        val updated = order.filter { it in ids } + ids.filterNot { it in order }
        if (updated != order) order = updated
        if (currentId !in ids) currentId = ids.first()
    }
    // 推移方向：自动和左滑为 1（新图从右侧进），右滑为 -1
    var slide by remember { mutableIntStateOf(1) }
    fun advance(direction: Int) {
        slide = direction
        val position = order.indexOf(currentId).coerceAtLeast(0)
        if (direction > 0 && position == order.lastIndex && order.size > 1) {
            val next = order.shuffled().toMutableList()
            if (next.first() == currentId) java.util.Collections.swap(next, 0, 1)
            order = next
            currentId = next.first()
        } else currentId = order[(position + direction + order.size) % order.size]
    }
    var progress by remember { mutableFloatStateOf(0f) }
    val tv = LocalTvMode.current
    var focused by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var manualRevision by remember { mutableStateOf(0) }
    val motion = xyz.linplayer.app.ui.theme.LocalMotionScale.current
    LaunchedEffect(owner, visible, images.size > 1, currentId, touching, dragging, focused, manualRevision, motion) {
        progress = 0f
        if (!visible || touching || dragging || focused || images.size < 2 || motion <= 0f) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val started = withInfiniteAnimationFrameNanos { it }
                do {
                    progress = withInfiniteAnimationFrameNanos { ((it - started) / 5_000_000_000f).coerceIn(0f, 1f) }
                } while (progress < 1f)
                advance(1)
                progress = 0f
            }
        }
    }
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val available = (LocalConfiguration.current.screenHeightDp.dp - status - bottom).coerceAtLeast(0.dp)
    val contentMin = with(LocalDensity.current) { status + 56.dp + 64.sp.toDp() + 16.sp.toDp() + 84.dp }
    // 平板屏宽足够，Hero 加高到半屏左右，避免剧照被裁成细长横条。
    val wide = !tv && LocalConfiguration.current.screenWidthDp >= 600
    val heroHeight = (available * if (wide) .5f else .42f).coerceIn(260.dp, if (wide) 460.dp else 310.dp)
        .coerceAtMost(available * .65f).coerceAtLeast(contentMin) + foregroundExtension
    Box(Modifier.fillMaxWidth().height(heroHeight).testTag("home.banners")
        .then(if (tv) Modifier.onFocusChanged { focused = it.hasFocus }.focusGroup() else Modifier)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                touching = true
                try {
                    while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) { }
                } finally { touching = false; manualRevision++ }
            }
        }
        .pointerInput(Unit) {
            var travel = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; travel = 0f },
                onDragCancel = { dragging = false; manualRevision++ },
                onDragEnd = {
                    if (kotlin.math.abs(travel) >= 40.dp.toPx())
                        advance(if (travel < 0) 1 else -1)
                    dragging = false; manualRevision++
                },
            ) { change, amount -> change.consume(); travel += amount }
        }) {
        // 隐藏媒体库时将腾出的高度并入Hero，后续续播卡片保持原位。
        val extended = Modifier.fillMaxWidth().layout { measurable, constraints ->
            val height = heroHeight.roundToPx()
            val expanded = height + backgroundExtension.roundToPx()
            val placeable = measurable.measure(constraints.copy(minHeight = expanded, maxHeight = expanded))
            layout(constraints.maxWidth, height) { placeable.placeRelative(0, 0) }
        }
        Box(extended.testTag("home.hero.background")) {
            // 同时推移：旧图向滑动方向整幅移出，新图紧贴着从另一侧推入，中间不透底色
            AnimatedContent(image, Modifier.fillMaxSize(), contentKey = { it.url },
                transitionSpec = {
                    val spec = tween<IntOffset>((T.T10 * motion).toInt(), easing = LpEasing.standard)
                    slideInHorizontally(spec) { it * slide } togetherWith slideOutHorizontally(spec) { -it * slide }
                }, label = "homeHero") { shown ->
                xyz.linplayer.app.ui.components.HeroArtwork(
                    originalImage(shown.item), shown.url, primaryImage(shown.item),
                    Modifier.fillMaxSize().sharedPoster(shown.item.id, "hero-${shown.item.id}"), loadOriginal = shown.item.id == image.item.id,
                )
            }
            // 顶部不压暗；只给文字区一段渐暗，并在续播图 1/4~3/4 之间融入页面底色
            val full = heroHeight + backgroundExtension
            fun at(y: Dp) = (y / full).coerceIn(0f, 1f)
            val band = if (backgroundExtension > 0.dp && fadeLength > 0.dp) fadeLength.coerceAtMost(backgroundExtension) else 24.dp
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent,
                at(heroHeight - 180.dp) to Color.Transparent,
                at(heroHeight - 110.dp) to Color.Black.copy(alpha = .4f),
                at(heroHeight - 40.dp) to Color.Black.copy(alpha = .66f),
                at(full - band) to Color.Black.copy(alpha = if (c.isDark) .74f else .68f),
                1f to c.bg)))
        }
        Box(Modifier.fillMaxWidth().height(heroHeight).pressable({
            // 登记来源后详情头图从这张剧照放大接上
            if (posterMotion != null && posterScene != null)
                posterMotion.open(posterScene.entry, image.item, "hero-${image.item.id}", image.url) { open(image.item) }
            else open(image.item)
        })) {
            AnimatedContent(image.item, Modifier.align(Alignment.BottomStart).fillMaxWidth(), contentKey = { it.id },
                contentAlignment = Alignment.BottomStart,
                // 文字随图同向推移但只走 1/3 幅宽，比背景慢一拍形成层次
                transitionSpec = {
                    (fadeIn(tween((T.T6 * motion).toInt(), delayMillis = (T.T3 * motion).toInt())) +
                        slideInHorizontally(tween((T.T10 * motion).toInt(), easing = LpEasing.standard)) { it * slide / 3 }) togetherWith
                        (fadeOut(tween((T.T4 * motion).toInt())) +
                            slideOutHorizontally(tween((T.T10 * motion).toInt(), easing = LpEasing.standard)) { -it * slide / 3 }) using
                        SizeTransform(clip = false)
                }, label = "homeHeroText") { item ->
                var logoFailed by remember(item.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().testTag("home.hero.text")
                    .padding(start = Sp.x16, end = Sp.x16, bottom = Sp.x26)
                    .then(if (wide) Modifier.wrapContentWidth(Alignment.Start).widthIn(max = 560.dp) else Modifier.padding(end = Sp.x34))
                    .animateContentSize(lpTween(T.T4)),
                    verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
                    val logo = logoImage(item).takeUnless { logoFailed }
                    if (logo != null) NetImage(logo, item.cardTitle,
                        if (wide) Modifier.width(260.dp).height(80.dp) else Modifier.width(180.dp).height(56.dp),
                        0.dp, ContentScale.Fit, onLoadResult = { if (!it) logoFailed = true })
                    else Text(item.cardTitle, color = Color.White,
                        style = LpText.banner.let { if (wide) it.copy(fontSize = 36.sp, lineHeight = 44.sp) else it }.copy(shadow = textShadow),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                        item.rating?.takeIf { it.isFinite() && it > 0 && it <= 10 }?.let { score ->
                            Text("★ " + "%.1f".format(java.util.Locale.ROOT, score), color = HeroScore,
                                style = LpText.caption.copy(fontWeight = FontWeight.SemiBold, shadow = textShadow), maxLines = 1)
                        }
                        item.doubanRating?.takeIf { it.isFinite() && it > 0 && it <= 10 }?.let { score ->
                            val text = "%.1f".format(java.util.Locale.ROOT, score)
                            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "豆瓣评分 $text" },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.x4)) {
                                androidx.compose.foundation.Image(LpIcons.douban, null, Modifier.size(14.dp))
                                Text(text, color = Color.White,
                                    style = LpText.caption.copy(fontWeight = FontWeight.SemiBold, shadow = textShadow), maxLines = 1)
                            }
                        }
                        val info = listOfNotNull(item.year?.toString(), *item.genres.take(2).toTypedArray()).joinToString(" · ")
                        if (info.isNotBlank()) Text(info, color = Color.White.copy(alpha = .92f), style = LpText.caption.copy(shadow = textShadow),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    overviews[item.id]?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color.White.copy(alpha = .82f), style = LpText.caption.copy(shadow = textShadow),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (images.size > 1) Row(Modifier.align(Alignment.BottomEnd).testTag("home.hero.indicators")
            .padding(end = Sp.x16, bottom = Sp.x10),
            horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
            repeat(minOf(images.size, 6)) { page ->
                val active = page == order.indexOf(currentId).coerceAtLeast(0) % minOf(images.size, 6)
                Canvas(Modifier.size(if (active) 26.dp else 16.dp, 3.dp).clip(RoundedCornerShape(R.pill))
                    .then(if (active) Modifier.testTag("home.hero.progress") else Modifier)) {
                    drawRect(Color.Black.copy(alpha = .4f))
                    drawRect(Color.White.copy(alpha = .45f))
                    if (active) drawRect(c.acc, size = size.copy(width = size.width * progress))
                }
            }
        }
    }
}

/** 紧凑服名入口保持无底无框，叠图使用白字阴影，长名称省略。 */
@Composable
internal fun ServerChip(account: Account?, onImage: Boolean = false, fallbackName: String = "服务器",
    onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Lp.colors
    val icon = account?.id?.let { rememberServerIcon(it) }
    val onHero = onImage
    Row(
        Modifier.heightIn(min = 56.dp)
            .pressable(onClick, onLongClick)
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
            account?.name ?: fallbackName, Modifier.weight(1f, fill = false),
            color = if (onHero) Color.White else c.fg,
            style = if (onHero) LpText.title.copy(shadow = Shadow(Color.Black, blurRadius = 6f)) else LpText.title,
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
internal fun ServerMenu(
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
                selected = a.isActive, large = true,
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
    menu: @Composable (Item) -> List<CardAction>,
    homeAccount: Pair<String, String>?,
    resume: Boolean = false,
) {
    when (block) {
        is Block.Loading -> HomeRowLoading(title, thumb)
        // 空轨整条不画:一条只有标题的空轨比没有它更让人困惑
        is Block.Ok -> if (block.value.isNotEmpty()) LpRow(
            title, block.value,
            { it, h -> app.imageUrl(it.id, "Primary", h) },
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
            // 插件数据不可信：空ID或重复ID都会让懒列表因重复key崩溃，拼上序号兜底
            itemsIndexed(got, key = { index, it -> "$index:" + (it.str("id") ?: "") }) { index, x ->
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

/** 保持横轨高度与可见范围的静态占位；加载图只在首页整页等待时居中出现一次。 */
@Composable
private fun HomeRowLoading(title: String, thumb: Boolean) = LpRowSkeleton(title, thumb)

/** 媒体库卡尺寸：平板放大，免得一排小卡缩在大屏左上角。 */
@Composable
private fun libraryCardSize() = if (!xyz.linplayer.app.tv.LocalTvMode.current && LocalConfiguration.current.screenWidthDp >= 600)
    androidx.compose.ui.unit.DpSize(220.dp, 124.dp) else androidx.compose.ui.unit.DpSize(158.dp, 90.dp)

@Composable
private fun ViewsRow(views: List<View>, nav: NavController, onImage: Boolean = false) {
    val c = Lp.colors
    val app = LocalApp.current
    LazyRow(
        Modifier.fillMaxWidth().padding(top = Sp.x4).testTag("home.libraries"),
        contentPadding = PaddingValues(horizontal = Sp.x16),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
    ) {
        items(views, key = { it.id }) { v ->
            Box(
                // 叠在头图上用半透明深底，浅色主题下不至于在暗背景上挖出浅灰方块。
                Modifier.size(libraryCardSize()).clip(RoundedCornerShape(R.md))
                    .background(if (onImage) Color.Black.copy(alpha = .28f) else c.s1)
                    .pressable({ nav.navigate(Route.Library(v.id, v.name)) }),
                contentAlignment = Alignment.Center,
            ) {
                NetImage(if (v.hasPrimary) app.imageUrl(v.id, "Primary", 260) else null, v.name,
                    Modifier.fillMaxSize(), R.md, ContentScale.Fit, placeholderWhileLoading = true,
                    placeholder = {
                        Text(v.name, Modifier.fillMaxWidth().padding(Sp.x12), color = if (onImage) Color.White else c.fg,
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
@Composable
internal fun rememberCardActions(
    app: xyz.linplayer.app.data.AppState,
    scope: kotlinx.coroutines.CoroutineScope,
    item: Item,
    serverId: String? = null,
    favoriteAction: Boolean = true,
): List<CardAction> {
    // 懒列表回收卡片后仍保留本次成功写入的状态；新服务端快照会重新校准。
    var played by rememberSaveable(item, serverId, app.session.value, app.privateSpace.value) { mutableStateOf(item.played) }
    var favorite by rememberSaveable(item, serverId, app.session.value, app.privateSpace.value) { mutableStateOf(item.favorite) }
    var busy by remember(item.id, serverId) { mutableStateOf(false) }
    val allowDownload = !xyz.linplayer.app.tv.LocalTvMode.current
    // 同一份列表跨重组复用：菜单是卡片参数，每次新建会让可见卡片全部跟着重组。
    return remember(item, played, favorite, serverId, favoriteAction, allowDownload, scope) {
        cardActions(app, scope, item.copy(played = played, favorite = favorite), serverId, favoriteAction,
            allowDownload = allowDownload,
            onChanged = { played = it.played; favorite = it.favorite },
            begin = { if (busy) false else { busy = true; true } }, finish = { busy = false })
    }
}

internal fun cardActions(
    app: xyz.linplayer.app.data.AppState,
    scope: kotlinx.coroutines.CoroutineScope,
    item: Item,
    serverId: String? = null,
    favoriteAction: Boolean = true,
    allowDownload: Boolean = true,
    onChanged: (Item) -> Unit = {},
    begin: () -> Boolean = { true },
    finish: () -> Unit = {},
): List<CardAction> {
    fun change(command: String, field: String, value: Boolean, updated: Item, message: String) {
        if (!begin()) return
        scope.launch {
            try {
                app.call(command, args(*listOfNotNull("item_id" to item.id, field to value,
                    serverId?.let { "server_id" to it }).toTypedArray()))
                onChanged(updated)
                app.toast(message, ToastKind.Ok)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) { app.report(e) }
            finally { finish() }
        }
    }
    val actions = mutableListOf(
        CardAction(if (item.played) "标为未看" else "标为已看",
            icon = if (item.played) LpIcons.checkCircle else LpIcons.check) {
            change("emby.setPlayed", "played", !item.played, item.copy(played = !item.played),
                if (item.played) "已标为未看" else "已标为已看")
        },
    )
    if (favoriteAction) actions += CardAction(
        if (item.favorite) "取消收藏" else "收藏", icon = if (item.favorite) LpIcons.heartOn else LpIcons.heart) {
        change("emby.setFavorite", "fav", !item.favorite, item.copy(favorite = !item.favorite),
            if (item.favorite) "已取消收藏" else "已加入收藏")
    }
    if (allowDownload && serverId == null && !app.privateSpace.value) actions += CardAction("下载", icon = LpIcons.download) {
        scope.launch {
            try {
                if (item.type == "Season" || item.isSeries)
                    app.call("download.enqueueSeason", args("parent_id" to item.id))
                else app.call("download.enqueue", args("item_id" to item.id))
                app.toast("已加入下载队列", ToastKind.Ok)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) { app.report(e) }
        }
    }
    return actions
}
