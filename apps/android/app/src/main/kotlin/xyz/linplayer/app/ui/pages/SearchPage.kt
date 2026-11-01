package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.tv.tvFocusRow
import xyz.linplayer.app.tv.LocalTvPageBack

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.minimumInteractiveComponentSize
import xyz.linplayer.app.ui.components.LpPullRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.displayBlock
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
import xyz.linplayer.app.ui.components.gridCellWidth
import xyz.linplayer.app.ui.components.posterImageHeight
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.MediaFilterChip
import xyz.linplayer.app.ui.components.ToneChip
import xyz.linplayer.app.ui.components.BlockBox
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
    val type = mutableStateOf("Movie")
    val aggRows = androidx.compose.runtime.mutableStateListOf<kotlinx.serialization.json.JsonObject>()
    val aggRun = mutableStateOf(0)
    val aggQuery = mutableStateOf("")
    val aggFailure = mutableStateOf<String?>(null)
    var completedQuery: Triple<String, String, Int>? = null
    var completedRun = 0
}

/** 搜索电影、剧集和人物；输入框内切换聚合，开启/键盘搜索/下拉刷新发起聚合查询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(nav: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<Route.Search>()
    val currentEntry by nav.currentBackStackEntryAsState()
    val pageActive = currentEntry?.id == entry.id
    val app = LocalApp.current
    val scope = rememberCoroutineScope()

    val session = app.session.collectAsState().value
    val state: SearchPageState = viewModel(viewModelStoreOwner = entry,
        key = "search:${session?.server}:${session?.userId}")
    if (!state.initialized) {
        state.q.value = route.q.orEmpty()
        state.initialized = true
    }
    var q by state.q
    var aggregate by state.aggregate
    var refresh by state.refresh
    var result by state.result
    var searchType by state.type
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val savedHistory by xyz.linplayer.app.data.UiPrefs.searchHistory
    val history = if (app.privateSpace.value) emptyList() else savedHistory
    val aggRows = state.aggRows
    var aggRun by state.aggRun
    var aggQuery by state.aggQuery
    var aggFailure by state.aggFailure
    var refreshing by remember(state) { mutableStateOf(false) }
    var aggBusy by remember(state) { mutableStateOf(false) }
    val resultScroll = rememberLazyListState()
    val aggregateScroll = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    val resultFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val inputFocus = remember { FocusRequester() }
    val aggregateFocus = remember { FocusRequester() }
    var resultsFocused by remember { mutableStateOf(false) }
    val pageBack = LocalTvPageBack.current
    LaunchedEffect(tv, entry.id) {
        if (tv) { withFrameNanos { }; inputFocus.requestFocus() }
    }
    DisposableEffect(tv, pageActive, inputFocus, resultsFocused) {
        val callback: () -> Boolean = { if (resultsFocused) { inputFocus.requestFocus(); true } else false }
        if (tv && pageActive) pageBack.value = callback
        onDispose { if (pageBack.value === callback) pageBack.value = null }
    }

    // 关键词/模式变化取消旧查询；刷新保留当前关键词与库内范围。
    LaunchedEffect(state, q.trim(), searchType, aggregate, refresh) {
        val text = q.trim()
        if (text.isEmpty() || (aggregate && route.viewId == null)) {
            result = null
            if (text.isEmpty()) refreshing = false
            if (text != aggQuery) { aggRun = 0; state.completedRun = 0; aggRows.clear(); aggFailure = null }
            return@LaunchedEffect
        }
        // 返回同一查询也先展示旧值，再请求实时结果。
        try {
            delay(250)
            result = Block.Loading
            val a = buildMap<String, Any> {
                put("query", text)
                put("types", jsonArrayOf(listOf(searchType)))
                route.viewId?.let { put("parent_id", it) }
            }
            val response = app.displayBlock("emby.search", args(*a.toList().toTypedArray())) { result = Block.Ok(Page.from(it).items) }
            currentCoroutineContext().ensureActive()
            result = when (val r = response) {
                is Block.Ok -> Block.Ok(Page.from(r.value).items)
                is Block.Fail -> r
                else -> Block.Loading
            }
            state.completedQuery = Triple(text, searchType, refresh)
        } finally { refreshing = false }
    }

    fun searchAggregate() {
        if (q.isBlank()) return
        aggQuery = q.trim(); aggRun++
    }
    LaunchedEffect(app, state) {
        app.invalidate.collect {
            if (it == "library" || it == "all") {
                if (aggregate && route.viewId == null) searchAggregate() else refresh++
            }
        }
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
                        if (run == aggRun && aggregate && text == q.trim()) {
                            val index = aggRows.indexOfFirst { it.str("server_id") == row.str("server_id") }
                            if (row.str("kind") == "emby" && row.str("error") == null && row["emby_items"].arr().isEmpty()) {
                                if (index >= 0) aggRows.removeAt(index)
                            } else if (index >= 0) {
                                if (row.str("error") == null) aggRows[index] = row else app.toast(row.str("error").orEmpty())
                            } else aggRows.add(row)
                        }
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

    LpImmersive(barHeight = 0.dp, tvRoot = true) { pad ->
        LpPullRefresh(refreshing, {
            if (q.isNotBlank() && !refreshing) {
                refreshing = true
                if (aggregate && route.viewId == null) searchAggregate() else refresh++
            }
        }, Modifier.fillMaxSize().statusBarsPadding().padding(top = Sp.x12)) {
            val controls: @Composable () -> Unit = {
                LpField(q, { q = it }, if (route.viewId != null) "在这个库里搜" else "搜片名、剧名或演员",
                    Modifier.padding(horizontal = Sp.x16).testTag("search.field"),
                    trailingIcon = if (route.viewId == null && !app.privateSpace.value) ({
                        LpIconButton(
                            LpIcons.layers, if (aggregate) "关闭聚合搜索" else "开启聚合搜索",
                            Modifier.testTag("search.aggregate").focusRequester(aggregateFocus)
                                .focusProperties {
                                    left = if (tv) inputFocus else FocusRequester.Default
                                    right = if (tv) resultFocus else FocusRequester.Default
                                }
                                .semantics { selected = aggregate },
                            tint = if (aggregate) Lp.colors.mediaAccent else Lp.colors.fg2,
                        ) {
                            aggregate = !aggregate; refreshing = false
                            if (aggregate) searchAggregate()
                        }
                    }) else null,
                    onSearch = {
                        keyboard?.hide()
                        if (!app.privateSpace.value) xyz.linplayer.app.data.UiPrefs.recordSearch(ctx, q)
                        if (aggregate && route.viewId == null) searchAggregate() else refresh++
                    },
                    focusRequester = if (tv) inputFocus else null,
                    focusProperties = {
                        right = if (!tv) FocusRequester.Default else if (route.viewId == null && !app.privateSpace.value) aggregateFocus else resultFocus
                    },
                    focusRight = if (tv) {
                        if (route.viewId == null && !app.privateSpace.value) aggregateFocus else resultFocus
                    } else null,
                )
                Spacer(Modifier.height(Sp.x12))
                if (!aggregate || route.viewId != null) Row(
                    Modifier.fillMaxWidth().padding(horizontal = Sp.x16).searchControlRow(tv, resultFocus),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x8),
                ) {
                    listOf("电影" to "Movie", "剧集" to "Series", "人物" to "Person").forEach { (label, type) ->
                        androidx.compose.foundation.layout.Box(Modifier.testTag("search.type.$type")) {
                            if (tv) ToneChip(label, searchType == type,
                                Modifier.minimumInteractiveComponentSize().onFocusChanged { if (it.isFocused) searchType = type }) {
                                    searchType = type
                                }
                            else MediaFilterChip(label, searchType == type) { searchType = type }
                        }
                    }
                }
                if ((tv || q.isBlank()) && history.isNotEmpty()) HistoryList(history, if (tv) resultFocus else null) { q = it }
                Spacer(Modifier.height(Sp.x8))

                if (aggregate && route.viewId == null && aggBusy) {
                    Dim3("还有来源在搜…", Modifier.padding(horizontal = Sp.x16, vertical = Sp.x4))
                }
            }
            val results: @Composable (Modifier) -> Unit = { modifier ->
              BoxWithConstraints(modifier) {
                val resultWidth = maxWidth
                val resultPad = if (tv) PaddingValues(top = Sp.x26, bottom = pad.calculateBottomPadding()) else pad
                val r = result
                when {
                    aggregate && route.viewId == null -> LazyColumn(Modifier.fillMaxSize().testTag("search.results"), state = aggregateScroll, contentPadding = resultPad) {
                        if (aggRun == 0) item("hint") {
                            EmptyState("在所有来源里搜", "输入关键词后按键盘搜索键，每个来源各占一行。", LpIcons.search)
                        }
                        if (aggBusy && aggRows.isEmpty()) item("loading") {
                            xyz.linplayer.app.ui.components.LoadingState(Modifier.fillParentMaxSize())
                        }
                        // 来源结果分批到达并原位替换：按来源ID保持行身份，两种行形态分开复用
                        items(aggRows.size, key = { aggRows[it].str("server_id") ?: "#$it" },
                            contentType = { aggRows[it].let { g -> if (g.str("error") != null) "error" else g.str("kind") ?: "emby" } }) { i ->
                            val g = aggRows[i]
                            val name = g.str("server_name") ?: g.str("server_id") ?: ""
                            val sid = g.str("server_id") ?: ""
                            when {
                                g.str("error") != null -> Dim3("$name 没搜成:${g.str("error")}", Modifier.padding(Sp.x16), maxLines = 2)
                                // 跨服写操作必须携带来源账号，不能落到当前活跃服务器。
                                else -> Column {
                                    LpRow(name, remember(g) { Item.list(g["emby_items"]) }, { it, h -> app.imageUrl(it.id, "Primary", h) },
                                        { nav.navigate(Route.Detail(it.id, it.type)) }, menu = { rememberCardActions(app, scope, it, sid) },
                                        tvExitLeft = if (tv) controlsFocus else null)
                                }
                            }
                        }
                        aggFailure?.let { message -> item("error") { ErrorState(message, { searchAggregate() }) } }
                        if (!aggBusy && aggFailure == null && aggRun > 0 && aggRows.isEmpty()) item("none") { EmptyState("「${q.trim()}」没搜到东西", "只包括打开了「允许聚合」的来源。") }
                    }

                    r == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { if (tv || history.isEmpty()) EmptyState(
                        "搜片名、剧名或演员", "会搜当前服务器；点击搜索框右侧叠层图标可切换聚合搜索。",
                        LpIcons.search,
                    ) }

                    r is Block.Loading -> xyz.linplayer.app.ui.components.LoadingState()

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
                            val (eps, rest) = remember(items) { items.partition { it.isEpisode } }
                            // 列数与媒体库一致：手机 3 列，横屏 / 平板按屏宽加列
                            val columns = if (tv) xyz.linplayer.app.ui.components.posterLayout(
                                resultWidth.value, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat(), tv = true)!!.first
                            else posterColumns()
                            val rows = remember(rest, columns) { rest.chunked(columns) }
                            val imageHeight = posterImageHeight(width = if (tv)
                                (resultWidth - Sp.x16 * 2 - Sp.x10 * (columns - 1)) / columns else gridCellWidth(columns))
                            LazyColumn(Modifier.fillMaxSize().testTag("search.results"), state = resultScroll, contentPadding = resultPad) {
                                if (items.size >= 50) item("limit") {
                                    Dim3("最多显示 50 条,请缩小关键词", Modifier.padding(Sp.x16))
                                }
                                // 每行独立虚拟化，不能把全部结果塞进一个普通Column。
                                itemsIndexed(rows, key = { index, row -> "poster-row:$index:${row.first().id}" },
                                    contentType = { _, _ -> "poster-row" }) { index, row ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = Sp.x16)
                                        .padding(bottom = if (index == (rest.size - 1) / columns) 0.dp else Sp.x16),
                                        horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
                                        row.forEach { picked ->
                                            MediaCard(picked, app.imageUrl(picked.id, "Primary", imageHeight), {
                                                // 历史只在**用户真的点开了某个结果**时才记 ——
                                                // 跟着防抖记会把「阿」「阿凡」「阿凡达」全记进去
                                                val t = q.trim()
                                                if (!app.privateSpace.value) xyz.linplayer.app.data.UiPrefs.recordSearch(ctx, t)
                                                if (picked.type == "Person") nav.navigate(Route.Person(picked.id, picked.name))
                                                else nav.navigate(Route.Detail(picked.id, picked.type))
                                            }, Modifier.weight(1f), menu = if (picked.type == "Person") null else rememberCardActions(app, scope, picked))
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                if (eps.isNotEmpty()) item("eps") {
                                    LpRow("分集", eps, { it, h -> app.imageUrl(it.id, "Primary", h) },
                                        { nav.navigate(Route.Detail(it.id, "Episode")) }, thumb = true,
                                        menu = { rememberCardActions(app, scope, it) }, tvExitLeft = if (tv) controlsFocus else null)
                                }
                            }
                        }
                    }
                }
              }
            }
            if (tv) Row(Modifier.fillMaxSize().imePadding()) {
                Column(Modifier.width(280.dp).testTag("search.controls").focusRequester(controlsFocus).focusRestorer().focusGroup()
                    .verticalScroll(rememberScrollState()).padding(bottom = Sp.x20)) { controls() }
                results(Modifier.weight(1f).fillMaxSize().focusRequester(resultFocus).focusGroup()
                    .onFocusChanged { resultsFocused = it.hasFocus })
            } else Column(Modifier.fillMaxSize().imePadding()) {
                controls()
                results(Modifier.fillMaxSize())
            }
        }
    }
}

private inline fun remember0(list: List<String>, set: (List<String>) -> Unit) = Unit

@Composable
private fun HistoryList(history: List<String>, resultFocus: FocusRequester? = null, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        MediaRowHeader("最近搜过")
        Spacer(Modifier.height(Sp.x8))
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Sp.x16)
                .then(if (resultFocus != null) Modifier.searchControlRow(true, resultFocus) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(Sp.x8),
            verticalArrangement = Arrangement.spacedBy(Sp.x4),
        ) {
            history.take(10).forEach { word -> MediaFilterChip(word, false, { onPick(word) }) }
        }
    }
}

/** 左侧操作横排走到右边缘后进入结果，避免几何搜索跳到另一排历史词。 */
private fun Modifier.searchControlRow(tv: Boolean, results: FocusRequester): Modifier = if (!tv) this else
    focusProperties {
        onExit = { if (requestedFocusDirection == FocusDirection.Right) results.requestFocus() }
    }.focusGroup()
