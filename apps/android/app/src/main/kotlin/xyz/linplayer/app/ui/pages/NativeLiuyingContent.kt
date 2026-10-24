package xyz.linplayer.app.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.player.FeedPlayback
import xyz.linplayer.app.ui.theme.*
import xyz.linplayer.app.tv.LocalNav
import xyz.linplayer.app.tv.TvNav
import xyz.linplayer.app.tv.TvPlayerPage
import xyz.linplayer.app.tv.TvRoute
import kotlin.math.abs

internal data class LiuYingPageWindow(val seed: String, val offset: Int, val rows: List<JsonObject>, val more: Boolean,
    val nextOffset: Int = offset + rows.size)

/** 候选由服务端限定入库资源；本页只持有当前有界窗口，不保存播放地址或断点。 */
@Composable
internal fun NativeLiuyingContent(onExit: () -> Unit, tv: Boolean) {
    if (!tv) {
        PhoneLiuyingFeed(onExit)
        return
    }
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    val privateSpace by app.privateSpace.collectAsState()
    val nav = rememberNavController()
    val tvNav = remember { TvNav() }
    var capability by remember(session, privateSpace) { mutableStateOf<Boolean?>(null) }
    var capabilityError by remember(session, privateSpace) { mutableStateOf<String?>(null) }
    var feed by remember(session, privateSpace) { mutableStateOf("normal") }
    var tab by remember(session, privateSpace) { mutableStateOf("dance") }
    var channel by remember(session, privateSpace) { mutableStateOf("") }
    var exposure by remember(session, privateSpace) { mutableStateOf("") }
    var favorites by remember(session, privateSpace) { mutableStateOf(false) }
    var labels by remember(session, privateSpace) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var rows by remember(session, privateSpace) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var index by remember(session, privateSpace) { mutableIntStateOf(0) }
    var offset by remember(session, privateSpace) { mutableIntStateOf(0) }
    var more by remember(session, privateSpace) { mutableStateOf(false) }
    var seed by remember(session, privateSpace) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var revision by remember(session, privateSpace) { mutableIntStateOf(0) }
    var loading by remember(session, privateSpace) { mutableStateOf(false) }
    var failure by remember(session, privateSpace) { mutableStateOf<String?>(null) }
    var favoriteBusy by remember(session, privateSpace) { mutableStateOf(false) }
    var previousWindow by remember(session, privateSpace) { mutableStateOf<LiuYingPageWindow?>(null) }
    var requestedSeed by remember(session, privateSpace) { mutableStateOf(seed) }
    var requestedOffset by remember(session, privateSpace) { mutableIntStateOf(0) }
    var requestLast by remember(session, privateSpace) { mutableStateOf(false) }
    var playbackRevision by remember(session, privateSpace) { mutableIntStateOf(0) }
    var autoAdvance by remember(session, privateSpace) { mutableStateOf(true) }
    var feedVolume by remember(session, privateSpace) { mutableStateOf<Float?>(null) }
    var expanded by remember { mutableStateOf(true) }
    var filtersOpen by remember(session, privateSpace) { mutableStateOf(false) }
    var favoritesOpen by remember(session, privateSpace, feed) { mutableStateOf(false) }
    var selectedPage by remember(session, privateSpace) { mutableStateOf<Pair<LiuYingPageWindow, Int>?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val videoOffset by animateFloatAsState(dragOffset, lpTween(T.T1), label = "liuyingSwipe")
    val actionScroll = rememberLazyListState()
    val nextFocus = remember { FocusRequester() }
    val current = rows.getOrNull(index)
    val ticket = playbackRevision
    val latestSession by rememberUpdatedState(session)
    val latestPrivateSpace by rememberUpdatedState(privateSpace)
    LaunchedEffect(tv, ticket, loading, favoritesOpen) {
        if (tv && current != null && !loading && expanded && !favoritesOpen) {
            withFrameNanos { }
            runCatching { nextFocus.requestFocus() }
        }
    }

    BackHandler { onExit() }
    LaunchedEffect(session, privateSpace) {
        if (!privateSpace) { onExit(); return@LaunchedEffect }
        try {
            capability = app.call("emby.extCapabilities").obj()?.get("liuying").obj().bool("enabled")
            if (capability == true) labels = app.call("emby.extLiuYingLabels").arr().mapNotNull { it.obj() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { capabilityError = error.message ?: "读取服务器能力失败" }
    }
    fun reset() {
        rows = emptyList(); index = 0; offset = 0; more = false; failure = null
        previousWindow = null; selectedPage = null; playbackRevision++; loading = true
        seed = java.util.UUID.randomUUID().toString()
        requestedSeed = seed; requestedOffset = 0; requestLast = false; revision++
    }
    fun bankai() {
        feed = if (feed == "normal") "adult" else "normal"; tab = if (feed == "normal") "dance" else ""
        channel = ""; exposure = ""; reset()
    }
    fun selectLabel(kind: String, name: String) {
        if (kind == "channel") { channel = if (channel == name) "" else name; exposure = "" }
        else exposure = if (exposure == name) "" else name
        reset()
    }
    fun move(delta: Int, expected: Int = ticket) {
        if (loading || failure != null || expected != playbackRevision) return
        if (current == null && !(delta < 0 && previousWindow != null)) return
        if (delta < 0 && index == 0 && offset == 0 && previousWindow == null) return
        playbackRevision++
        dragOffset = 0f
        if (index + delta in rows.indices) { index += delta; return }
        val old = LiuYingPageWindow(seed, offset, rows, more)
        if (delta < 0 && previousWindow != null) {
            val previous = previousWindow!!
            seed = previous.seed; offset = previous.offset; rows = previous.rows; more = previous.more
            index = rows.lastIndex; previousWindow = null
            return
        }
        requestedSeed = if (delta > 0 && !more) java.util.UUID.randomUUID().toString() else seed
        requestedOffset = if (delta < 0) (offset - 12).coerceAtLeast(0) else if (more) offset + rows.size else 0
        requestLast = delta < 0
        previousWindow = if (delta > 0) old else null
        loading = true; revision++
    }
    val capabilityReady = capability == true
    val query = args("feed" to feed, "tab" to tab, "channel" to channel, "exposure" to exposure,
        "favorite" to favorites, "seed" to requestedSeed, "offset" to requestedOffset)
    val loadSeed = requestedSeed
    val loadOffset = requestedOffset
    val loadLast = requestLast
    val adoptedPage = selectedPage
    LaunchedEffect(session, privateSpace, capabilityReady, revision) {
        if (!privateSpace || !capabilityReady) return@LaunchedEffect
        loading = true; failure = null
        try {
            if (adoptedPage != null) {
                val (page, selectedIndex) = adoptedPage
                rows = page.rows; seed = page.seed; offset = page.offset; more = page.more; index = selectedIndex
                selectedPage = null
                return@LaunchedEffect
            }
            val result = app.call("emby.extLiuYingItems", query).obj()
            coroutineContext.ensureActive()
            rows = result?.get("items").arr().mapNotNull { it.obj() }
            seed = loadSeed; offset = loadOffset
            index = if (loadLast) rows.lastIndex.coerceAtLeast(0) else 0
            more = result.bool("has_more")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            coroutineContext.ensureActive()
            failure = error.message ?: "读取流影失败"
        }
        finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
    }
    fun favorite() {
        val item = current ?: return
        if (favoriteBusy) return
        val id = item.str("item_id") ?: return
        val want = !item.bool("favorite")
        favoriteBusy = true
        scope.launch {
            try {
                app.call("emby.setFavorite", args("item_id" to id, "fav" to want))
                coroutineContext.ensureActive()
                if (latestSession != session || latestPrivateSpace != privateSpace) return@launch
                rows = rows.map { if (it.str("item_id") == id) JsonObject(it + args("favorite" to want)) else it }
                previousWindow = previousWindow?.let { page -> page.copy(rows = page.rows.map {
                    if (it.str("item_id") == id) JsonObject(it + args("favorite" to want)) else it
                }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { app.report(error) }
            finally { favoriteBusy = false }
        }
    }
    @Composable
    fun chrome(playback: FeedPlayback? = null) {
        val latestPlayback by rememberUpdatedState(playback)
        val c = Lp.colors
        Box(Modifier.fillMaxSize()) {
            if (!tv && (current != null || previousWindow != null) && !loading && failure == null) Box(Modifier.fillMaxSize().testTag("liuying.gesture")
                .pointerInput(ticket) { detectTapGestures { latestPlayback?.takeIf { it.ready }?.togglePause?.invoke() } }
                .pointerInput(ticket) {
                    var x = 0f; var y = 0f; var axis = 0
                    var start = 0.0
                    val slop = 12.dp.toPx()
                    detectDragGestures(onDragStart = {
                        x = 0f; y = 0f; axis = 0; start = latestPlayback?.position ?: 0.0
                    }, onDragEnd = {
                        if (axis == 2 && abs(y) >= 64.dp.toPx()) move(if (y < 0) 1 else -1)
                        if (axis == 1) latestPlayback?.takeIf { it.ready && it.duration > 0 }?.let {
                            it.seek((start + x / size.width * it.duration * .6).coerceIn(0.0, it.duration))
                        }
                        dragOffset = 0f
                    }, onDragCancel = { dragOffset = 0f }) { change, delta ->
                        x += delta.x; y += delta.y
                        if (axis == 0) axis = when {
                            abs(y) > slop && abs(y) > abs(x) * 1.3f -> 2
                            abs(x) > slop && abs(x) > abs(y) * 1.3f -> 1
                            else -> 0
                        }
                        if (axis != 0) change.consume()
                        if (axis == 2) dragOffset = (y * .35f).coerceIn(-size.height * .25f, size.height * .25f)
                    }
                })
            if (!tv) {
                PhoneLiuyingChrome(feed, tab, feed == "normal" && tab == "dance" && capabilityReady, capabilityReady,
                    current.takeIf { !loading && failure == null }, favoriteBusy, autoAdvance, playback,
                    onExit, ::bankai, { key ->
                        if (tab != key) { tab = key; channel = ""; exposure = ""; reset() }
                    }, ::favorite, { filtersOpen = true }, { autoAdvance = !autoAdvance }, { favoritesOpen = true })
            } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Sp.x26)) {
                LazyRow(Modifier.fillMaxWidth().background(c.bg.copy(alpha = .85f)).testTag("liuying.controls"),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x4)) {
                    item { LpButton("返回", onExit, kind = BtnKind.Ghost) }
                    item { LpButton(if (feed == "normal") "卍解" else "卍解 · 开", ::bankai,
                        m = Modifier.testTag("liuying.bankai").semantics { selected = feed == "adult" },
                        kind = if (feed == "adult") BtnKind.Primary else BtnKind.Ghost, enabled = capabilityReady) }
                    if (feed == "normal") {
                        items(listOf("dance" to "舞蹈", "live" to "直播")) { (key, title) ->
                            LpButton(title, { if (tab != key) { tab = key; channel = ""; exposure = ""; reset() } },
                                kind = if (tab == key) BtnKind.Primary else BtnKind.Ghost, enabled = capabilityReady)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.align(Alignment.CenterEnd).width(if (tv) 220.dp else 144.dp)
                        .heightIn(max = if (tv) 500.dp else 480.dp).background(c.bg.copy(alpha = .85f))
                        .testTag("liuying.actions"), state = actionScroll, verticalArrangement = Arrangement.spacedBy(Sp.x2)) {
                        item { LpButton(if (expanded) "收起" else "展开", { expanded = !expanded }, kind = BtnKind.Ghost,
                            m = Modifier.fillMaxWidth().testTag("liuying.expand")) }
                        if (expanded) {
                            item { LpButton(if (autoAdvance) "自动下一条" else "循环当前", { autoAdvance = !autoAdvance },
                                kind = BtnKind.Secondary, m = Modifier.fillMaxWidth().testTag("liuying.mode")) }
                            item { LpButton("上一条", { move(-1) }, enabled = !loading && failure == null &&
                                (previousWindow != null || current != null && (index > 0 || offset > 0)),
                                m = Modifier.fillMaxWidth().testTag("liuying.previous")) }
                            item { LpButton("下一条", { move(1) }, enabled = current != null && !loading,
                                m = Modifier.fillMaxWidth().focusRequester(nextFocus).testTag("liuying.next")) }
                            item { LpButton(if (current.bool("favorite")) "取消收藏" else "收藏", ::favorite,
                                kind = BtnKind.Secondary, enabled = current != null && !favoriteBusy,
                                m = Modifier.fillMaxWidth().testTag("liuying.favorite")) }
                            item { LpButton(if (favorites) "只看收藏" else "全部作品", { favorites = !favorites; reset() },
                                kind = BtnKind.Ghost, enabled = capabilityReady, m = Modifier.fillMaxWidth()) }
                            item { LpButton("收藏列表", { favoritesOpen = true }, kind = BtnKind.Ghost,
                                enabled = capabilityReady, m = Modifier.fillMaxWidth().testTag("liuying.favorites")) }
                            if (feed == "normal" && tab == "dance" && capabilityReady) {
                                item { LpButton("全部标签", { channel = ""; exposure = ""; reset() }, kind = BtnKind.Ghost) }
                                for ((kind, title) in listOf("channel" to "频道", "exposure" to "衣着")) {
                                    item { Text(title, Modifier.padding(Sp.x8), color = c.fg2, style = LpText.caption) }
                                    items(labels.filter { it.str("kind") == kind }, key = { it.str("name").orEmpty() }) { label ->
                                        val name = label.str("name").orEmpty()
                                        val selected = channel == name || exposure == name
                                        Column(Modifier.fillMaxWidth()) {
                                            LpButton(label.str("title") ?: name, {
                                                selectLabel(kind, name)
                                            }, kind = if (selected) BtnKind.Primary else BtnKind.Ghost,
                                                m = Modifier.fillMaxWidth().testTag("liuying.label.$name").semantics { this.selected = selected })
                                            label.long("count")?.let { Text("$it 部", Modifier.padding(horizontal = Sp.x20),
                                                color = c.fg2, style = LpText.caption) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (current != null && !loading && failure == null) Column(Modifier.fillMaxWidth().background(c.bg.copy(alpha = .85f)).padding(Sp.x8)) {
                    Text(current.str("title").orEmpty(), color = c.fg, style = LpText.list, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current.str("author")?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.fg2, style = LpText.caption) }
                    if (playback != null) {
                        var preview by remember { mutableStateOf<Float?>(null) }
                        Slider(preview ?: playback.position.toFloat().coerceIn(0f, playback.duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = { preview = it }, onValueChangeFinished = { preview?.let { playback.seek(it.toDouble()) }; preview = null },
                            valueRange = 0f..playback.duration.toFloat().coerceAtLeast(1f), enabled = playback.ready && playback.duration > 0,
                            modifier = Modifier.fillMaxWidth().testTag("liuying.progress"))
                        Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8), verticalAlignment = Alignment.CenterVertically) {
                            LpButton(if (playback.paused) "播放" else "暂停", playback.togglePause, kind = BtnKind.Ghost, enabled = playback.ready)
                            LpButton(if (playback.muted) "开声音" else "静音", playback.toggleMute, kind = BtnKind.Ghost, enabled = playback.ready)
                            LpButton("${playback.speed}x", {
                                val speeds = listOf(.5, .75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0)
                                playback.setSpeed(speeds.firstOrNull { it > playback.speed } ?: speeds.first())
                            }, kind = BtnKind.Ghost, enabled = playback.ready && !playback.speedBusy,
                                m = Modifier.testTag("liuying.speed"))
                            Text("${playback.position.toInt()} / ${playback.duration.toInt()} 秒", color = c.fg2, style = LpText.caption)
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().testTag("liuying.native")) {
        if (capabilityReady && !loading && failure == null && current != null) {
            key(session, privateSpace, playbackRevision) {
                val id = current.str("item_id").orEmpty()
                val title = current.str("title").orEmpty()
                val complete = {
                    if (ticket == playbackRevision && !loading) {
                        if (autoAdvance) move(1, ticket) else playbackRevision++
                    }
                }
                if (tv) CompositionLocalProvider(LocalNav provides tvNav) {
                    TvPlayerPage(TvRoute.Player(id, title, fromStart = true), onExit = { onExit() },
                        onCompleted = complete, feedOverlay = { chrome(it) },
                        feedVolume = feedVolume, onFeedVolume = { feedVolume = it })
                } else PlayerPage(nav, null, onExit = { onExit() },
                    embeddedRoute = Route.Player(id, title, fromStart = true), onCompleted = complete,
                    feedOverlay = { chrome(it) }, feedOffset = videoOffset,
                    feedVolume = feedVolume, onFeedVolume = { feedVolume = it })
            }
        } else {
            Box(Modifier.fillMaxSize().padding(horizontal = Sp.x20, vertical = Sp.x48)
                .padding(end = if (tv && expanded) 238.dp else 0.dp)) {
            when {
                capabilityError != null -> EmptyState("流影暂不可用", capabilityError!!, LpIcons.globe)
                capability == false -> EmptyState("服务器未提供流影能力", "请刷新普通首页更新能力，或检查账号权限", LpIcons.globe)
                capability == null || loading -> LoadingLabel("正在加载…")
                failure != null -> ErrorState(failure!!, { revision++ })
                current == null -> EmptyState("暂无可播放的入库资源", "可以切换来源、分类或标签；资料中心内容不会直接播放", LpIcons.globe)
            }
            }
            chrome()
        }
        if (!tv && filtersOpen) PhoneLiuyingFilters(
            labels, feed == "normal" && tab == "dance" && capabilityReady, channel, exposure, favorites,
            !loading && failure == null && (previousWindow != null || current != null && (index > 0 || offset > 0)),
            current != null && !loading && failure == null,
            { move(-1) }, { move(1) }, ::selectLabel,
            { favorites = !favorites; reset() }, { channel = ""; exposure = ""; favorites = false; reset() },
            { filtersOpen = false })
        if (favoritesOpen) PhoneLiuyingFavorites(feed, tab, { favoritesOpen = false }) { selectedTab, page, selectedIndex ->
            tab = selectedTab; channel = ""; exposure = ""; favorites = false; autoAdvance = false
            // 收藏点播只接管选中一条；下一条回到新的随机轮次。
            val selected = LiuYingPageWindow(java.util.UUID.randomUUID().toString(), 0, listOf(page.rows[selectedIndex]), false)
            previousWindow = null; selectedPage = selected to 0
            requestedSeed = selected.seed; requestedOffset = 0; requestLast = false
            rows = emptyList(); failure = null; loading = true; playbackRevision++; revision++
            favoritesOpen = false
        }
    }
}
