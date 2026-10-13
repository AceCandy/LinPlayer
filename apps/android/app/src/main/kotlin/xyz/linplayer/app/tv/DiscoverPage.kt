package xyz.linplayer.app.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.collectAsState
import xyz.linplayer.app.data.extensionBlock
import xyz.linplayer.app.data.openExtension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.CardPoster
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.bleed
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.map

private data class Entry(val title: String, val sub: String, val image: String?, val rank: Int,
                         val entry: JsonObject)

private val Weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 发现 = 排行榜 + 放送表(UI_TV.md §7.9)【用户定 2026-09-14】。
 * ★ **切 tab / 切分类时焦点留在左栏**,右栏内容淡入替换、**不抢焦点**。
 * ★ 榜单 / 放送条目**不是本地库的条目**:打开时实时向 MSG 精确匹配,唯一命中进详情。
 */
@Composable
fun DiscoverPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val t = tvType
    var tab by keepState("tv.discover.tab") { 0 }
    val identity by app.session.collectAsState()
    var cats by remember(identity) { mutableStateOf<Block<List<Pair<String, String>>>>(Block.Loading) }
    var cat by keepState<Int>("tv.rank.cat") { 0 }
    var reload by remember { mutableStateOf(0) }
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).dayOfWeek.value
    var day by keepState("tv.cal.day") { today }

    LaunchedEffect(identity, reload) {
        fun categories(e: kotlinx.serialization.json.JsonElement) = e.arr().mapNotNull { it.obj() }
            .mapNotNull { o -> o.str("id")?.let { it to (o.str("label") ?: "榜单") } }
        cats = app.extensionBlock("emby.extRankings", onCached = { cats = Block.Ok(categories(it)) }).map(::categories)
        if (cat >= (cats.valueOrNull?.size ?: 0)) cat = 0
    }
    fun open(e: Entry) {
        scope.launch { app.openExtension(e.entry) { id, type -> nav.push(TvRoute.Detail(id, type)) } }
    }

    Row(Modifier.contentArea()) {
        Column(Modifier.width(180.dp)) {
            TvText("发现", t.headline, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.height(TvSp.x8))
            ScopeChips(listOf("排行榜", "放送表"), tab, itemModifier = { i -> Modifier.memo("discover.tab.$i") }, onSelect = { tab = it })
            Spacer(Modifier.height(TvSp.x8))
            if (tab == 0) when (val c = cats) {
                is Block.Loading -> repeat(4) { Skel(Modifier.width(160.dp).height(32.dp)); Spacer(Modifier.height(TvSp.x6)) }
                is Block.Fail -> TvText("榜单分类没加载出来:${c.message}", t.meta, TvC.fg3, maxLines = 3)
                is Block.Ok -> c.value.forEachIndexed { i, (_, label) ->
                    PanelItem(label, selected = i == cat, check = false, modifier = Modifier.memo("rank.cat.$i", initial = i == cat),
                        onClick = { cat = i })
                }
            } else TvText("MSG 公共周历 · 日本时区", t.meta, TvC.fg3)
            PanelItem("刷新", onClick = { reload++ })
        }
        Spacer(Modifier.width(TvSp.x24))
        Box(Modifier.weight(1f)) {
            AnimatedContent(targetState = Triple(tab, cat, reload), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "discover") { (tb, ct, src) ->
                if (tb == 0) RankingPane(app, cats.valueOrNull?.getOrNull(ct), cats) { open(it) }
                else CalendarPane(app, src, day, today, { day = it }) { open(it) }
            }
        }
    }
}

@Composable
private fun RankingPane(app: AppState, cat: Pair<String, String>?, cats: Block<List<Pair<String, String>>>, open: (Entry) -> Unit) {
    val t = tvType
    if (cats is Block.Ok && cats.value.isEmpty()) {
        // ☠ **不存在「排行榜开关」**,别写「去设置里打开」:用户照着找只会翻个空
        TvText("当前服务器暂无可用榜单。", t.body, TvC.fg2, maxLines = 2)
        return
    }
    val c = cat ?: return
    val identity by app.session.collectAsState()
    var block by remember(c.first, identity) { mutableStateOf<Block<List<Entry>>>(Block.Loading) }
    var page by remember(c.first, identity) { mutableStateOf(1) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(identity, c.first, page, retry) {
        block = Block.Loading
        fun rows(e: kotlinx.serialization.json.JsonElement) = e.arr().mapNotNull { it.obj() }.map { o ->
            Entry(o.str("title") ?: "", o.dbl("rating")?.let { "★ %.1f".format(it) } ?: o.str("subtitle").orEmpty(),
                o.str("image_url"), (o.long("rank") ?: 0).toInt(), o)
        }
        block = app.extensionBlock("emby.extRankingItems", args("category_id" to c.first, "page" to page),
            onCached = { block = Block.Ok(rows(it)) }).map(::rows)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvText(c.second, t.title, TvC.fg, weight = TvW.semi)
        }
        Row {
            if (page > 1) PanelItem("上一页", modifier = Modifier.weight(1f).memo("rank.previous"), onClick = { page-- })
            PanelItem("第 $page 页 · 刷新", modifier = Modifier.weight(1f).memo("rank.refresh"), onClick = { retry++ })
            if (page < 500 && block.valueOrNull?.isNotEmpty() == true) PanelItem("下一页", modifier = Modifier.weight(1f).memo("rank.next"), onClick = { page++ })
        }
        Spacer(Modifier.height(TvSp.x12))
        EntryGrid(block, rank = true, keyPrefix = "rank.${c.first}", open = open)
    }
}

/** 周归组:Bangumi 给 weekday(1=周一);Trakt 给 air_date,按本地时区算星期。 */
internal fun weekdayOf(o: JsonObject): Int? = o.long("weekday")?.toInt()?.takeIf { it in 1..7 }
    ?: (o.str("broadcast_at") ?: o.str("air_date"))?.let { iso ->
        runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).dayOfWeek.value }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(iso.take(10)).dayOfWeek.value }.getOrNull()
    }

@Composable
private fun CalendarPane(app: AppState, src: Int, day: Int, today: Int, onDay: (Int) -> Unit, open: (Entry) -> Unit) {
    val t = tvType
    val identity by app.session.collectAsState()
    var all by remember(identity) { mutableStateOf<Block<List<JsonObject>>>(Block.Loading) }
    LaunchedEffect(identity, src) {
        all = Block.Loading
        all = app.extensionBlock("emby.extCalendar", onCached = {
            all = Block.Ok(it.arr().mapNotNull { row -> row.obj() })
        }).map { it.arr().mapNotNull { row -> row.obj() } }
    }
    Column {
        TvText("放送表 · MSG 资料中心", t.title, TvC.fg, weight = TvW.semi)
        Spacer(Modifier.height(TvSp.x8))
        ScopeChips(Weekdays.mapIndexed { i, w -> if (i + 1 == today) "今天" else w }, day - 1,
            itemModifier = { i -> Modifier.memo("cal.day.$i") }, onSelect = { onDay(it + 1) })
        Spacer(Modifier.height(TvSp.x12))
        val block = all.map { list -> list.filter { weekdayOf(it) == day }.map { o ->
            Entry(o.str("title") ?: "", o.str("subtitle").orEmpty(), o.str("image_url"), 0, o)
        } }
        EntryGrid(block, rank = false, keyPrefix = "cal.$day", open = open)
    }
}

@Composable
private fun EntryGrid(block: Block<List<Entry>>, rank: Boolean, keyPrefix: String, open: (Entry) -> Unit) {
    val t = tvType
    when (block) {
        is Block.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) { repeat(5) { Skel(Modifier.width(TvDim.posterW).height(TvDim.posterH)) } }
        is Block.Fail -> TvText("没加载出来:${block.message}", t.meta, TvC.fg3, maxLines = 3)
        is Block.Ok -> if (block.value.isEmpty()) TvText("这里暂时没有内容", t.body, TvC.fg3) else LazyVerticalGrid(
            GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(TvSp.x16), contentPadding = PaddingValues(12.dp), modifier = Modifier.bleed(12.dp),
        ) {
            itemsIndexed(block.value, key = { i, e -> "$i.${e.title}" }) { i, e ->
                CardPoster(cover = { TvImage(e.image) }, title = e.title, sub = e.sub, rank = if (rank) e.rank else 0,
                    modifier = Modifier.memo("$keyPrefix.$i"), onClick = { open(e) })
            }
        }
    }
}
