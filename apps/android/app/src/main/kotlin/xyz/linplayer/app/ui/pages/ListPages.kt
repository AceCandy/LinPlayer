package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.MediaRowHeader
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.BlockBox
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.LpTag
import xyz.linplayer.app.ui.components.MediaFilterChip
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.Panel
import xyz.linplayer.app.ui.components.Skeleton
import xyz.linplayer.app.ui.components.StepperRow
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import androidx.compose.runtime.derivedStateOf
import androidx.navigation.toRoute

/**
 * 收藏(U1.9a)。**2026-09-12 起是底栏第三个 Tab**,所以没有返回键。
 *
 * 排序档位表在核心层(`emby.FavoriteSorts`),这里只按名字传过去 ——
 * 服务端那条路对某些 fork 是死的,排序是核心层本地做的。
 */
@Composable
fun FavoritesPage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    val session = app.session.collectAsStateWithLifecycle().value
    val key = "fav.${session?.server}.${session?.userId}"
    var block by xyz.linplayer.app.data.keepState<Block<List<Item>>>(key) { Block.Loading }
    var sort by xyz.linplayer.app.data.keepState("$key.sort") { FAV_SORTS[0] }
    /* 手里这份是按哪一档拉的。少了它,下面那道「已经有结果就别重拉」的闸
       会把换档位一起吞掉 —— 和媒体库那个坑同一个形状。 */
    var fetchedSort by xyz.linplayer.app.data.keepState<String?>("$key.as") { null }
    var reload by remember { mutableStateOf(0) }
    var nextIndex by xyz.linplayer.app.data.keepState("$key.next") { 0 }
    var hasMore by xyz.linplayer.app.data.keepState("$key.more") { false }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }

    suspend fun fetch(offset: Int) {
        val gen = generation
        when (val r = app.block("emby.listFavorites", args("sort" to sort, "start_index" to offset, "limit" to 60))) {
            is Block.Ok -> if (gen == generation) {
                val page = Page.from(r.value)
                val previous = if (offset == 0) emptyList() else (block as? Block.Ok)?.value.orEmpty()
                block = Block.Ok((previous + page.items).distinctBy { it.id })
                nextIndex = r.value.obj().long("next_index")?.toInt() ?: offset + page.items.size
                hasMore = r.value.obj().bool("has_more")
                moreFailed = false
            }
            is Block.Fail -> if (gen == generation) {
                if (offset == 0) block = r else { moreFailed = true; app.report(Exception(r.message)) }
            }
            else -> Unit
        }
    }
    fun loadMore() {
        if (!hasMore || loadingMore) return
        loadingMore = true
        val gen = generation
        scope.launch { try { fetch(nextIndex) } finally { if (gen == generation) loadingMore = false } }
    }

    LaunchedEffect(key, reload, sort) {
        // ☠ 判据必须带上档位,否则就是媒体库那个坑(2026-09-12「筛选了不刷新」)
        if (reload == 0 && block is Block.Ok && fetchedSort == sort) return@LaunchedEffect
        generation++; loadingMore = false; moreFailed = false
        fetchedSort = sort; nextIndex = 0; hasMore = false; block = Block.Loading
        fetch(0)
    }
    LaunchedEffect(Unit) { app.invalidate.collect { if (it == "library" || it == "all") reload++ } }

    LpScaffold("收藏", actions = {
        // 数据源的收藏单独一页(D326):它们不在 Emby 服务器上,排序档位也对不上
        // 「观看历史」和「全部收藏」是一对(SPEC 8.7 D326)
        xyz.linplayer.app.ui.components.LpIconButton(LpIcons.rewind, "观看历史") { nav.navigate(Route.History) }
        xyz.linplayer.app.ui.components.LpIconButton(LpIcons.plugin, "数据源收藏") { nav.navigate(Route.SourceFavorites) }
    }) { pad ->
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16),
                horizontalArrangement = Arrangement.spacedBy(Sp.x8),
            ) {
                FAV_SORTS.forEach { s -> MediaFilterChip(s, s == sort) { sort = s } }
            }
            BlockBox(block, { reload++ }, skeleton = { GridSkel(pad) }) { items ->
                if (items.isEmpty() && !hasMore) EmptyState(
                    "还没有收藏任何内容",
                    "在任意封面上长按 → 收藏,或者在详情页点右上角那颗心。收藏会跟着服务器走。",
                    LpIcons.heart,
                ) else LazyVerticalGrid(
                    GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), grid,
                    contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                    verticalArrangement = Arrangement.spacedBy(Sp.x16),
                ) {
                    items(items, key = { it.id }) {
                        MediaCard(it, app.imageUrl(it.id, "Primary", 330),
                            { nav.navigate(Route.Detail(it.id, it.type)) },
                            Modifier.fillMaxWidth(), menu = cardActions(app, scope, it))
                    }
                    if (hasMore) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        if (!moreFailed) LaunchedEffect(nextIndex) { loadMore() }
                        LpButton(if (loadingMore) "加载中…" else if (moreFailed) "重试加载" else "加载更多",
                            { loadMore() }, Modifier.fillMaxWidth(), BtnKind.Secondary)
                    }
                }
            }
        }
    }
}

/** 收藏排序档位。**必须和核心层 `emby.FavoriteSorts` 逐字一致** —— 对不上就静默落回第一档。 */
private val FAV_SORTS = listOf("更新时间", "名称", "评分", "年份")

/**
 * 下载(U1.12)。
 *
 * ★ **「清除已完成」只清记录,不删文件**;每条右边那个 ✕ 才是删文件。
 *   两个语义正相反,**别合并成一个命令**。
 * ★ 并发数**只读不灌**:核心层持久化,UI 读回来显示。
 */
@Composable
fun DownloadsPage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()

    data class Task(val id: String, val title: String, val state: String,
                    val progress: Float, val received: Long, val total: Long, val error: String?)
    var tasks by remember { mutableStateOf<List<Task>?>(null) }
    var speeds by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var failure by remember { mutableStateOf<Block.Fail?>(null) }
    var reload by remember { mutableStateOf(0) }
    var threads by remember { mutableStateOf(2.0) }

    fun parse(e: kotlinx.serialization.json.JsonElement?): List<Task> = e.arr().mapNotNull {
        val o = it.obj() ?: return@mapNotNull null
        val title = o.str("title") ?: "下载任务"
        val series = o.str("series_name").orEmpty()
        val season = o.long("season_number")
        val episode = o.long("episode_number")
        val heading = listOfNotNull(series.takeIf { it.isNotBlank() },
            if (season != null && episode != null) "S${season}E${episode}" else null, title).joinToString(" · ")
        Task(o.str("id") ?: return@mapNotNull null, heading, o.str("status") ?: "queued",
            (o.dbl("progress") ?: 0.0).toFloat().coerceIn(0f, 1f),
            o.long("received_bytes") ?: 0L, o.long("total_bytes") ?: 0L, o.str("error"))
    }

    // 核心不发送 download.progress；仅在页面可见时采样，离页/退后台取消。
    LaunchedEffect(reload, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = emptyMap<String, Pair<Long, Long>>()
            speeds = emptyMap()
            while (true) {
                when (val result = app.block("download.list")) {
                    is Block.Ok -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val rows = parse(result.value)
                        speeds = rows.filter { it.state == "downloading" }.mapNotNull { t ->
                            val old = previous[t.id] ?: return@mapNotNull null
                            if (now <= old.second || t.received < old.first) return@mapNotNull null
                            t.id to (t.received - old.first) * 1000.0 / (now - old.second)
                        }.toMap()
                        previous = rows.filter { it.state == "downloading" }.associate { it.id to (it.received to now) }
                        tasks = rows
                        failure = null
                    }
                    is Block.Fail -> { failure = result; speeds = emptyMap(); previous = emptyMap() }
                    is Block.Loading -> Unit
                }
                delay(2000)
            }
        }
    }
    LaunchedEffect(Unit) {
        // download.list 是数组；不传 threads 的 setThreads 才是只读回读，不能把默认值灌回核心。
        val cur = runCatching { app.call("download.setThreads") }.getOrNull().obj()
        threads = (cur.long("threads") ?: 2L).toDouble()
    }

    fun action(command: String, id: String? = null) {
        scope.launch {
            when (val result = app.block(command, id?.let { args("id" to it) })) {
                is Block.Ok -> {
                    reload++
                    if (command == "download.clearCompleted") app.toast("已清除完成的记录(文件保留)", ToastKind.Ok)
                }
                is Block.Fail -> if (!result.isSilent) app.toast(result.message, ToastKind.Error)
                is Block.Loading -> Unit
            }
        }
    }

    LpScaffold("下载", onBack = { nav.popBackStack() }, actions = {
        LpIconButton(LpIcons.trash, "清除已完成") { action("download.clearCompleted") }
    }) { pad ->
        Column(Modifier.fillMaxSize()) {
            Panel(Modifier.padding(Sp.x16)) {
                StepperRow("同时下载", threads, 1.0, 4.0, 1.0, { v ->
                    val before = threads
                    threads = v
                    scope.launch {
                        when (val result = app.block("download.setThreads", args("threads" to v.toInt()))) {
                            is Block.Ok -> threads = (result.value.obj().long("threads") ?: v.toLong()).toDouble()
                            is Block.Fail -> { threads = before; if (!result.isSilent) app.toast(result.message, ToastKind.Error) }
                            is Block.Loading -> Unit
                        }
                    }
                }, fmt = { it.toInt().toString() })
                Text("清除已完成仅清记录；单项删除会删除文件",
                    Modifier.padding(start = Sp.x16, end = Sp.x16, bottom = Sp.x12),
                    color = Lp.colors.fg2, fontSize = 12.sp)
            }
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                failure?.takeUnless { it.isSilent }?.let { error ->
                    item("error") { ErrorState(error.message, { reload++ }) }
                }
                val rows = tasks
                if (rows == null && failure == null) item("loading") {
                    Column(Modifier.padding(horizontal = Sp.x16)) {
                        repeat(3) { Skeleton(Modifier.fillMaxWidth().height(120.dp)); Spacer(Modifier.height(Sp.x12)) }
                    }
                }
                if (rows?.isEmpty() == true && failure == null) item("empty") {
                    EmptyState("下载队列是空的", "在详情页长按菜单中添加下载任务。", LpIcons.download)
                }
                items(rows.orEmpty(), key = { it.id }) { t ->
                    val c = Lp.colors
                    Panel(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x6)) {
                        Column(Modifier.padding(Sp.x16)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title, Modifier.weight(1f), color = c.fg, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                when (t.state) {
                                    "paused", "failed" -> LpIconButton(LpIcons.play,
                                        if (t.state == "failed") "重试下载" else "继续下载", tint = c.mediaIcon) {
                                        action("download.resume", t.id)
                                    }
                                    "queued", "downloading" -> LpIconButton(LpIcons.pause, "暂停下载", tint = c.mediaIcon) {
                                        action("download.pause", t.id)
                                    }
                                }
                                LpIconButton(LpIcons.close, "删除任务与文件", tint = c.bad) { action("download.remove", t.id) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(when (t.state) {
                                    "queued" -> "等待下载"; "downloading" -> "正在下载"; "paused" -> "已暂停"
                                    "completed" -> "已完成"; "failed" -> "下载失败"; "canceled" -> "已取消"; else -> "状态未知"
                                }, color = if (t.state == "failed") c.bad else c.mediaIcon, fontSize = 12.sp)
                                if (t.total > 0) Text("${(t.progress * 100).toInt()}%", color = c.fg2, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(Sp.x6))
                            if (t.total > 0) LinearProgressIndicator(progress = { t.progress },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(R.pill)),
                                color = c.mediaAccent, trackColor = c.s3)
                            Spacer(Modifier.height(Sp.x6))
                            Text(listOfNotNull(
                                "${downloadSize(t.received)} / ${if (t.total > 0) downloadSize(t.total) else "大小未知"}",
                                speeds[t.id]?.let { "${downloadSize(it.toLong())}/s" },
                            ).joinToString(" · "), color = c.fg2, fontSize = 12.sp)
                            t.error?.takeIf { t.state == "failed" && it.isNotBlank() }?.let {
                                Text(it, Modifier.padding(top = Sp.x8), color = c.bad, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun downloadSize(bytes: Long): String = when {
    bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.1f KB".format(bytes / 1024.0)
}


@Composable
private fun GridSkel(pad: PaddingValues) {
    LazyVerticalGrid(
        GridCells.Adaptive(112.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        verticalArrangement = Arrangement.spacedBy(Sp.x16),
    ) {
        items(List(12) { it }) {
            Column {
                Skeleton(Modifier.fillMaxWidth().height(168.dp))
                Spacer(Modifier.height(Sp.x6))
                Skeleton(Modifier.fillMaxWidth(0.8f).height(12.dp))
            }
        }
    }
}

/**
 * 「按某个类型 / 标签 / 工作室 列条目」
 * 【用户定 2026-09-12:「支持点击 标签 工作室 类型 的跳转」】。
 *
 * ★ 工作室走的是 `studio_ids` 不是 `studios`:实测(Emby 4.9.5)按名字筛被完全无视,
 *   返回全库 1673 条,头几条的工作室对不上;按 id 才精确命中。
 */
@Composable
fun FacetPage(nav: NavController, entry: androidx.navigation.NavBackStackEntry) {
    val route = entry.toRoute<Route.Facet>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var first by remember { mutableStateOf<Block<Unit>>(Block.Loading) }
    var total by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }

    suspend fun fetch(offset: Int) {
        val q = buildMap<String, Any> {
            put("start_index", offset); put("limit", FACET_PAGE)
            put("sort_by", "SortName"); put("sort_order", "Ascending")
            put(facetParam(route.kind), jsonArrayOf(listOf(route.value)))
        }
        // parent_id 空 = 不限库:按标签找片本来就不该被「你现在在哪个库」框住
        val a = mapOf("parent_id" to "", "query" to args(*q.toList().toTypedArray()))
        when (val r = app.block("emby.listItemsPage", args(*a.toList().toTypedArray()))) {
            is Block.Ok -> {
                val p = Page.from(r.value)
                items = if (offset == 0) p.items else items + p.items
                total = p.total
                first = Block.Ok(Unit)
            }
            is Block.Fail -> if (offset == 0) first = r
            else -> Unit
        }
    }
    LaunchedEffect(route.kind, route.value) { fetch(0) }

    // 滚到底再拉下一页。闩防重入 —— 没有它的话一屏滚动能连发四五次同一页
    val needMore by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && last >= items.size - 8 &&
                (total == null || items.size < (total ?: 0))
        }
    }
    LaunchedEffect(needMore) {
        if (!needMore || loading) return@LaunchedEffect
        loading = true
        fetch(items.size)
        loading = false
    }

    val kindName = when (route.kind) { "tag" -> "标签"; "studio" -> "工作室"; else -> "类型" }
    LpScaffold(route.label, subtitle = kindName, onBack = { nav.popBackStack() },
        scrolled = rememberScrolled(grid)) { pad ->
        BlockBox(first, { scope.launch { fetch(0) } }, skeleton = { GridSkel(pad) }) {
            if (items.isEmpty()) EmptyState(
                "没有找到「${route.label}」下的内容",
                "这台服务器上可能没有刮到这一项,或者它只挂在被屏蔽的库里。",
                LpIcons.search,
            ) else LazyVerticalGrid(
                GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), grid,
                contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                verticalArrangement = Arrangement.spacedBy(Sp.x16),
            ) {
                items(items, key = { it.id }) {
                    MediaCard(it, app.imageUrl(it.id, "Primary", 330),
                        { nav.navigate(Route.Detail(it.id, it.type)) },
                        Modifier.fillMaxWidth(), menu = cardActions(app, scope, it))
                }
            }
        }
    }
}

/** 一页拉多少。和媒体库那页同一个数,别在这儿另起一档。 */
private const val FACET_PAGE = 120

/**
 * 落地页那一档要往 `emby.listItemsPage` 的 query 里放哪个参数名。
 *
 * ☠ 工作室是 **`studio_ids`** 不是 `studios`:实测(Emby 4.9.5)按名字筛被完全无视,
 * 返回全库 1673 条、头几条对不上;按 id 才精确命中。写成 `studios` 的表现是
 * 「点了工作室,出来的是一整个库」—— **一句错都不报**。
 */
internal fun facetParam(kind: String): String = when (kind) {
    "tag" -> "tags"
    "studio" -> "studio_ids"
    else -> "genres"
}

// ---------------------------------------------------------------- 全局观看历史(SPEC 8.7 D430 D431)

/** ticks 是 100 纳秒单位 —— 除以 1e7 才是秒。写成 1e6 时长会大十倍且不报错。 */
private fun clockOf(secs: Double): String {
    val s = secs.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * 全局观看历史。这份记录是**本地库**不是服务器的播放记录 —— 跨服续播就靠它,
 * 所以「当前服务器」和「全部」是两种看法,都要有。
 *
 * ★ 数据源那部分走 [SourceHistoryRow]:它已经按换源链路合并过(D431),
 *   来源被删的仍然显示但点不开(D333)—— 藏起来用户会以为记录丢了。
 */
@Composable
fun HistoryPage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var onlyCurrent by remember { mutableStateOf(true) }
    var recs by remember { mutableStateOf<Block<List<JsonObject>>>(Block.Loading) }
    var reload by remember { mutableStateOf(0) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        names = Account.list(runCatching { app.call("account.listAccounts") }.getOrNull())
            .associate { it.server to it.name }
    }
    LaunchedEffect(onlyCurrent, reload) {
        recs = Block.Loading
        recs = when (val result = app.block("emby.watchHistoryList", args("current_only" to onlyCurrent))) {
            is Block.Ok -> Block.Ok(result.value.arr().mapNotNull { it.obj() }
                .sortedByDescending { it.long("last_played_at") ?: 0L })
            is Block.Fail -> result
            is Block.Loading -> Block.Loading
        }
    }

    LpScaffold("观看历史", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16),
                horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                MediaFilterChip("当前服务器", onlyCurrent) { onlyCurrent = true }
                MediaFilterChip("全部服务器", !onlyCurrent) { onlyCurrent = false }
            }
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
                item("src") { SourceHistoryRow(nav) }
                item("h") { MediaRowHeader("服务器观看记录") }
                when (val result = recs) {
                    is Block.Loading -> item("loading") {
                        Column(Modifier.padding(Sp.x16)) {
                            repeat(4) { Skeleton(Modifier.fillMaxWidth().height(112.dp)); Spacer(Modifier.height(Sp.x10)) }
                        }
                    }
                    is Block.Fail -> if (!result.isSilent) item("error") { ErrorState(result.message, { reload++ }) }
                    is Block.Ok -> {
                        if (result.value.isEmpty()) item("empty") {
                            EmptyState("还没有观看记录", "观看记录保存在本机，可切换范围查看其他服务器的记录。", LpIcons.rewind)
                        }
                        items(result.value.take(200), key = { it.str("record_id") ?: "" }) { rec ->
                            val series = rec.str("series_title").orEmpty()
                            val title = rec.str("title").orEmpty()
                            val pos = (rec.long("last_position_ticks") ?: 0L) / 1e7
                            val run = (rec.long("run_time_ticks") ?: 0L) / 1e7
                            val played = rec.bool("played")
                            val progress = if (played) "已看完" else "已观看 ${clockOf(pos)}" + if (run > 0) " / ${clockOf(run)}" else ""
                            val itemId = rec.str("last_emby_item_id")
                            // scope_key 是 server:user_id，URL 可能带端口，必须按最后一个冒号切。
                            val sid = rec.str("scope_key").orEmpty().substringBeforeLast(':', "")
                            // last_played_at 是毫秒；ticks 则是 100 纳秒，不能共用换算。
                            val whenMs = rec.long("last_played_at") ?: 0L
                            val whenText = if (whenMs > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(whenMs)) else "时间未知"
                            Panel(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x6)) {
                                Column(Modifier.fillMaxWidth().pressable({
                                    if (itemId.isNullOrEmpty()) app.toast("这条记录没有对应的条目,换服务器后要先在设置里「扫描恢复」")
                                    else scope.launch {
                                        if (sid.isNotEmpty()) runCatching { app.call("account.setActiveServer", args("server_id" to sid)) }
                                            .onSuccess { app.refreshSession() }.onFailure { app.report(it); return@launch }
                                        nav.navigate(Route.Detail(itemId, if (series.isEmpty()) "Movie" else "Episode"))
                                    }
                                }).padding(Sp.x16)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (series.isEmpty()) title else "$series · $title", Modifier.weight(1f),
                                            color = Lp.colors.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(LpIcons.chevR, "查看详情", Modifier.padding(start = Sp.x8).size(18.dp), tint = Lp.colors.mediaIcon)
                                    }
                                    Text(names[sid] ?: "服务器名称不可用", Modifier.padding(top = Sp.x8),
                                        color = Lp.colors.mediaIcon, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(whenText, Modifier.padding(top = Sp.x4), color = Lp.colors.fg2, fontSize = 12.sp)
                                    Text(progress, Modifier.padding(top = Sp.x8), color = Lp.colors.fg2, fontSize = 12.sp)
                                    if (run > 0) LinearProgressIndicator(
                                        progress = { if (played) 1f else (pos / run).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.padding(top = Sp.x8).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(R.pill)),
                                        color = Lp.colors.mediaAccent, trackColor = Lp.colors.s3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
