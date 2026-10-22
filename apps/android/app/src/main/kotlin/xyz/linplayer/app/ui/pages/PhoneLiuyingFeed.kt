package xyz.linplayer.app.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.player.*
import xyz.linplayer.app.ui.player.liuying.*
import xyz.linplayer.app.ui.theme.*
import java.util.UUID

private data class FeedRow(val key: String, val page: LiuYingPageWindow, val index: Int) {
    val row get() = page.rows.getOrNull(index)
}

private fun feedRows(pages: List<LiuYingPageWindow>) = pages.flatMap { page ->
    if (page.rows.isEmpty()) listOf(FeedRow("${page.seed}:${page.offset}:empty", page, -1))
    else page.rows.indices.map { FeedRow("${page.seed}:${page.offset}:$it", page, it) }
}

/** 手机专用宿主；TV仍沿用原入口，不创建普通播放器或后台播放服务。 */
@Composable
internal fun PhoneLiuyingFeed(onExit: () -> Unit, session: LiuyingPlaybackSession = rememberLiuyingSession()) {
    val app = LocalApp.current
    val identity by app.session.collectAsState()
    val privateSpace by app.privateSpace.collectAsState()
    val space by app.spaceRevision.collectAsState()
    var capability by remember(identity, space) { mutableStateOf<Boolean?>(null) }
    var capabilityError by remember(identity, space) { mutableStateOf<String?>(null) }
    var labels by remember(identity, space) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var feed by remember(identity, space) { mutableStateOf("normal") }
    var tab by remember(identity, space) { mutableStateOf("dance") }
    var channel by remember(identity, space) { mutableStateOf("") }
    var exposure by remember(identity, space) { mutableStateOf("") }
    var favorites by remember(identity, space) { mutableStateOf(false) }
    var generation by remember(identity, space) { mutableIntStateOf(1) }
    var filtersOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var autoAdvance by remember { mutableStateOf(true) }
    var adopted by remember(identity, space) { mutableStateOf<Pair<LiuYingPageWindow, Int>?>(null) }
    val leave = { session.close(); onExit() }
    BackHandler(onBack = leave)
    LaunchedEffect(identity, space, privateSpace) {
        if (!privateSpace) { leave(); return@LaunchedEffect }
        try {
            capability = app.call("emby.extCapabilities").obj()?.get("liuying").obj().bool("enabled")
            if (capability == true) labels = app.call("emby.extLiuYingLabels").arr().mapNotNull { it.obj() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { capabilityError = failure.message ?: "读取服务器能力失败" }
    }
    fun reset() { adopted = null; generation++ }
    fun bankai() {
        feed = if (feed == "normal") "adult" else "normal"
        tab = if (feed == "normal") "dance" else ""
        channel = ""; exposure = ""; favoritesOpen = false; reset()
    }
    fun selectLabel(kind: String, value: String) {
        if (kind == "channel") { channel = if (channel == value) "" else value; exposure = "" }
        else exposure = if (exposure == value) "" else value
        reset()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("liuying.native")) {
        if (capability == true && privateSpace) key(identity, space, generation) {
            PhoneLiuyingPager(session, generation, feed, tab, channel, exposure, favorites, adopted,
                labels, autoAdvance, filtersOpen || favoritesOpen,
                leave, ::bankai, { value -> if (value != tab) { tab = value; channel = ""; exposure = ""; reset() } },
                { filtersOpen = true }, { autoAdvance = !autoAdvance }, { favoritesOpen = true },
                filtersOpen, { filtersOpen = false }, ::selectLabel,
                { favorites = !favorites; reset() }, { channel = ""; exposure = ""; favorites = false; reset() })
        } else {
            Box(Modifier.fillMaxSize().padding(Sp.x26), contentAlignment = Alignment.Center) {
                when {
                    capabilityError != null -> EmptyState("流影暂不可用", capabilityError!!, LpIcons.globe)
                    capability == false -> EmptyState("服务器未提供流影能力", "请刷新普通首页更新能力，或检查账号权限", LpIcons.globe)
                    else -> LoadingLabel("正在加载…")
                }
            }
            PhoneLiuyingChrome(feed, tab, false, false, null, false, autoAdvance, null, leave, ::bankai,
                {}, {}, {}, {}, {})
        }
        if (favoritesOpen) PhoneLiuyingFavorites(feed, tab, { favoritesOpen = false }, { selectedTab ->
            tab = selectedTab; channel = ""; exposure = ""; favorites = false; favoritesOpen = false; reset()
        }) { selectedTab, page, index ->
            tab = selectedTab; channel = ""; exposure = ""; favorites = true
            adopted = page to index; generation++; favoritesOpen = false
        }
    }
}

/** 三页候选与三项媒体准备分别有界；边界请求失败保留原页和相同seed供重试。 */
@Composable
private fun PhoneLiuyingPager(
    session: LiuyingPlaybackSession, generation: Int, feed: String, tab: String, channel: String, exposure: String,
    favorites: Boolean, adopted: Pair<LiuYingPageWindow, Int>?, labels: List<JsonObject>, autoAdvance: Boolean,
    modal: Boolean, onExit: () -> Unit, onBankai: () -> Unit, onTab: (String) -> Unit,
    onFilter: () -> Unit, onMode: () -> Unit, onFavorites: () -> Unit, filtersOpen: Boolean, closeFilters: () -> Unit,
    onLabel: (String, String) -> Unit, toggleFavorites: () -> Unit, clearFilters: () -> Unit,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val initialSeed = remember { adopted?.first?.seed ?: UUID.randomUUID().toString() }
    var pages by remember { mutableStateOf(adopted?.let { listOf(it.first) } ?: emptyList()) }
    var loading by remember { mutableStateOf(pages.isEmpty()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var favoriteBusy by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Triple<String, Int, Boolean>?>(if (pages.isEmpty()) Triple(initialSeed, 0, false) else null) }
    var failedRequest by remember { mutableStateOf<Triple<String, Int, Boolean>?>(null) }
    var endSeed by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var removedFavorites by remember { mutableStateOf(setOf<String>()) }
    var retainedFavorite by remember { mutableStateOf<String?>(null) }
    val rows = feedRows(pages).filter {
        it.row.str("item_id") !in removedFavorites || it.key == retainedFavorite
    }
    val pager = rememberPagerState(initialPage = adopted?.second ?: 0, pageCount = {
        maxOf(1, rows.size + if (rows.isNotEmpty() && pages.lastOrNull()?.rows?.isNotEmpty() == true) 1 else 0)
    })
    val current = rows.getOrNull(pager.settledPage)
    val currentRow = current?.row
    val latestAuto by rememberUpdatedState(autoAdvance)
    val latestCurrent by rememberUpdatedState(current)
    var pendingMove by remember { mutableStateOf<Int?>(null) }
    fun move(delta: Int) {
        val target = pager.settledPage + delta
        if (target in rows.indices) scope.launch { pager.animateScrollToPage(target) }
        else if (delta > 0 && !loading) {
            val last = pages.lastOrNull() ?: return
            if (last.rows.isEmpty()) return
            pendingMove = 1
            request = Triple(if (last.more) last.seed else endSeed, if (last.more) last.offset + last.rows.size else 0, false)
        } else if (delta < 0 && !loading && pages.firstOrNull()?.offset?.let { it > 0 } == true) {
            val first = pages.first()
            pendingMove = -1
            request = Triple(first.seed, (first.offset - 12).coerceAtLeast(0), true)
        }
    }
    SideEffect { session.onEnded = { if (latestAuto) move(1) else session.loop() } }
    LaunchedEffect(current?.key, rows.map { it.key }, pager.isScrollInProgress) {
        if (pager.isScrollInProgress) {
            session.drag(true)
            return@LaunchedEffect
        }
        val index = rows.indexOfFirst { it.key == current?.key }
        fun selection(row: FeedRow) = row.row.str("item_id")?.takeIf { it.isNotEmpty() }?.let {
            LiuyingSelection(row.key, it, generation)
        }
        session.select(current?.let(::selection),
            if (index < 0) emptyList() else rows.drop(index + 1).mapNotNull(::selection).take(3))
        // 先登记最新落定目标，再撤掉拖动门，旧声音不能在两者之间恢复。
        session.drag(false)
        if (retainedFavorite != null && retainedFavorite != current?.key) {
            retainedFavorite = null
            val updated = feedRows(pages).filter { it.row.str("item_id") !in removedFavorites }
            updated.indexOfFirst { it.key == current?.key }.takeIf { it >= 0 }?.let { pager.requestScrollToPage(it) }
            return@LaunchedEffect
        }
        if (loading || failure != null || request != null) return@LaunchedEffect
        if (current == null && rows.isNotEmpty()) {
            val last = pages.last()
            if (last.rows.isNotEmpty()) request =
                Triple(if (last.more) last.seed else endSeed, if (last.more) last.offset + last.rows.size else 0, false)
            return@LaunchedEffect
        }
        if (currentRow == null) return@LaunchedEffect
        if (rows.size - index <= 4 && current.page == pages.lastOrNull()) {
            val last = pages.last()
            if (last.more) request = Triple(last.seed, last.offset + last.rows.size, false)
        } else if (index < 3 && pages.firstOrNull()?.offset?.let { it > 0 } == true && current.page == pages.first()) {
            val first = pages.first()
            request = Triple(first.seed, (first.offset - 12).coerceAtLeast(0), true)
        }
    }
    LaunchedEffect(request, retry) {
        val target = request ?: return@LaunchedEffect
        loading = true; failure = null
        val (seed, offset, previous) = target
        try {
            val result = app.call("emby.extLiuYingItems", args("feed" to feed, "tab" to tab,
                "channel" to channel, "exposure" to exposure, "favorite" to favorites, "seed" to seed, "offset" to offset)).obj()
            coroutineContext.ensureActive()
            val items = result?.get("items").arr().mapNotNull { it.obj() }.take(12)
            val page = LiuYingPageWindow(seed, offset, items, items.isNotEmpty() && result.bool("has_more"))
            // 响应期间仍可连续滑动；只在落定后按此刻的稳定身份接页、修正索引。
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            val anchor = latestCurrent?.key
            val combined = if (previous) listOf(page) + pages else pages + page
            // 边界占位页没有条目key，接入时以新页为锚，避免三页窗口裁掉刚取得的结果。
            val anchorPage = if (anchor == null && !previous) combined.lastIndex
                else combined.indexOfFirst { p -> feedRows(listOf(p)).any { it.key == anchor } }
            val keepFrom = (anchorPage - 1).coerceAtLeast(0).coerceAtMost((combined.size - 3).coerceAtLeast(0))
            pages = combined.drop(keepFrom).take(3)
            val newRows = feedRows(pages).filter { it.row.str("item_id") !in removedFavorites || it.key == retainedFavorite }
            val newIndex = when (pendingMove) {
                1 -> newRows.indexOfFirst { it.page == page }
                -1 -> newRows.indexOfLast { it.page == page }
                else -> if (anchor == null && rows.isNotEmpty()) newRows.indexOfFirst { it.page == page }
                    else newRows.indexOfFirst { it.key == anchor }
            }
            if (newIndex >= 0 && (anchor == null || pendingMove != null || newIndex != pager.settledPage))
                pager.requestScrollToPage(newIndex)
            pendingMove = null
            if (seed == endSeed) endSeed = UUID.randomUUID().toString()
            failedRequest = null; request = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            coroutineContext.ensureActive()
            failure = error.message ?: "读取流影失败"
            failedRequest = target; request = null
        } finally { loading = false }
    }
    fun favorite() {
        val row = currentRow ?: return
        if (favoriteBusy) return
        val id = row.str("item_id") ?: return
        val wanted = !row.bool("favorite")
        favoriteBusy = true
        scope.launch {
            try {
                app.call("emby.setFavorite", args("item_id" to id, "fav" to wanted))
                coroutineContext.ensureActive()
                val anchor = latestCurrent?.key
                pages = pages.map { p -> p.copy(rows = p.rows.map {
                    if (it.str("item_id") == id) JsonObject(it + args("favorite" to wanted)) else it
                }) }
                if (favorites && !wanted) {
                    retainedFavorite = latestCurrent?.takeIf { it.row.str("item_id") == id }?.key
                    removedFavorites = removedFavorites + id
                    val updated = feedRows(pages).filter {
                        it.row.str("item_id") !in removedFavorites || it.key == retainedFavorite
                    }
                    updated.indexOfFirst { it.key == anchor }.takeIf { it >= 0 }?.let { pager.requestScrollToPage(it) }
                } else removedFavorites = removedFavorites - id
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { app.report(error) }
            finally { favoriteBusy = false }
        }
    }
    val snap = lpTween<Float>(T.T4)
    Box(Modifier.fillMaxSize()) {
        VerticalPager(pager, Modifier.fillMaxSize().testTag("liuying.gesture"), beyondViewportPageCount = 1,
            userScrollEnabled = !modal, key = { index -> rows.getOrNull(index)?.key ?: if (rows.isEmpty()) "initial" else "loading" },
            flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1),
                snapAnimationSpec = snap, snapPositionalThreshold = .2f)) { index ->
            val entry = rows.getOrNull(index)
            val row = entry?.row
            val slot = entry?.let { session.slotFor(it.key) }
            Box(Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(entry?.key) { detectTapGestures {
                    if (row != null && entry.key == latestCurrent?.key) session.togglePause()
                } }
                .pointerInput(entry?.key) {
                    var dx = 0f
                    var start = 0.0
                    detectHorizontalDragGestures(onDragStart = { dx = 0f; start = session.position },
                        onDragEnd = {
                            if (row != null && entry.key == latestCurrent?.key)
                                session.seek(start + dx / size.width * session.duration * .6)
                        }) { change, delta ->
                        if (row != null && entry.key == latestCurrent?.key) { change.consume(); dx += delta }
                    }
                }) {
                if (slot != null) key(slot) { LiuyingSlotPicture(slot,
                    session.slots.active === slot && session.foreground && session.unlocked,
                    session.slots.onSurfaceChanged, Modifier.fillMaxSize()) }
                if (slot?.frameReady != true) {
                    row?.str("item_id")?.let { id -> AsyncImage(app.imageUrl(id, height = 1080), null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                if (index == pager.settledPage) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            session.error != null -> ErrorState(session.error!!, session::retry)
                            failure != null && row == null -> ErrorState(failure!!, { request = failedRequest; retry++ })
                            row != null && slot?.frameReady != true || row == null && loading -> LoadingLabel("正在加载…")
                            row == null -> EmptyState("暂无可播放的入库资源", "可以切换来源、分类或标签；资料中心内容不会直接播放", LpIcons.globe)
                        }
                    }
                val selectedPage = index == pager.settledPage
                val controls = session.controls().let {
                    if (selectedPage) it else it.copy(position = 0.0, duration = 0.0, paused = false, ready = false)
                }
                // 相邻页保留自己的内容与画面，但不暴露离屏操作给无障碍服务。
                Box(if (selectedPage) Modifier else Modifier.clearAndSetSemantics {}) {
                    PhoneLiuyingChrome(feed, tab, feed == "normal" && tab == "dance", selectedPage && !modal,
                        row, favoriteBusy, autoAdvance, controls, onExit, onBankai, onTab,
                        ::favorite, onFilter, onMode, onFavorites, showNavigation = false)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(180.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent))))
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp)) {
            PhoneLiuyingTopBar(feed, tab, !modal, onExit, onBankai, onTab, onFavorites)
        }
        if (failure != null && currentRow != null) Text("下一页加载失败 · 点击重试",
            Modifier.align(Alignment.Center).testTag("liuying.pageRetry").pressable({ request = failedRequest; retry++ }),
            color = Color.White, style = LpText.caption)
        if (filtersOpen) PhoneLiuyingFilters(labels, feed == "normal" && tab == "dance", channel, exposure, favorites,
            pager.settledPage > 0 || pages.firstOrNull()?.offset?.let { it > 0 } == true, currentRow != null,
            { move(-1) }, { move(1) }, onLabel, toggleFavorites, clearFilters, closeFilters)
    }
}
