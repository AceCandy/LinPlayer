package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import xyz.linplayer.app.ui.components.LpIconButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.MediaRowHeader
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.LpImmersive
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.Skeleton
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.Sp

/** 搜索结果属于导航页，进入详情保留，搜索页出栈后自动释放。 */
internal class SearchPageState : ViewModel() {
    var initialized = false
    val q = mutableStateOf("")
    val aggregate = mutableStateOf(false)
    val refresh = mutableStateOf(0)
    val result = mutableStateOf<Block<List<Item>>?>(null)
    val history = mutableStateOf<List<String>>(emptyList())
    val aggRows = androidx.compose.runtime.mutableStateListOf<kotlinx.serialization.json.JsonObject>()
    val aggRun = mutableStateOf(0)
    val aggQuery = mutableStateOf("")
    val aggFailure = mutableStateOf<String?>(null)
    var completedQuery: Pair<String, Int>? = null
    var completedRun = 0
}

/** 搜索只查电影和剧集；输入框内切换聚合，开启/键盘搜索/下拉刷新发起聚合查询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(nav: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<Route.Search>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()

    val source = app.activeSource.collectAsState().value
    val session = app.session.collectAsState().value
    val onSource = source != null
    val state: SearchPageState = viewModel(viewModelStoreOwner = entry,
        key = "search:${session?.server}:${session?.userId}:${source?.id}")
    if (!state.initialized) {
        state.q.value = route.q.orEmpty()
        state.aggregate.value = onSource
        state.initialized = true
    }
    var q by state.q
    var aggregate by state.aggregate
    var refresh by state.refresh
    var result by state.result
    var history by state.history
    val aggRows = state.aggRows
    var aggRun by state.aggRun
    var aggQuery by state.aggQuery
    var aggFailure by state.aggFailure
    var refreshing by remember(state) { mutableStateOf(false) }
    var aggBusy by remember(state) { mutableStateOf(false) }
    val resultScroll = rememberLazyListState()
    val aggregateScroll = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 预填了词就别抢焦点弹键盘 —— 用户是来看结果的,不是来打字的
    LaunchedEffect(Unit) { if (q.isBlank()) focus.requestFocus() }

    // 关键词/模式变化取消旧查询；刷新保留当前关键词与库内范围。
    LaunchedEffect(state, q.trim(), aggregate, refresh) {
        val text = q.trim()
        if (text.isEmpty() || (aggregate && route.viewId == null)) {
            result = null
            if (text.isEmpty()) refreshing = false
            if (text != aggQuery) { aggRun = 0; state.completedRun = 0; aggRows.clear(); aggFailure = null }
            return@LaunchedEffect
        }
        if (state.completedQuery == (text to refresh) && result != null && result !is Block.Loading) return@LaunchedEffect
        try {
            delay(250)
            result = Block.Loading
            val a = buildMap<String, Any> {
                put("query", text)
                put("types", jsonArrayOf(listOf("Series", "Movie")))
                route.viewId?.let { put("parent_id", it) }
            }
            val response = app.block("emby.search", args(*a.toList().toTypedArray()))
            currentCoroutineContext().ensureActive()
            result = when (val r = response) {
                is Block.Ok -> Block.Ok(Page.from(r.value).items)
                is Block.Fail -> r
                else -> Block.Loading
            }
            state.completedQuery = text to refresh
        } finally { refreshing = false }
    }

    fun searchAggregate() {
        if (q.isBlank()) return
        aggQuery = q.trim(); aggRun++
    }
    LaunchedEffect(state, aggRun, aggregate) {
        if (aggRun == 0 || !aggregate || route.viewId != null) return@LaunchedEffect
        if (state.completedRun == aggRun) return@LaunchedEffect
        val run = aggRun
        val text = aggQuery
        aggRows.clear(); aggBusy = true; aggFailure = null
        try {
            coroutineScope {
                val requestScope = this
                app.call("source.aggregateSearch", args("query" to text)) { part ->
                    part.obj()?.let { row -> requestScope.launch {
                        if (run == aggRun && aggregate && text == q.trim()) aggRows.add(row)
                    } }
                }
            }
            currentCoroutineContext().ensureActive()
            state.completedRun = run
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            aggFailure = if (e is CoreException) e.advice else e.message ?: "聚合搜索失败"
            app.report(e)
            state.completedRun = run
        }
        finally { aggBusy = false; refreshing = false }
    }

    LpImmersive(barHeight = 0.dp) { pad ->
        PullToRefreshBox(refreshing, {
            if (q.isNotBlank() && !refreshing) {
                refreshing = true
                if (aggregate && route.viewId == null) searchAggregate() else refresh++
            }
        }, Modifier.fillMaxSize().statusBarsPadding().padding(top = Sp.x12)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                LpField(q, { q = it }, if (route.viewId != null) "在这个库里搜" else "搜片名、剧名或演员",
                    Modifier.padding(horizontal = Sp.x16).focusRequester(focus).testTag("search.field"),
                    trailingIcon = if (route.viewId == null && !onSource) ({
                        LpIconButton(
                            LpIcons.layers, if (aggregate) "关闭聚合搜索" else "开启聚合搜索",
                            Modifier.testTag("search.aggregate").semantics { selected = aggregate },
                            tint = if (aggregate) Lp.colors.mediaAccent else Lp.colors.fg2,
                        ) {
                            aggregate = !aggregate; refreshing = false
                            if (aggregate) searchAggregate()
                        }
                    }) else null,
                    onSearch = {
                        keyboard?.hide()
                        if (aggregate && route.viewId == null) searchAggregate() else refresh++
                    },
                )
                Spacer(Modifier.height(Sp.x16))

                /* 插件的搜索快捷动作(D242)。摆在搜索框下面、结果上面 —— 结果列表本身不动。
                   一条都没有时整行不画,不在搜索框下面留一条空白。 */
                val actions = xyz.linplayer.app.ui.plugin.rememberSearchActions(q)
                if (actions.isNotEmpty()) Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16, vertical = Sp.x2),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x8),
                ) {
                    actions.forEach { a ->
                        xyz.linplayer.app.ui.components.LpButton(a.str("title") ?: "", {
                            scope.launch { xyz.linplayer.app.ui.plugin.runSearchAction(app, a) }
                        })
                    }
                }

                if (aggregate && route.viewId == null && aggBusy) {
                    Dim3("还有来源在搜…", Modifier.padding(horizontal = Sp.x16, vertical = Sp.x4))
                }
                val r = result
                when {
                    aggregate && route.viewId == null -> LazyColumn(Modifier.fillMaxSize().testTag("search.results"), state = aggregateScroll, contentPadding = pad) {
                        if (aggRun == 0) item("hint") {
                            EmptyState("在所有来源里搜", "输入关键词后按键盘搜索键，每个来源各占一行。", LpIcons.search)
                        }
                        items(aggRows.size) { i ->
                            val g = aggRows[i]
                            val name = g.str("server_name") ?: g.str("server_id") ?: ""
                            val sid = g.str("server_id") ?: ""
                            when {
                                g.str("error") != null -> Dim3("$name 没搜成:${g.str("error")}", Modifier.padding(Sp.x16), maxLines = 2)
                                g.str("kind") == "plugin" -> Column(Modifier.padding(vertical = Sp.x8)) {
                                    MediaRowHeader(name)
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Sp.x16), horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
                                        g["items"].arr().mapNotNull { it.obj() }.forEach { it ->
                                            val sourceId = it.str("source") ?: sid
                                            val onOpen = { nav.navigate(Route.SourceDetail(sourceId, it.str("id") ?: "")) }
                                            SourceCard(it, onOpen, Modifier.width(108.dp), menu = listOf(
                                                xyz.linplayer.app.ui.components.CardAction("查看详情", onClick = onOpen),
                                                xyz.linplayer.app.ui.components.CardAction("收藏") { scope.launch {
                                                    runCatching { app.call("source.setFavorite", args("server_id" to sourceId, "item" to it, "favorite" to true)) }
                                                        .onSuccess { app.toast("已加入收藏", xyz.linplayer.app.data.ToastKind.Ok) }
                                                        .onFailure { app.report(it) }
                                                } },
                                            ))
                                        }
                                    }
                                }
                                // 跨服写操作必须携带来源账号，不能落到当前活跃服务器。
                                else -> Column {
                                    LpRow(name, Item.list(g["emby_items"]), { app.imageUrl(it.id, "Primary", 330) },
                                        { nav.navigate(Route.Detail(it.id, it.type)) }, menu = { cardActions(app, scope, it, sid) })
                                }
                            }
                        }
                        aggFailure?.let { message -> item("error") { ErrorState(message, { searchAggregate() }) } }
                        if (!aggBusy && aggFailure == null && aggRun > 0 && aggRows.isEmpty()) item("none") { EmptyState("「${q.trim()}」没搜到东西", "只包括打开了「允许聚合」的来源。") }
                    }

                    r == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { if (history.isEmpty()) EmptyState(
                        "搜片名、剧名或演员", "会搜当前服务器；点击搜索框右侧叠层图标可切换聚合搜索。",
                        LpIcons.search,
                    ) else HistoryList(history) { q = it } }

                    r is Block.Loading -> LazyVerticalGrid(
                        GridCells.Fixed(3), contentPadding = PaddingValues(Sp.x16),
                        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                        verticalArrangement = Arrangement.spacedBy(Sp.x16),
                    ) {
                        items(9) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Skeleton(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                                Spacer(Modifier.height(Sp.x6))
                                Skeleton(Modifier.fillMaxWidth(.8f).height(14.dp))
                            }
                        }
                    }

                    r is Block.Fail -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ErrorState(r.message, { if (!refreshing) { refreshing = true; refresh++ } })
                    }

                    else -> {
                        val items = (r as Block.Ok).value
                        if (items.isEmpty()) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { EmptyState(
                            "「${q.trim()}」没搜到东西",
                            "检查一下有没有打错字,或者换个关键词 —— 有些片源用的是英文原名。",
                        ) } else {
                            // 分集**单独一栏横版**;剧和影走网格
                            val eps = items.filter { it.isEpisode }
                            val rest = items.filterNot { it.isEpisode }
                            LazyColumn(Modifier.fillMaxSize().testTag("search.results"), state = resultScroll, contentPadding = pad) {
                                if (items.size >= 50) item("limit") {
                                    Dim3("最多显示 50 条,请缩小关键词", Modifier.padding(Sp.x16))
                                }
                                // 每行独立虚拟化，不能把全部结果塞进一个普通Column。
                                itemsIndexed(rest.chunked(3), key = { index, row -> "poster-row:$index:${row.first().id}" },
                                    contentType = { _, _ -> "poster-row" }) { index, row ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = Sp.x16)
                                        .padding(bottom = if (index == (rest.size - 1) / 3) 0.dp else Sp.x16),
                                        horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
                                        row.forEach { picked ->
                                            MediaCard(picked, app.imageUrl(picked.id, "Primary", 330), {
                                                // 历史只在**用户真的点开了某个结果**时才记 ——
                                                // 跟着防抖记会把「阿」「阿凡」「阿凡达」全记进去
                                                val t = q.trim()
                                                if (t.isNotEmpty()) history = (listOf(t) + history).distinct().take(8)
                                                nav.navigate(Route.Detail(picked.id, picked.type))
                                            }, Modifier.weight(1f), menu = cardActions(app, scope, picked))
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                if (eps.isNotEmpty()) item("eps") {
                                    LpRow("分集", eps, { app.imageUrl(it.id, "Primary", 220) },
                                        { nav.navigate(Route.Detail(it.id, "Episode")) }, thumb = true,
                                        menu = { cardActions(app, scope, it) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private inline fun remember0(list: List<String>, set: (List<String>) -> Unit) = Unit

@Composable
private fun HistoryList(history: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        MediaRowHeader("最近搜过")
        Spacer(Modifier.height(Sp.x8))
        history.forEach {
            Text(it, Modifier.fillMaxWidth().pressable({ onPick(it) }).padding(horizontal = Sp.x16, vertical = Sp.x12),
                color = Lp.colors.fg2, fontSize = 14.sp)
        }
    }
}
