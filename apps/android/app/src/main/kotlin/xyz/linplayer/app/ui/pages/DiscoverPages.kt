package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import xyz.linplayer.app.ui.theme.Dim
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.ui.components.LpPullRefresh
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.extensionBlock
import xyz.linplayer.app.data.openExtension
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.Session
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LongShotTarget
import xyz.linplayer.app.ui.components.BlockBox
import xyz.linplayer.app.ui.components.LoadingState
import xyz.linplayer.app.ui.components.Body
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.MediaFilterChip
import xyz.linplayer.app.ui.components.glow
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.lpTween

/*
 * 发现族两页:排行榜 · 追剧日历。版式照草稿 06 / 07。
 *
 * ☠ 这两页 2026-09-06 之前**整页是空的**,而且不报错 —— 界面按
 * `name` / `items` / `air_time` 取值,核心层发的是 `label` / 平铺数组 /
 * `broadcast_at`;排行榜更把 `ranking.Entry` 当成 Emby 的 `Item` 去解析。
 * 同一类错由 `scripts/check-android-fields.py` 守着。
 *
 * ★ 榜单条目**不是本地库里的条目**:它的 id 是 TMDB / 弹弹Play 的 id,
 *   点击由 MSG 按来源标识精确匹配，再进入 Emby 详情。
 *
 * 两页共用紧凑顶栏与蓝紫筛选，奖牌颜色仅用于表达榜单名次。
 */

private val GOLD = Color(0xFFFFC94D)
// ---------------------------------------------------------------- 排行榜

/** MSG 榜单的展示字段及精确来源身份。 */
@androidx.compose.runtime.Immutable
private data class Rank(
    val id: String,
    val rank: Int,
    val title: String,
    val image: String?,
    val rating: Double?,
    val subtitle: String?,
    val code: String?,
    val score: Long?,
    val favorited: Boolean,
    val entry: kotlinx.serialization.json.JsonObject,
) {
    companion object {
        fun list(e: kotlinx.serialization.json.JsonElement?): List<Rank> =
            e.arr().mapNotNull {
                val o = it.obj() ?: return@mapNotNull null
                val jav = o.str("source") == "javranking"
                Rank(
                    id = o.str("id") ?: return@mapNotNull null,
                    rank = (o.long("rank") ?: 0).toInt(),
                    title = o.str("title") ?: return@mapNotNull null,
                    image = o.str("image_url") ?: o.str("poster_url"),
                    rating = if (jav) null else o.dbl("rating"),
                    subtitle = o.str("subtitle"),
                    code = if (jav) o.str("external_id") else null,
                    score = if (jav) o.long("score") else null,
                    favorited = o.bool("is_favorited"),
                    entry = o,
                )
            }
    }
}

private data class RankingCategory(val id: String, val label: String, val source: String, val enabled: Boolean)

private class RankingRows {
    val rows = mutableStateOf(emptyList<Rank>())
    val block = mutableStateOf<Block<List<Rank>>>(Block.Loading)
    val page = mutableStateOf(1)
    val ended = mutableStateOf(false)
    val moreError = mutableStateOf<String?>(null)
}

/** 榜单体系页签：选中加粗并带强调色短线，未选中弱化文字。 */
@Composable
private fun RankingSourceTab(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Lp.colors
    Column(
        Modifier.heightIn(min = Dim.tap).clip(RoundedCornerShape(R.sm))
            .semantics { selected = on }.pressable({ if (!on) onClick() })
            .padding(horizontal = Sp.x8, vertical = Sp.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (on) c.fg else c.fg3, style = LpText.list,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        Spacer(Modifier.height(Sp.x4))
        Box(Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(R.pill))
            .background(if (on) c.acc else Color.Transparent))
    }
}

private fun rankingSourceLabel(source: String) = when (source) {
    "tmdb" -> "TMDb"
    "douban" -> "豆瓣"
    "bangumi" -> "Bangumi"
    "hongguo" -> "红果"
    "huangguoai" -> "黄果 AI"
    "javranking" -> "JavRanking"
    else -> source.ifBlank { "其它" }
}

/**
 * 排行榜(U1.14a)。版式照草稿 06。
 *
 * ★ **名次是这一页的主视觉**:大数字压在海报**后面**,海报反倒是配角 ——
 *   排行榜卖的就是名次。三张卡不在同一条线上,错落本身就是动势。
 * ★ 列表**零分隔线**:奇数行铺一层从左往右淡出的白 5%,右侧自然消失,不会在行尾切一刀。
 * ★ 取数失败必须**向上报错,不许吞成空表** —— 空表和失败在界面上长得一样,
 *   但一个该重试一个不该。
 * ★ 分类空表和不支持/失败分别展示；无需客户端第三方凭据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingPage(nav: NavController) {
    val app = LocalApp.current
    val identity by app.session.collectAsState()
    val primary by app.aggregatePrimary.collectAsState()
    val privateSpace by app.privateSpace.collectAsState()
    val rankingServer = if (privateSpace) identity?.server else primary
    val list = rememberLazyListState()
    // 截长屏认的就是这个滚动容器(设置里开了才画按钮,见 LongShot)
    LongShotTarget(list)

    fun cacheKey(session: Session?) = "rankings.${session?.server}.${session?.userId}.$rankingServer.$privateSpace"
    val ck = cacheKey(identity)
    var cats by keepState<Block<List<RankingCategory>>>("$ck.cats") { Block.Loading }
    var cur by rememberSaveable(rankingServer, privateSpace) { mutableStateOf<String?>(null) }
    var source by rememberSaveable(rankingServer, privateSpace) { mutableStateOf<String?>(null) }
    var reload by rememberSaveable { mutableStateOf(0) }
    var rankReload by rememberSaveable { mutableStateOf(0) }
    var completedCatalog by rememberSaveable(rankingServer, privateSpace) { mutableStateOf<Int?>(null) }
    var completedPage by rememberSaveable(rankingServer, privateSpace, cur) { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val kept by keepState("$ck.$cur") { RankingRows() }
    var rows by kept.rows
    var block by kept.block
    var page by kept.page
    var ended by kept.ended
    var moreError by kept.moreError
    var refreshing by remember(identity, rankingServer, cur) { mutableStateOf(false) }
    // 只有手动下拉才显示刷新指示；进页、切榜与翻页静默进行。
    var pulling by remember(identity, rankingServer, cur) { mutableStateOf(false) }
    var moreRetry by remember(identity, rankingServer, cur) { mutableStateOf(0) }
    LaunchedEffect(identity, rankingServer, privateSpace, reload) {
        if (completedCatalog == reload && cats is Block.Ok) return@LaunchedEffect
        completedCatalog = null
        cats = Block.Loading
        fun categories(e: kotlinx.serialization.json.JsonElement) = e.arr().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            RankingCategory(o.str("id") ?: return@mapNotNull null, o.str("label") ?: "榜单",
                o.str("source") ?: o.str("group") ?: "其它", o["enabled"] == null || o.bool("enabled"))
        }
        cats = app.extensionBlock("emby.extRankings", args("include_disabled" to true), serverId = rankingServer, onCached = {
            val cached = categories(it)
            cats = Block.Ok(cached)
            val selected = cached.firstOrNull { it.id == cur }
                ?: cached.firstOrNull { it.enabled } ?: cached.firstOrNull()
            source = selected?.source
            if (cur == null || cached.none { it.id == cur }) cur = selected?.id
        }).map(::categories)
        val selected = cats.valueOrNull?.firstOrNull { it.id == cur }
            ?: cats.valueOrNull?.firstOrNull { it.enabled } ?: cats.valueOrNull?.firstOrNull()
        source = selected?.source
        if (cur == null || cats.valueOrNull?.none { it.id == cur } == true) cur = selected?.id
        if (cats is Block.Ok) completedCatalog = reload
    }
    val selected = cats.valueOrNull?.firstOrNull { it.id == cur }
    LaunchedEffect(identity, rankingServer, privateSpace, selected, rankReload) {
        val category = selected ?: run { pulling = false; return@LaunchedEffect }
        if (!category.enabled) {
            pulling = false
            rows = emptyList()
            block = Block.Fail("E_INVALID", "主服尚未开启或配置此榜单来源")
            return@LaunchedEffect
        }
        // 返回只恢复已完成的页；新进入、失败、取消及主动刷新仍请求首屏。
        if (completedPage != rankReload || block !is Block.Ok) {
            completedPage = null
            moreError = null
            refreshing = true
            try {
                if (rows.isEmpty()) block = Block.Loading
                val first = app.extensionBlock("emby.extRankingItems",
                    args("category_id" to category.id, "page" to 1), serverId = rankingServer,
                    onCached = { if (rows.isEmpty()) rows = Rank.list(it) }).map { Rank.list(it) }
                block = first
                when (first) {
                    is Block.Ok -> {
                        rows = first.value.distinctBy { it.id }
                        page = 1
                        ended = rows.isEmpty()
                        completedPage = rankReload
                    }
                    is Block.Fail -> {
                        if (first.code in setOf("E_AUTH", "E_PERMISSION", "E_INVALID", "E_NOTFOUND")) rows = emptyList()
                        return@LaunchedEffect
                    }
                    else -> return@LaunchedEffect
                }
            } finally { refreshing = false; pulling = false }
        }
        snapshotFlow { Triple(list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, rows.size, moreRetry) }
            .collect { (last, _, _) ->
                if (ended || moreError != null || last < 0 || last < list.layoutInfo.totalItemsCount - 3) return@collect
                val next = app.extensionBlock("emby.extRankingItems",
                    args("category_id" to category.id, "page" to page + 1), serverId = rankingServer,
                    onCached = {}).map { Rank.list(it) }
                currentCoroutineContext().ensureActive()
                when (next) {
                    is Block.Ok -> {
                        val combined = (rows + next.value).distinctBy { it.id }
                        ended = next.value.isEmpty() || combined.size == rows.size || page + 1 >= 500
                        rows = combined
                        page++
                    }
                    is Block.Fail -> moreError = next.message
                    else -> Unit
                }
            }
    }
    val open: (Rank) -> Unit = { row -> scope.launch {
        app.openExtension(row.entry) { id, type ->
            // 精确匹配后的切服会清页面缓存，仅交接此次已验证来源的榜单快照。
            val targetKey = cacheKey(app.session.value)
            PageCache.put("$targetKey.cats", cats)
            PageCache.put("$targetKey.$cur", kept)
            nav.navigate(Route.Detail(id, type))
        }
    } }

    LpScaffold("排行榜", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.fillMaxSize()) {
            val cf = cats
            if (cf is Block.Fail && !cf.isSilent) {
                ErrorState(cf.message, { reload++ })
                return@Column
            }
            val cs = cats.valueOrNull
            if (cs != null && cs.isEmpty()) {
                EmptyState("暂无可用榜单", "稍后再来看看。", LpIcons.trophy)
                return@Column
            }
            if (cs != null) {
                // 体系是上一级，用文字页签与下方榜单芯片拉开层级；只有一个体系时整行不画。
                val groups = cs.map { it.source }.distinct()
                if (groups.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Sp.x8),
                    verticalAlignment = Alignment.CenterVertically) {
                    groups.forEach { group ->
                        RankingSourceTab(rankingSourceLabel(group), group == source) {
                            source = group
                            cur = cs.firstOrNull { it.source == group && it.enabled }?.id
                                ?: cs.first { it.source == group }.id
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Sp.x16, end = Sp.x16, bottom = Sp.x8),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                    cs.filter { it.source == source }.forEach { category ->
                        MediaFilterChip(category.label + if (category.enabled) "" else " · 未开启",
                            category.id == cur) { cur = category.id }
                    }
                }
            }
            LpPullRefresh(pulling, onRefresh = { if (!refreshing) { pulling = true; rankReload++ } },
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                    if (rows.isEmpty()) item("state") {
                        BlockBox(block, { rankReload++ }, skeleton = { LoadingState(Modifier.fillParentMaxSize()) }) {
                            EmptyState("暂无榜单数据", "这个榜单现在是空的。", LpIcons.trophy)
                        }
                    } else {
                        if (rows.size >= 3) item("podium") { Podium(rows.take(3), open) }
                        itemsIndexed(rows.drop(if (rows.size >= 3) 3 else 0), key = { _, row -> row.id }) { index, row ->
                            RankRow(row, index, open)
                        }
                        (block as? Block.Fail)?.let { error -> item("refresh-error") {
                            ErrorState(error.message, { rankReload++ })
                        } }
                    }
                    moreError?.let { error -> item("more-error") {
                        ErrorState(error, { moreError = null; moreRetry++ })
                    } }
                    item("tail") { Spacer(Modifier.height(Sp.x26)) }
                }
            }
        }
    }
}

/** 金银铜。**只有这三个色号写死** —— 它们是奖牌不是主题色,跟着主题变就没意义了。 */
private val medals = listOf(GOLD, Color(0xFFD8DEE9), Color(0xFFD08B5B))

/**
 * 领奖台。第二名 · 第一名 · 第三名,**第一名整体上浮 14dp** 并且金色辉光 6 秒一次呼吸。
 * ★ 整页唯一的循环动效,慢到不会烦。
 */
@Composable
private fun Podium(top3: List<Rank>, open: (Rank) -> Unit) {
    // 排成 2 · 1 · 3
    val ordered = listOfNotNull(
        top3.getOrNull(1), top3.getOrNull(0), top3.getOrNull(2))
    Row(
        Modifier.fillMaxWidth().padding(start = Sp.x16, end = Sp.x16, top = Sp.x20, bottom = Sp.x16),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        verticalAlignment = Alignment.Bottom,
    ) {
        ordered.forEach { r -> Pod(r, Modifier.weight(1f), open) }
    }
}

@Composable
private fun Pod(r: Rank, m: Modifier, open: (Rank) -> Unit) {
    val c = Lp.colors
    val first = r.rank == 1
    val medal = medals.getOrElse(r.rank - 1) { medals[2] }

    val t = rememberInfiniteTransition(label = "pod")
    val breathe by t.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(6000, easing = LpEasing.standard), RepeatMode.Reverse),
        label = "breathe",
    )

    // 第一名整体上浮半层 —— 三张卡不在同一条线上,**错落本身就是动势**
    Column(m.offset(y = if (first) (-14).dp else 0.dp).pressable({ open(r) })) {
        Box {
            // 金色辉光只给第一名,6 秒一次呼吸。**整页唯一的循环动效**,慢到不会烦
            if (first) Box(
                Modifier.matchParentSize().graphicsLayer { alpha = breathe }.glow(GOLD, .45f)
            )
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                NetImage(LocalApp.current.proxiedImage(r.image), r.title, Modifier.fillMaxSize(), 12.dp)
            }
            /* 名次数字压在海报左下角、往左探出一截。
               ★ 它是这一页的**主视觉**,海报反而是配角 —— 排行榜卖的就是名次。
               ★ 用 offset 不用 padding:offset 不参与布局,数字探到卡外面去也不会把行撑宽。 */
            Text(
                r.rank.toString(),
                Modifier.align(Alignment.BottomStart).offset(x = (-8).dp, y = 6.dp),
                color = medal, fontSize = if (first) 62.sp else 52.sp,
                fontWeight = FontWeight.Black, lineHeight = if (first) 56.sp else 47.sp,
            )
        }
        Spacer(Modifier.height(Sp.x8))
        Text(
            r.title, color = c.fg, style = LpText.compact,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        r.code?.let { Text(it, color = c.fg2, style = LpText.caption) }
        r.score?.let { Text("战力 $it", color = c.mediaIcon, style = LpText.caption) }
        r.rating?.takeIf { it > 0 }?.let {
            Text("%.1f".format(it), color = c.mediaIcon, style = LpText.number, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 榜单一行。
 * ★ **零分隔线**:奇数行一层从左往右淡出的白 5% —— 斑马纹用渐变做,右侧自然消失。
 * ★ 排名使用次级文字色，保证深浅主题可读。
 * ★ 逐条错开 24ms 上浮进场,**只在首次组合时跑一次**。
 */
@Composable
private fun RankRow(r: Rank, index: Int, open: (Rank) -> Unit) {
    val c = Lp.colors
    var shown by remember(r.id) { mutableStateOf(false) }
    LaunchedEffect(r.id) {
        kotlinx.coroutines.delay((index.coerceAtMost(8) * 24).toLong()); shown = true
    }
    val a by animateFloatAsState(
        if (shown) 1f else 0f, lpTween(T.T5, LpEasing.emphasizedDecelerate), label = "rowIn")

    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = a; translationY = (1f - a) * 24f }
            .pressable({ open(r) }).padding(horizontal = Sp.x16, vertical = Sp.x6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            r.rank.toString(), Modifier.width(34.dp),
            color = c.fg2, style = LpText.number.copy(fontSize = 19.sp, lineHeight = 24.sp), fontWeight = FontWeight.Black,
        )
        NetImage(LocalApp.current.proxiedImage(r.image), null, Modifier.size(56.dp, 84.dp), 10.dp)
        Spacer(Modifier.width(Sp.x12))
        Column(Modifier.weight(1f)) {
            Body(r.title, maxLines = 2)
            r.subtitle?.let { Dim3(it, Modifier.padding(top = 2.dp)) }
            r.code?.let { Text(it, color = c.fg2, style = LpText.caption) }
            r.score?.let { Text("战力 $it", color = c.mediaIcon, style = LpText.caption) }
        }
        r.rating?.takeIf { it > 0 }?.let {
            Text("%.1f".format(it), color = c.mediaIcon, style = LpText.action.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- 追剧日历

/** MSG 公共周历；未知播出时刻保持为空。 */
@androidx.compose.runtime.Immutable
private data class Air(
    val title: String,
    val subtitle: String?,
    val weekday: Int,          // 1=周一 … 7=周日
    val hhmm: String?,         // 本地时刻;取不到就是 null(**不编一个时间出来**)
    val at: java.time.Instant?,
    val image: String?,
    val rating: Double?,
    val entry: kotlinx.serialization.json.JsonObject,
)

private val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 追剧日历(U1.14b)。版式照草稿 07。
 *
 * ★ 核心层发的是**平铺数组**,不是按天分组 —— 分组在这里做。
 * ★ 主轴是**时间不是番剧**:左边一条渐隐的光,同一时段的几部挂在同一个节点上。
 *   上一稿那条线是一根等粗的灰杠,太像表格了。
 */
/** 卡片点「可播 / 已入库」要跳页;不为这一个用处把 nav 一路传下去。 */
private val LocalCalendarNav = androidx.compose.runtime.staticCompositionLocalOf<NavController?> { null }

@Composable
fun CalendarPage(nav: NavController) = androidx.compose.runtime.CompositionLocalProvider(LocalCalendarNav provides nav) { CalendarBody(nav) }

@Composable
private fun CalendarBody(nav: NavController) {
    val app = LocalApp.current
    var all by remember { mutableStateOf<Block<List<Air>>>(Block.Loading) }
    // 默认落在今天。java.time 的 DayOfWeek 就是 1=周一,和核心层同口径
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).dayOfWeek.value
    var day by remember { mutableStateOf(today) }
    var reload by remember { mutableStateOf(0) }

    val identity by app.session.collectAsState()
    val primary by app.aggregatePrimary.collectAsState()
    LaunchedEffect(identity, primary, reload) {
        all = Block.Loading
        fun rows(e: kotlinx.serialization.json.JsonElement) = e.arr().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            Air(title = o.str("title") ?: return@mapNotNull null,
                subtitle = o.str("subtitle"), weekday = (o.long("weekday") ?: 0).toInt(),
                hhmm = o.str("broadcast_time")?.takeIf { o.str("timezone") == "Asia/Tokyo" }
                    ?.let { time -> runCatching { java.time.LocalTime.parse(time).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull() }, at = null, image = o.str("image_url"), rating = o.dbl("rating"), entry = o)
        }
        all = app.extensionBlock("emby.extCalendar", serverId = primary, onCached = { all = Block.Ok(rows(it)) }).map(::rows)
    }

    LpScaffold("追剧日历", onBack = { nav.popBackStack() }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            BlockBox(all, { reload++ }) { rows ->
                Column(Modifier.fillMaxSize()) {
                    Text("本周 ${rows.size} 部",
                        Modifier.padding(horizontal = Sp.x16, vertical = Sp.x8),
                        color = Lp.colors.fg2, style = LpText.caption)
                    WeekBar(day, today) { day = it }

                    val dIn = lpTween<androidx.compose.ui.unit.IntOffset>(
                        T.T5, LpEasing.emphasizedDecelerate)
                    val dInFade = lpTween<Float>(T.T4)
                    val dOut = lpTween<androidx.compose.ui.unit.IntOffset>(T.T4)
                    val dOutFade = lpTween<Float>(T.T3)
                    AnimatedContent(
                        targetState = day,
                        transitionSpec = {
                            val fwd = targetState > initialState
                            (slideInHorizontally(dIn) { if (fwd) it / 6 else -it / 6 } +
                                fadeIn(dInFade)) togetherWith
                                (slideOutHorizontally(dOut) { if (fwd) -it / 10 else it / 10 } +
                                    fadeOut(dOutFade))
                        },
                        label = "day",
                    ) { d ->
                        Timeline(rows.filter { it.weekday == d }, isToday = d == today, pad = PaddingValues())
                    }
                }
            }
        }
    }
}

/**
 * 周条:**七颗胶囊**,今天有文字标记，选中使用蓝紫底色。
 * ★ 上一稿是七个圆角方块 —— 方块并排 = 表格感。
 * ★ 胶囊只写星期与日期，不写当天部数：多一行数字整条周条过高。
 */
@Composable
private fun WeekBar(cur: Int, today: Int, onPick: (Int) -> Unit) {
    val c = Lp.colors
    val monday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).minusDays((today - 1).toLong())
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Sp.x12, vertical = Sp.x12),
        horizontalArrangement = Arrangement.spacedBy(Sp.x6),
    ) {
        (1..7).forEach { d ->
            val on = d == cur
            val z by animateFloatAsState(if (on) 1f else 0f, lpTween(T.T5), label = "wd")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(R.pill))
                    .then(
                        if (on) Modifier.background(
                            c.mediaAccent
                        ) else Modifier.background(c.s1)
                    )
                    .pressable({ onPick(d) })
                    .padding(vertical = Sp.x10),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (d == today) "今" else weekLabels[d - 1],
                    color = if (on) c.mediaOnAccent else if (d == today) c.mediaIcon else c.fg2,
                    style = LpText.caption,
                )
                Text(
                    "%02d".format(monday.plusDays((d - 1).toLong()).dayOfMonth),
                    color = if (on) c.mediaOnAccent else c.fg, style = LpText.list,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer { val s = 1f + z * .06f; scaleX = s; scaleY = s },
                )
            }
        }
    }
}

/**
 * 时间轴。同一时刻的几部挂同一个节点。
 * ★ 节点往下是一道**由强到无的光**,时段之间不画横线 —— 线自己淡完就是分界。
 * ★ 下一个时段的节点是一颗 2.6 秒一次的脉冲点:「26 分钟后」那几部在页面上是活的。
 */
@Composable
private fun Timeline(rows: List<Air>, isToday: Boolean, pad: PaddingValues) {
    if (rows.isEmpty()) {
        EmptyState("这一天没有放送", "放送表按上游时区分组,这一栏是空的。", LpIcons.calendar)
        return
    }
    // 没有放送时间的条目归入无标签的末段，不显示时间，也不推算
    val slots = rows.groupBy { it.hhmm.orEmpty() }.toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it })
    val now = java.time.Instant.now()
    // 「下一个时段」= 今天里第一个还没到的时刻。不是今天就没有这颗脉冲点
    val nextSlot = if (!isToday) null else slots.entries
        .firstOrNull { e -> e.value.any { it.at != null && it.at.isAfter(now) } }?.key

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        slots.forEach { (hhmm, list) ->
            item(hhmm.ifEmpty { "untimed" }) { Slot(hhmm, list, hhmm == nextSlot, now) }
        }
        item("source") {
            Dim3("周历 Bangumi · 时间 bangumi-data（CC\u00A0BY\u00A04.0）· 日本时区",
                Modifier.padding(Sp.x16), maxLines = 3)
        }
        item("tail") { Spacer(Modifier.height(Sp.x26)) }
    }
}

@Composable
private fun Slot(hhmm: String, list: List<Air>, isNext: Boolean, now: java.time.Instant) {
    val c = Lp.colors
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LpEasing.standardDecelerate), RepeatMode.Restart),
        label = "pulseP",
    )
    /* ★ 高度走 `IntrinsicSize.Min` 让那道光自己撑满整段。
       写成「卡片数 × 猜一个高度」的话,标题换行多一行就露出一截或短一截 ——
       而那种错位只有在真机上、恰好那一部片名字长的时候才看得见。 */
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .padding(start = Sp.x16, end = Sp.x16),
    ) {
        Column(
            Modifier.width(52.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                hhmm, color = if (isNext) c.mediaIcon else c.fg2, style = LpText.filter.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Sp.x6))
            Box(contentAlignment = Alignment.Center) {
                // 脉冲:一圈从节点扩出去再化掉
                if (isNext) Box(
                    Modifier.size(26.dp).graphicsLayer {
                        val s = 0.3f + p * 0.7f
                        scaleX = s; scaleY = s; alpha = (1f - p) * 0.55f
                    }.clip(RoundedCornerShape(R.pill)).background(c.mediaAccent)
                )
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(R.pill))
                        .background(if (isNext) c.mediaAccent else c.line2)
                )
            }
            // 节点往下那道**渐隐的光**:从强到无。时段之间不画横线 —— 线自己淡完就是分界
            Box(
                Modifier.width(2.dp).weight(1f).background(
                    Brush.verticalGradient(
                        listOf(
                            (if (isNext) c.mediaAccent else c.fg2).copy(alpha = .55f),
                            Color.Transparent,
                        )
                    )
                )
            )
        }
        Spacer(Modifier.width(Sp.x12))
        Column(Modifier.weight(1f).padding(top = Sp.x2)) {
            list.forEachIndexed { i, a -> AirCard(a, i, now) }
        }
    }
}

/** 番剧卡。**去掉描边**,白 6% 填充 + 16 圆角。 */
@Composable
private fun AirCard(a: Air, index: Int, now: java.time.Instant) {
    val c = Lp.colors
    val nav = LocalCalendarNav.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val open = { scope.launch {
        app.openExtension(a.entry) { id, type -> nav?.navigate(Route.Detail(id, type)) }
    }; Unit }
    var shown by remember(a.title) { mutableStateOf(false) }
    LaunchedEffect(a.title) {
        kotlinx.coroutines.delay((index.coerceAtMost(8) * 28).toLong()); shown = true
    }
    val t by animateFloatAsState(
        if (shown) 1f else 0f, lpTween(T.T6, LpEasing.emphasizedDecelerate), label = "airIn")

    Row(
        Modifier.fillMaxWidth().padding(bottom = Sp.x10)
            .graphicsLayer { alpha = t; translationY = (1f - t) * 28f }
            .clip(RoundedCornerShape(16.dp)).background(c.s1).pressable(open)
            .padding(Sp.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetImage(LocalApp.current.proxiedImage(a.image), null, Modifier.size(44.dp, 62.dp), 10.dp)
        Spacer(Modifier.width(Sp.x10))
        Column(Modifier.weight(1f)) {
            // ★ 标题不许单行截断(截成「…」= 显示不全),放开完整换行
            Body(a.title, maxLines = 2)
            a.subtitle?.let { Dim3(it, Modifier.padding(top = 2.dp)) }
            countdown(a.at, now)?.let { (label, soon) ->
                Spacer(Modifier.height(Sp.x6))
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(R.pill))
                        .background(if (soon) c.mediaAccent.copy(alpha = .12f) else c.ok.copy(alpha = .18f))
                        .padding(horizontal = Sp.x8, vertical = 2.dp),
                    color = if (soon) c.mediaIcon else c.ok, style = LpText.caption,
                )
            }
            Spacer(Modifier.height(Sp.x6))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("查看入库",
                    Modifier.padding(end = Sp.x6), color = c.mediaIcon, style = LpText.caption)
                a.rating?.takeIf { it > 0 }?.let {
                    Text("%.1f".format(it), Modifier.padding(end = Sp.x6), color = c.mediaIcon,
                        style = LpText.number, fontWeight = FontWeight.SemiBold)
                }
            }
        }

    }
}

/**
 * 「26 分钟后」/「已更新」。返回 null 表示**算不出来** —— 时刻缺失时不编一个出来。
 *
 * ★ Bangumi 官方 API 不给时刻,靠 bangumi-data 补;补不到的条目核心层发的就是 null。
 */
private fun countdown(at: java.time.Instant?, now: java.time.Instant): Pair<String, Boolean>? {
    if (at == null) return null
    val mins = java.time.Duration.between(now, at).toMinutes()
    return when {
        mins < 0 -> "已更新" to false
        mins < 60 -> "$mins 分钟后" to true
        mins < 24 * 60 -> "${mins / 60} 小时后" to true
        else -> null
    }
}

private fun parseIso(iso: String?): java.time.Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(iso) }.getOrNull()
}
