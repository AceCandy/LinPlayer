package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.activateMediaServer
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.displayBlock
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LongShotTarget
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.LpLoading
import xyz.linplayer.app.ui.components.LpPullIndicator
import xyz.linplayer.app.ui.components.Panel
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.components.privateNeonBorder
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/**
 * 聚合视界(U1.8)· 底栏第二个 Tab。
 *
 * 按服务器分组展示总览；整次读取失败与单服失败分别呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AggregatePage(nav: NavController) {
    val app = LocalApp.current
    val privateSpace by app.privateSpace.collectAsState()
    // 私密聚合页只承载本服流影和榜单，不读取普通聚合快照或发起跨服请求。
    if (privateSpace) {
        LpScaffold("聚合视界") { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Sp.x16, vertical = Sp.x12)) {
                Shortcut("流影", LpIcons.play, Modifier.fillMaxWidth()) { nav.navigate(Route.Liuying) }
                Spacer(Modifier.height(Sp.x8))
                Shortcut("排行榜", LpIcons.trophy, Modifier.fillMaxWidth()) { nav.navigate(Route.Ranking) }
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    // 截长屏认的就是这个滚动容器(设置里开了才画按钮,见 LongShot)
    LongShotTarget(list)


    val session by app.session.collectAsState()
    val identity = session?.let { it.server to it.userId }
    // 首页只缓存当前服续播；其它服务器的统计和续播沿用聚合快照。
    var groups by remember(identity) { mutableStateOf(cachedOverview()) }
    SideEffect {
        if (app.session.value?.let { it.server to it.userId } == identity)
            PageCache.put("agg.groups", groups)
    }
    var done by remember(identity) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf<Block.Fail?>(null) }
    var refreshResume by remember(identity) { mutableStateOf(false) }

    /* ☠ `emby.aggregateOverview` **不是流式的**,它一次性返回整张表。
       这里原来给它挂了 onPartial 回调,又按 server / name / items 三个
       **核心层根本不发**的字段名去取 —— 结果 groups 恒空,页面永远画
       「添加更多服务器才有聚合」。真实字段是 server_name / counts / resume。
       同一类错(响应字段名对不上)由 scripts/check-android-fields.py 守着。 */
    val countsOnly = !refreshResume
    LaunchedEffect(identity, reload) {
        done = false; failure = null
        try {
            val result = app.displayBlock("emby.aggregateOverview", args("counts_only" to countsOnly)) { cached ->
                val saved = cached.arr().mapNotNull { Overview.from(it) }
                val existing = groups.associateBy { it.serverId }
                // 首页可能只预先给了当前服，不能因此丢掉其它服的持久续播快照。
                groups = saved.map { row -> existing[row.serverId]?.let { current ->
                    row.copy(movie = current.movie ?: row.movie, series = current.series ?: row.series,
                        episode = current.episode ?: row.episode, resume = current.resume)
                } ?: row } + groups.filter { current -> saved.none { it.serverId == current.serverId } }
            }
            currentCoroutineContext().ensureActive()
            if (app.session.value?.let { it.server to it.userId } != identity) return@LaunchedEffect
            when (result) {
                is Block.Ok -> groups = result.value.arr().mapNotNull { Overview.from(it) }.map { fresh ->
                    val old = groups.firstOrNull { it.serverId == fresh.serverId }
                    fresh.copy(
                        movie = if (fresh.countsKnown) fresh.movie else old?.movie,
                        series = if (fresh.countsKnown) fresh.series else old?.series,
                        episode = if (fresh.countsKnown) fresh.episode else old?.episode,
                        resume = if (fresh.error != null || fresh.resumeUnchanged) old?.resume.orEmpty() else fresh.resume,
                    )
                }
                is Block.Fail -> failure = result
                is Block.Loading -> Unit
            }
            // 共享最新成功续播，返回时不能用首页旧快照覆盖刚刷新的聚合数据。
            if (result is Block.Ok && PageCache.get<List<Account>>("home.accounts")?.any { it.isActive && it.aggregate } == true)
                groups.firstOrNull { it.active && it.error == null }?.let {
                    PageCache.put("home.resume", Block.Ok(it.resume))
                }
        } finally {
            done = true
        }
    }

    LaunchedEffect(app, identity) {
        app.invalidate.collect { if (it in setOf("all", "resume", "accounts")) {
            refreshResume = it != "accounts"
            reload++
        } }
    }

    LpScaffold("聚合视界", scrolled = rememberScrolled(list)) { pad ->
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = !done && groups.isNotEmpty(), state = pull,
            indicator = { LpPullIndicator(pull, !done && groups.isNotEmpty()) },
            onRefresh = { if (done) { refreshResume = false; reload++ } },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(Modifier.fillMaxSize(), list) {
                item("shortcuts") { Shortcuts(nav) }
                failure?.takeUnless { it.isSilent }?.let { error ->
                    item("error") { ErrorState(error.message, { reload++ }) }
                }
                groups.forEach { g ->
                    item("server-${g.serverId}") {
                        Panel(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x8)) {
                            ServerHead(g)
                            (g.error ?: g.countsError)?.let { err ->
                                Text("现在连不上:$err", color = Lp.colors.fg2, style = LpText.caption,
                                    modifier = Modifier.padding(horizontal = Sp.x16))
                            }
                            if (g.resume.isNotEmpty()) LpRow("", g.resume,
                                { it, h -> app.imageUrl(it.id, "Primary", h, g.lineUrl) },
                                { item -> scope.launch {
                                    if (g.active || app.activateMediaServer(g.serverId))
                                        nav.navigate(Route.Detail(item.id, item.type))
                                } }, thumb = true, resume = true,
                                menu = if (g.active) { item -> rememberCardActions(app, scope, item) } else null)
                            Spacer(Modifier.height(Sp.x12))
                        }
                    }
                }
                if (done && failure == null && groups.isEmpty()) item("empty") {
                    EmptyState("还没有添加服务器", "添加一台服务器之后，这里会显示它的规模和继续观看。",
                        LpIcons.globe, actionLabel = "去添加服务器",
                        onAction = { nav.navigate(Route.AddServer) })
                }
                item("tail") { Spacer(Modifier.height(Sp.x26)) }
            }
            if (groups.isEmpty() && !done) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                LpLoading(size = if (xyz.linplayer.app.data.UiPrefs.loadingStyle.value == 0) 90.dp else 130.dp)
            }
        }
    }
}

/** 聚合页的附加功能入口。 */
@Composable
private fun Shortcuts(nav: NavController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x12),
        horizontalArrangement = Arrangement.spacedBy(Sp.x8),
    ) {
        if (!xyz.linplayer.app.tv.LocalTvMode.current) Shortcut("下载", LpIcons.download, Modifier.weight(1f)) { nav.navigate(Route.Downloads) }
        Shortcut("排行榜", LpIcons.trophy, Modifier.weight(1f)) { nav.navigate(Route.Ranking) }
        Shortcut("日历", LpIcons.calendar, Modifier.weight(1f)) { nav.navigate(Route.Calendar) }
    }
}

/** 首页与聚合快照均随账号切换清理；成功的空续播也必须覆盖旧内容。 */
private fun cachedOverview(): List<Overview> {
    val saved = PageCache.get<List<Overview>>("agg.groups").orEmpty()
    val home = PageCache.get<Block<List<Item>>>("home.resume") as? Block.Ok ?: return saved
    val account = PageCache.get<List<Account>>("home.accounts")?.firstOrNull { it.isActive }
        ?: return saved
    if (!account.aggregate || account.plugin != null) return saved
    val previous = saved.firstOrNull { it.serverId == account.server }
    val current = previous?.copy(resume = home.value) ?: Overview(
        account.server, account.name, true, null, null, null, home.value, null,
        lineUrl = account.lineUrl, primary = account.aggregatePrimary,
    )
    return if (previous == null) saved + current else saved.map { if (it.serverId == account.server) current else it }
}

@Composable
private fun Shortcut(label: String, icon: ImageVector, m: Modifier, onClick: () -> Unit) {
    val c = Lp.colors
    Column(
        m.privateNeonBorder(R.md).clip(RoundedCornerShape(R.md)).background(c.s1).pressable(onClick).padding(vertical = Sp.x12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = c.mediaIcon)
        Spacer(Modifier.height(Sp.x6))
        Text(label, color = c.fg2, style = LpText.badge)
    }
}

/** 一台服务器的总览。字段名照 `core/aggregate/aggregate.go` 的 `SourceOverview`。 */
@androidx.compose.runtime.Immutable
private data class Overview(
    val serverId: String,
    val serverName: String,
    val active: Boolean,
    val movie: Long?,
    val series: Long?,
    val episode: Long?,
    val resume: List<Item>,
    /** 这台连不上的原因。以前吞掉,看着像「这台什么都没在看」。 */
    val error: String?,
    val lineUrl: String = serverId,
    val primary: Boolean = false,
    val countsKnown: Boolean = false,
    val countsError: String? = null,
    val resumeUnchanged: Boolean = false,
) {
    companion object {
        fun from(e: kotlinx.serialization.json.JsonElement?): Overview? {
            val o = e.obj() ?: return null
            val counts = o["counts"].obj()
            val known = o.str("counts_error") == null && (o["counts_known"] == null || o.bool("counts_known"))
            return Overview(
                serverId = o.str("server_id") ?: return null,
                serverName = o.str("server_name")?.takeIf { it.isNotBlank() } ?: "服务器",
                active = o.bool("active"),
                movie = counts.long("movie").takeIf { known },
                series = counts.long("series").takeIf { known },
                episode = counts.long("episode").takeIf { known },
                lineUrl = o.str("line_url") ?: o.str("server_id").orEmpty(),
                primary = o.bool("aggregate_primary"),
                countsKnown = known,
                countsError = o.str("counts_error"),
                resumeUnchanged = o.bool("resume_unchanged"),
                resume = Item.list(o["resume"]),
                error = o.str("error"),
            )
        }
    }
}

@Composable
private fun ServerHead(g: Overview) {
    val c = Lp.colors
    Column(
        Modifier.fillMaxWidth().padding(start = Sp.x16, end = Sp.x16, top = Sp.x20, bottom = Sp.x6),
        verticalArrangement = Arrangement.spacedBy(Sp.x6),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
            // 地址仅作内部键；长名字不能挤掉当前服务器标记。
            Text(g.serverName, color = c.fg, style = LpText.title,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (g.primary) Text("主服", color = c.mediaIcon, style = LpText.badge)
            if (g.active) Text("当前", color = c.mediaOnAccent, style = LpText.badge,
                modifier = Modifier.clip(RoundedCornerShape(R.sm)).background(c.mediaAccent)
                    .padding(horizontal = Sp.x6, vertical = Sp.x4))
        }
        val counts = listOfNotNull(
            g.movie?.let { "电影 $it" },
            g.series?.let { "剧集 $it" },
            g.episode?.let { "分集 $it" },
        ).joinToString("  ·  ")
        Text(counts.ifEmpty { "资源数量尚未取得" }, color = c.fg2, style = LpText.caption)
    }
}
