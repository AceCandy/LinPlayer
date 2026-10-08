package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.View
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
import xyz.linplayer.app.ui.components.LpRowSkeleton
import xyz.linplayer.app.ui.components.homePosterEntrance
import xyz.linplayer.app.ui.components.Skeleton
import androidx.compose.ui.platform.testTag
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.SectionTitle
import xyz.linplayer.app.ui.components.pressable
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

    // 每次恢复首页更新续播；库仅在首次加载或显式刷新时重取。
    LaunchedEffect(owner, currentSession?.server, currentSession?.userId, reload) {
        var loadHome = reload > 0 || views !is Block.Ok
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                coroutineScope {
                    launch { resume = app.block("emby.listResume", args("limit" to 12)).map { Item.list(it) } }
                    if (loadHome) {
                        // 空栏目恢复占位，才能在本轮再次按需加载。
                        latest = latest.filterValues { it.isNotEmpty() }
                        if (collections.valueOrNull?.isEmpty() == true) collections = Block.Loading
                        // 服名入口读取完整本地账号表，不走网络。
                        launch { accounts = Account.list(app.block("account.listAccounts").valueOrNull) }
                        launch { views = app.block("emby.views").map { View.list(it) } }
                    }
                }
                loadHome = false
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
                if (key == "collections" && requested.add("collections") &&
                    (reload > 0 || collections !is Block.Ok)) {
                    launch { collections = app.block("emby.listCollections").map { Item.list(it) } }
                }
                val view = visibleViews?.firstOrNull { key == "latest-${it.id}" } ?: continue
                if (requested.add(view.id) && (reload > 0 || view.id !in latest)) {
                    launch {
                        val r = app.block("emby.listLatest", args("parent_id" to view.id, "limit" to 16))
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

    val open: (Item) -> Unit = { nav.navigate(Route.Detail(it.id, it.type)) }
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

    LpImmersive(barHeight = 40.dp, barHorizontalPadding = Sp.x16, bar = {
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
        Box(Modifier.size(40.dp).pressable({ nav.navigate(Route.Settings) }),
            contentAlignment = Alignment.Center) {
            Icon(LpIcons.settings, "设置", Modifier.size(20.dp), tint = Lp.colors.fg2)
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
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 40.dp,
            ),
        ) {
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                item("views") {
                    when (v) {
                        is Block.Loading -> LazyRow(
                            Modifier.padding(top = Sp.x4), contentPadding = PaddingValues(horizontal = Sp.x16),
                            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                        ) { items(3) { Skeleton(Modifier.size(158.dp, 90.dp)) } }
                        is Block.Ok -> if (v.value.isEmpty()) EmptyState(
                            "这个账号下没有媒体库", "在服务器上建一个库,或者换一台服务器试试。",
                        ) else ViewsRow(v.value, nav)
                        is Block.Fail -> Unit
                    }
                }

                item("resume") {
                    RowBlock("继续观看", resume, thumb = true, app = app, open = open, menu = resumeMenu,
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

/** 紧凑服名入口：无底色和箭头，共用服务器图标，长名称省略。 */
@Composable
private fun ServerChip(account: Account?, onClick: () -> Unit) {
    val c = Lp.colors
    val icon = account?.id?.let { rememberServerIcon(it) }
    Row(
        Modifier.height(40.dp)
            .pressable(onClick)
            .padding(end = Sp.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (icon != null) androidx.compose.foundation.Image(
                icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            ) else Icon(LpIcons.server, null, Modifier.size(21.dp), tint = c.acc)
        }
        Spacer(Modifier.width(Sp.x8))
        Text(
            account?.name ?: "服务器", Modifier.weight(1f, fill = false),
            color = c.fg, fontSize = 14.sp,
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
            open, thumb = thumb, menu = menu, resume = resume, homeAccount = homeAccount,
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
            SectionTitle(title)
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
        SectionTitle(title)
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
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2,
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
