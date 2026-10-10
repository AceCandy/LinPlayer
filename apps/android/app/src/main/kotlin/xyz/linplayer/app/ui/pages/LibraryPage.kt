package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.ui.theme.LpText

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.Dim
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.browseBlock
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LongShotTarget
import xyz.linplayer.app.ui.components.BlockBox
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.Dim2
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.MediaFilterChip
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.LpRefreshIcon
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.Sp

/** 独立有效的库内排序；DateCreated在基准服务端是最新入库时间的别名。 */
private val SORTS = listOf(
    "更新日期" to "DateLastContentAdded",
    "上映日期" to "PremiereDate",
    "名称" to "SortName",
    "评分" to "CommunityRating",
)

/** 评分下限固定四档:服务端给的分级不是评分,**没有分面可列**。 */
private val RATINGS = listOf("不限" to 0, "9 分以上" to 9, "8 分以上" to 8, "7 分以上" to 7, "6 分以上" to 6)

private const val PAGE = 30

/**
 * 刷新前是否需要丢弃旧筛选的快照。
 *
 * ☠ 这道闸原来只判「手里有没有结果」,于是**换了筛选也当成「已经有了」直接跳过** ——
 * 界面一动不动(用户 2026-09-12:「移动端媒体库页的筛选不生效,筛选了不会刷新出现筛选结果」)。
 * 判据必须带上「手里这份是按哪套筛选拉的」。
 *
 * @param fetchedAs 手里这份结果对应的筛选签名;还没拉过是 null
 * @param key 现在这套筛选的签名
 */
internal fun needResetLibrary(hasItems: Boolean, ok: Boolean, fetchedAs: String?, key: String): Boolean =
    !(hasItems && ok && fetchedAs == key)

/**
 * 媒体库 + 筛选(U1.4)：紧凑顶栏与固定筛选区，网格复用首页媒体卡片。
 * ★ **排序一律走服务端。** 本地排只能排到已加载的那一页,翻页后顺序就乱了。
 * ★ 分页在核心层(offset/limit),UI 只负责**什么时候要下一页**(§10.2)。
 */
@Composable
fun LibraryPage(nav: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<Route.Library>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    // 截长屏认的就是这个滚动容器(设置里开了才画按钮,见 LongShot)
    LongShotTarget(grid)


    // ★ 键必须带 viewId —— 库 A 和库 B 是两页,共用一个键会串数据
    val ck = "lib.${route.viewId}"
    var items by xyz.linplayer.app.data.keepState<List<Item>>("$ck.items") { emptyList() }
    var total by xyz.linplayer.app.data.keepState<Long?>("$ck.total") { null }
    var first by xyz.linplayer.app.data.keepState<Block<Unit>>("$ck.first") { Block.Loading }
    var loadingMore by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(true) }
    var ended by xyz.linplayer.app.data.keepState("$ck.ended") { false }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    // 排序/筛选也留住:返回后筛选条被重置回默认,和「白重拉一次」一样恼人
    var sort by xyz.linplayer.app.data.keepState("$ck.sort") { SORTS[0] }
    var sortOrder by xyz.linplayer.app.data.keepState("$ck.sortOrder") { "Descending" }
    var minRating by xyz.linplayer.app.data.keepState("$ck.rating") { RATINGS[0] }
    var genre by xyz.linplayer.app.data.keepState<String?>("$ck.genre") { null }
    var showFilter by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf<Block<List<String>>>(Block.Loading) }
    var filtersSupported by xyz.linplayer.app.data.keepState("$ck.filtersSupported.${app.session.value?.server}") { true }
    /* 手里这份结果是**按哪套筛选**拉回来的。没有它就分不清「返回这一页」和
       「换了筛选」,而这两件事下面那道闸要走相反的路 —— 见 LaunchedEffect。 */
    var fetchedAs by xyz.linplayer.app.data.keepState<String?>("$ck.as") { null }

    val hasFilter = genre != null || minRating.second > 0
    val filterKey = "${sort.second}|$sortOrder|${minRating.second}|${genre.orEmpty()}"

    /* ☠ **这条命令收的是 `parent_id` + 一个嵌套的 `query` 对象**,不是平铺参数。
       平铺传过去核心层一个都读不到:`parent_id` 空 = 不限库、`query` 缺 = 默认分页,
       于是**每个媒体库点进去都是同一份全站列表**,而且不报错。
       这个写法(buildMap 展开成 args)以前躲开了 check-android-args.py 的正则,
       闸门已经补上,别再改回平铺。 */
    suspend fun request(offset: Int): Block<kotlinx.serialization.json.JsonElement> {
        val q = buildMap<String, Any> {
            put("start_index", offset); put("limit", PAGE)
            put("sort_by", sort.second)
            put("sort_order", sortOrder)
            if (filtersSupported) {
                if (minRating.second > 0) put("rating_min", minRating.second)
                genre?.let { put("genres", jsonArrayOf(listOf(it))) }
            }
        }
        val a = buildMap<String, Any> {
            put("parent_id", route.viewId)
            put("query", args(*q.toList().toTypedArray()))
        }
        val params = args(*a.toList().toTypedArray())
        return if (offset == 0) app.browseBlock("emby.listItemsPage", params) { cached ->
            if (items.isEmpty()) {
                val page = Page.from(cached)
                items = page.items; total = page.total; first = Block.Ok(Unit)
                ended = page.items.isEmpty() || page.total?.let { items.size >= it } == true
            }
        } else app.block("emby.listItemsPage", params)
    }

    // 同条件先保留页面/磁盘快照；刷新已加载的范围，不能拼接不同排序的旧尾页。
    LaunchedEffect(route.viewId, filterKey, reload) {
        refreshing = true; refreshError = null
        if (needResetLibrary(items.isNotEmpty(), first is Block.Ok, fetchedAs, filterKey)) {
            first = Block.Loading; items = emptyList(); total = null; ended = false
        }
        fetchedAs = filterKey
        try {
            coroutineScope {
                launch {
                    val wanted = items.size.coerceAtLeast(PAGE)
                    val fresh = mutableListOf<Item>()
                    var done = false
                    var count: Long? = null
                    while (fresh.size < wanted && !done) {
                        when (val response = request(fresh.size)) {
                            is Block.Ok -> {
                                currentCoroutineContext().ensureActive()
                                val page = Page.from(response.value)
                                fresh.addAll(page.items); count = page.total
                                done = page.items.isEmpty() || count?.let { fresh.size >= it } == true
                            }
                            is Block.Fail -> {
                                if (items.isEmpty() || response.code in setOf("E_AUTH", "E_NOTFOUND")) {
                                    items = emptyList(); first = response
                                } else refreshError = response.message
                                return@launch
                            }
                            else -> return@launch
                        }
                    }
                    if (items.isNotEmpty() && items.firstOrNull()?.id != fresh.firstOrNull()?.id &&
                        grid.firstVisibleItemIndex == 0 && !grid.isScrollInProgress) grid.requestScrollToItem(0)
                    items = fresh.toList(); total = count; ended = done; first = Block.Ok(Unit)
                }
                if (filters !is Block.Ok) launch {
                    filters = when (val r = app.block("emby.getFilters", args("parent_id" to route.viewId))) {
                        is Block.Ok -> {
                            currentCoroutineContext().ensureActive()
                            filtersSupported = r.value.obj()?.get("capabilities").obj()?.get("filters")?.toString() != "false"
                            if (!filtersSupported) { genre = null; minRating = RATINGS[0] }
                            Block.Ok(r.value.obj().strList("genres"))
                        }
                        is Block.Fail -> r
                        else -> Block.Loading
                    }
                }
            }
        } finally { refreshing = false }
    }

    // 记录可见末项和累计数；新一页到达后即使仍在底部也能继续触发。
    LaunchedEffect(route.viewId, filterKey, reload) {
        snapshotFlow { Triple(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, items.size, refreshing) }
            .collect { (last, size, busy) ->
                if (busy || loadingMore || ended || first !is Block.Ok || size == 0 || last < size - 6) return@collect
                loadingMore = true
                try {
                    when (val response = request(size)) {
                        is Block.Ok -> {
                            currentCoroutineContext().ensureActive()
                            val page = Page.from(response.value)
                            items = items + page.items; total = page.total
                            ended = page.items.isEmpty() || total?.let { items.size >= it } == true
                        }
                        is Block.Fail -> {
                            if (response.code in setOf("E_AUTH", "E_NOTFOUND")) {
                                items = emptyList(); first = response; ended = true
                            } else refreshError = response.message
                        }
                        else -> Unit
                    }
                } finally { loadingMore = false }
            }
    }

    LpScaffold(
        title = route.title,
        // 未结束展示已加载数量的下限，结束后展示完整数量。
        subtitle = if (first is Block.Ok) "${items.size}${if (ended) "" else "+"} 部" else null,
        onBack = { nav.popBackStack() },
        actions = {
            MediaSortControl(sort.first, sortOrder == "Ascending", { showFilter = true },
                Modifier.testTag("library.sort"))
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            refreshError?.let { message ->
                xyz.linplayer.app.ui.components.ErrorState(message, { reload++ })
            }
            if (hasFilter) FilterBar(
                genre = genre, rating = minRating,
                onClearGenre = { genre = null },
                onClearRating = { minRating = RATINGS[0] },
            )
            BlockBox(first, onRetry = { reload++ },
                skeleton = {
                    Box(Modifier.fillMaxSize().testTag("library.loading"), contentAlignment = Alignment.Center) {
                        LpRefreshIcon()
                    }
                }) {
                if (items.isEmpty()) {
                    // 空态区分三种:这里是「被筛掉了」
                    EmptyState(
                        if (hasFilter) "当前筛选没有结果" else "这个库还没有内容",
                        if (hasFilter) "把条件放宽一点,或者清除筛选。" else "服务器上这个库是空的。",
                        actionLabel = if (hasFilter) "清除筛选" else null,
                        onAction = if (hasFilter) ({ genre = null; minRating = RATINGS[0] }) else null,
                    )
                } else LazyVerticalGrid(
                    GridCells.Fixed(posterColumns()), Modifier.fillMaxSize(), grid,
                    contentPadding = PaddingValues(
                        start = Sp.x16, end = Sp.x16, top = Sp.x8, bottom = pad.calculateBottomPadding()),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                    verticalArrangement = Arrangement.spacedBy(Sp.x16),
                ) {
                    items(items, key = { it.id }, contentType = { "poster" }) { it2 ->
                        MediaCard(
                            it2, app.imageUrl(it2.id, "Primary", 330),
                            { nav.navigate(Route.Detail(it2.id, it2.type)) },
                            Modifier.fillMaxWidth(),
                            menu = cardActions(app, scope, it2),
                        )
                    }
                }
            }
        }
        if ((refreshing && first is Block.Ok) || loadingMore) {
            Box(Modifier.align(Alignment.Center).size(48.dp)
                .testTag("library.refreshing"), contentAlignment = Alignment.Center) {
                LpRefreshIcon()
            }
        }
        }
    }

    if (showFilter) LpDialog({ showFilter = false }, "筛选与排序") {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            SectionLabel("排序")
            SORTS.forEach { s ->
                OptRow(s.first, {
                    if (s != sort) { sort = s; sortOrder = if (s.second == "SortName") "Ascending" else "Descending" }
                    showFilter = false
                }, selected = s == sort, media = true)
            }
            SectionLabel("排序方向")
            OptRow("升序", { sortOrder = "Ascending"; showFilter = false }, selected = sortOrder == "Ascending", media = true)
            OptRow("降序", { sortOrder = "Descending"; showFilter = false }, selected = sortOrder == "Descending", media = true)
            if (!filtersSupported) Dim2("服务端不支持条件筛选,可使用排序和分页。", Modifier.padding(Sp.x12))
            else {
                SectionLabel("评分下限")
                RATINGS.forEach { r ->
                    OptRow(r.first, { minRating = r; showFilter = false }, selected = r == minRating)
                }
                SectionLabel("类型")
                when (val f = filters) {
                    is Block.Loading -> Dim2("正在取分面…", Modifier.padding(Sp.x12))
                    // 分面拉不到要**在筛选面板里明说**,不能静默变成「此库没有分面」
                    is Block.Fail -> if (!f.isSilent) Dim2("分面取不到:${f.message}", Modifier.padding(Sp.x12))
                    is Block.Ok -> if (f.value.isEmpty()) Dim2("这个库没有类型分面", Modifier.padding(Sp.x12))
                    else f.value.forEach { g ->
                        OptRow(g, { genre = if (genre == g) null else g; showFilter = false },
                            selected = genre == g)
                    }
                }
            }
            Spacer(Modifier.height(Sp.x12))
            LpButton("关闭", { showFilter = false }, Modifier.fillMaxWidth(), BtnKind.Secondary)
        }
    }
}

/** 只在有已选筛选时展示清除入口，默认不占一行。 */
@Composable
private fun FilterBar(
    genre: String?,
    rating: Pair<String, Int>,
    onClearGenre: () -> Unit,
    onClearRating: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = Sp.x16),
        horizontalArrangement = Arrangement.spacedBy(Sp.x8),
    ) {
        genre?.let { MediaFilterChip("$it ×", true, onClearGenre) }
        if (rating.second > 0) MediaFilterChip("${rating.first} ×", true, onClearRating)
    }
}

@Composable
private fun SectionLabel(t: String) =
    Text(t, color = Lp.colors.fg3, style = LpText.filter,
        modifier = Modifier.padding(start = Sp.x12, top = Sp.x12, bottom = Sp.x4))

/** 海报列数。插件主题的 `layout.posterColumns` 覆盖官方的 3(SPEC 11.4)。 */
@Composable
internal fun posterColumns(): Int = xyz.linplayer.app.ui.theme.PluginTheme.posterColumns(
    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp, 3)

/** 媒体库与收藏分类共用的右侧排序入口。 */
@Composable
internal fun MediaSortControl(label: String, ascending: Boolean, onClick: () -> Unit, m: Modifier = Modifier) {
    Row(
        m.heightIn(min = Dim.tap).widthIn(max = 168.dp)
            .semantics { contentDescription = "排序：$label，${if (ascending) "升序" else "降序"}" }
            .pressable(onClick).padding(start = Sp.x8, end = Sp.x12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Sp.x6),
    ) {
        Icon(LpIcons.sortLines, null, Modifier.size(18.dp), tint = Lp.colors.mediaIcon)
        Text(label, Modifier.weight(1f, fill = false), color = Lp.colors.mediaIcon,
            style = LpText.filter, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Icon(LpIcons.arrowDown, null,
            Modifier.size(18.dp).rotate(if (ascending) 180f else 0f),
            tint = Lp.colors.mediaIcon)
    }
}
