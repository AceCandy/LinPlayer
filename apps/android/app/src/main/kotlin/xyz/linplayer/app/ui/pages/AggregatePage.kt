package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.displayBlock
import xyz.linplayer.app.data.block
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
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.LpRowSkeleton
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/**
 * 聚合视界(U1.8)· 底栏第二个 Tab。
 *
 * 按服务器分组展示总览；整次读取失败与单服失败分别呈现。
 */
@Composable
fun AggregatePage(nav: NavController) {
    val app = LocalApp.current
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

    /* ☠ `emby.aggregateOverview` **不是流式的**,它一次性返回整张表。
       这里原来给它挂了 onPartial 回调,又按 server / name / items 三个
       **核心层根本不发**的字段名去取 —— 结果 groups 恒空,页面永远画
       「添加更多服务器才有聚合」。真实字段是 server_name / counts / resume。
       同一类错(响应字段名对不上)由 scripts/check-android-fields.py 守着。 */
    LaunchedEffect(identity, reload) {
        done = false; failure = null
        try {
            val result = app.displayBlock("emby.aggregateOverview") { cached ->
                if (groups.isEmpty()) groups = cached.arr().mapNotNull { Overview.from(it) }
            }
            currentCoroutineContext().ensureActive()
            if (app.session.value?.let { it.server to it.userId } != identity) return@LaunchedEffect
            when (result) {
                is Block.Ok -> groups = result.value.arr().mapNotNull { Overview.from(it) }.map { fresh ->
                    if (fresh.error == null) fresh else groups.firstOrNull { it.serverId == fresh.serverId }
                        ?.let { fresh.copy(resume = it.resume) }
                        ?: fresh
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
        app.invalidate.collect { if (it in setOf("all", "resume", "accounts")) reload++ }
    }

    LpScaffold("聚合视界", scrolled = rememberScrolled(list), actions = {
        LpIconButton(LpIcons.refresh, "刷新") { reload++ }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            item("shortcuts") { Shortcuts(nav) }

            if (groups.isEmpty() && !done) item("skel") {
                LpRowSkeleton(); LpRowSkeleton()
            }

            failure?.takeUnless { it.isSilent }?.let { error ->
                item("error") { ErrorState(error.message, { reload++ }) }
            }
            if (!done && groups.isNotEmpty()) item("refreshing") {
                Dim3("正在刷新…", Modifier.padding(horizontal = Sp.x16))
            }

            groups.forEach { g ->
                item("head-${g.serverId}") { ServerHead(g) }
                g.error?.let { err -> item("err-${g.serverId}") {
                    Text("现在连不上:$err", color = Lp.colors.fg2, style = LpText.caption,
                        modifier = Modifier.padding(horizontal = Sp.x16))
                } }
                if (g.resume.isNotEmpty()) item("resume-${g.serverId}") {
                    LpRow("继续观看", g.resume, { app.imageUrl(it.id, "Primary", 220) },
                        { nav.navigate(Route.Detail(it.id, it.type)) }, thumb = true, resume = true,
                        menu = { cardActions(app, scope, it) })
                }
            }

            // ★ 单台服务器**照样能用**【用户定 2026-09-06】:聚合视界不是「多服专属」,
            //   它是「跨源总览 + 快捷入口」。空态只在真的一台服都没有时出现。
            if (done && failure == null && groups.isEmpty()) item("empty") {
                EmptyState(
                    "还没有添加服务器", "添加一台服务器之后,这里会显示它的规模和继续观看。",
                    LpIcons.globe, actionLabel = "去添加服务器",
                    onAction = { nav.navigate(Route.AddServer) },
                )
            }
            item("tail") { Spacer(Modifier.height(Sp.x26)) }
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
        Shortcut("下载", LpIcons.download, Modifier.weight(1f)) { nav.navigate(Route.Downloads) }
        Shortcut("排行榜", LpIcons.trophy, Modifier.weight(1f)) { nav.navigate(Route.Ranking) }
        Shortcut("日历", LpIcons.calendar, Modifier.weight(1f)) { nav.navigate(Route.Calendar) }
        Shortcut("流影", LpIcons.play, Modifier.weight(1f)) { nav.navigate(Route.Liuying) }
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
        account.server, account.name, true, 0, 0, 0, home.value, null,
    )
    return if (previous == null) saved + current else saved.map { if (it.serverId == account.server) current else it }
}

@Composable
private fun Shortcut(label: String, icon: ImageVector, m: Modifier, onClick: () -> Unit) {
    val c = Lp.colors
    Column(
        m.clip(RoundedCornerShape(R.md)).background(c.s1).pressable(onClick).padding(vertical = Sp.x12),
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
    val movie: Long,
    val series: Long,
    val episode: Long,
    val resume: List<Item>,
    /** 这台连不上的原因。以前吞掉,看着像「这台什么都没在看」。 */
    val error: String?,
) {
    companion object {
        fun from(e: kotlinx.serialization.json.JsonElement?): Overview? {
            val o = e.obj() ?: return null
            val counts = o["counts"].obj()
            return Overview(
                serverId = o.str("server_id") ?: return null,
                serverName = o.str("server_name")?.takeIf { it.isNotBlank() } ?: "服务器",
                active = o.bool("active"),
                movie = counts.long("movie") ?: 0,
                series = counts.long("series") ?: 0,
                episode = counts.long("episode") ?: 0,
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
            if (g.active) Text("当前", color = c.mediaOnAccent, style = LpText.badge,
                modifier = Modifier.clip(RoundedCornerShape(R.sm)).background(c.mediaAccent)
                    .padding(horizontal = Sp.x6, vertical = Sp.x4))
        }
        val counts = listOfNotNull(
            g.movie.takeIf { it > 0 }?.let { "电影 $it" },
            g.series.takeIf { it > 0 }?.let { "剧集 $it" },
            g.episode.takeIf { it > 0 }?.let { "分集 $it" },
        ).joinToString("  ·  ")
        if (counts.isNotEmpty()) Text(counts, color = c.fg2, style = LpText.caption)
    }
}
