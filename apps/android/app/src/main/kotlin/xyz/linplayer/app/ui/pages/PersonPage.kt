package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.LocalTvMode
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.ui.theme.PluginTheme
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

private data class PersonWorks(
    val items: List<Item> = emptyList(),
    val total: Long? = null,
    val next: Int = 0,
    val more: Boolean = true,
    val loaded: Boolean = false,
)

/** 人物资料和作品独立加载；游标只由实时页推进，快照不参与分页判定。 */
@Composable
fun PersonPage(nav: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<Route.Person>()
    val app = LocalApp.current
    val tv = LocalTvMode.current
    val revision by app.userStateRevision.collectAsState()
    val identity by app.session.collectAsState()
    val space by app.spaceRevision.collectAsState()
    val key = "person.${entry.id}.${identity?.server}.${identity?.userId}.$space"
    var detail by keepState<Block<JsonElement>>("$key.detail") { Block.Loading }
    var detailPreview by remember(key) { mutableStateOf<JsonElement?>(null) }
    var detailRetry by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf("Movie") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val c = Lp.colors

    LaunchedEffect(key, detailRetry) {
        if (detail is Block.Ok && detailRetry == 0) return@LaunchedEffect
        try {
            detail = app.displayBlock("emby.personDetail", args("person_id" to route.personId),
                retainOnFailure = false) { detailPreview = it }
        } finally { detailPreview = null }
    }

    LpScaffold(title = "人物作品", onBack = { nav.popBackStack() }) { pad ->
        key(key, category) {
            PersonWorksGrid(nav, route.personId, "$key.$category", category, revision, pad) { total ->
                val d = (detailPreview ?: (detail as? Block.Ok)?.value).obj()
                Row(Modifier.fillMaxWidth().padding(start = Sp.x16, end = Sp.x16, top = Sp.x8, bottom = Sp.x12), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Sp.x16)) {
                    NetImage(if (d.bool("has_primary")) app.imageUrl(route.personId, "Primary", 192) else null, route.name,
                        Modifier.size(if (tv) 96.dp else 80.dp), R.pill)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Sp.x4)) {
                        Text(d.str("name").orEmpty().ifBlank { route.name }, color = c.fg,
                            style = LpText.title.copy(fontWeight = FontWeight.Bold), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            d.str("birthday")?.takeIf { it.isNotBlank() }?.take(10)?.let { "生于 $it" },
                            d.str("death_day")?.takeIf { it.isNotBlank() }?.take(10)?.let { "卒于 $it" },
                            d.str("birth_place")?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = c.fg3, style = LpText.caption)
                    }
                }
                when (val state = detail) {
                    is Block.Fail -> ErrorState(state.message, { detailRetry++ })
                    is Block.Loading -> Unit
                    is Block.Ok -> {
                        val overview = state.value.obj().str("overview").orEmpty()
                        if (overview.isNotBlank()) Text(overview,
                            Modifier.padding(horizontal = Sp.x16).testTag("person.overview")
                                .pressable({ expanded = !expanded }),
                            color = c.fg2, style = LpText.secondary, maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                // 网格行距已经隔开下方作品，这里不再留底边距
                Row(Modifier.padding(start = Sp.x16, end = Sp.x16, top = Sp.x12), horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                    for ((type, label) in listOf("Movie" to "电影", "Series" to "剧集")) {
                        MediaFilterChip(if (category == type && total != null) "$label $total" else label,
                            category == type, { category = type })
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonWorksGrid(nav: NavController, personId: String, cacheKey: String, category: String,
    revision: Long, pad: PaddingValues, header: @Composable (Long?) -> Unit) {
    val app = LocalApp.current
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var confirmed by keepState(cacheKey) { PersonWorks() }
    var checkedRevision by keepState<Long?>("$cacheKey.revision") { null }
    var preview by remember { mutableStateOf<PersonWorks?>(null) }
    var error by remember { mutableStateOf<Block.Fail?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val shown = preview ?: confirmed

    LaunchedEffect(cacheKey, revision, retry) {
        error = null
        suspend fun request(base: PersonWorks, showPreview: Boolean): PersonWorks? {
            busy = true
            try {
                fun page(value: JsonElement): PersonWorks {
                    val d = value.obj()
                    return PersonWorks((base.items + Item.list(d?.get("items"))).distinctBy { it.id },
                        d.long("total"), d.long("next_index")?.toInt() ?: base.next,
                        d.bool("has_more"), true)
                }
                when (val result = app.displayBlock("emby.personItemsPage",
                    args("person_id" to personId, "type" to category, "start_index" to base.next, "limit" to 30),
                    retainOnFailure = false, onCached = { if (showPreview) preview = page(it) })) {
                    is Block.Ok -> {
                        currentCoroutineContext().ensureActive()
                        val next = page(result.value)
                        if ((next.more && next.next <= base.next) ||
                            (Item.list(result.value.obj()?.get("items")).isNotEmpty() && next.items.size == base.items.size)) {
                            error = Block.Fail("E_UPSTREAM", "服务器没有推进人物作品分页")
                        } else return next
                    }
                    is Block.Fail -> {
                        error = result
                        if (result.code in setOf("E_AUTH", "E_PERMISSION", "E_NOTFOUND", "E_INVALID", "E_UNSUPPORTED"))
                            confirmed = PersonWorks()
                    }
                    else -> Unit
                }
                return null
            } finally {
                preview = null
                busy = false
            }
        }
        // 用户状态变化时重验原已加载范围，全部成功后才替换，保持返回位置。
        if (confirmed.loaded && checkedRevision != revision) {
            val wanted = confirmed.next
            var fresh = PersonWorks()
            do {
                fresh = request(fresh, false) ?: return@LaunchedEffect
            } while (fresh.more && fresh.next < wanted)
            confirmed = fresh
            checkedRevision = revision
        }
        snapshotFlow {
            Triple(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, confirmed.next, confirmed.loaded)
        }.collect { (last, _, loaded) ->
            if (error != null || !confirmed.more ||
                (loaded && confirmed.items.isNotEmpty() && last < confirmed.items.size - 5)) return@collect
            request(confirmed, true)?.let {
                confirmed = it
                checkedRevision = revision
            }
        }
    }

    val columns = PluginTheme.posterColumns(LocalConfiguration.current.screenWidthDp,
        if (LocalTvMode.current) 6 else posterLayout()?.first ?: 3)
    val imageHeight = posterImageHeight(width = gridCellWidth(columns))
    LazyVerticalGrid(
        GridCells.Fixed(columns),
        Modifier.fillMaxSize().testTag("person.works"), grid,
        contentPadding = PaddingValues(start = Sp.x16, end = Sp.x16,
            top = Sp.x8, bottom = pad.calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        verticalArrangement = Arrangement.spacedBy(Sp.x16),
    ) {
        item("header", span = { GridItemSpan(maxLineSpan) }) {
            Column { header(shown.total) }
        }
        error?.let { failure ->
            item("error", span = { GridItemSpan(maxLineSpan) }) { ErrorState(failure.message, { retry++ }) }
        }
        items(shown.items, key = { it.id }) { item ->
            MediaCard(item, app.imageUrl(item.id, "Primary", imageHeight),
                { nav.navigate(Route.Detail(item.id, item.type)) }, Modifier.fillMaxWidth(),
                menu = rememberCardActions(app, scope, item))
        }
        // 首批作品未到时占住下方区域放整块等待动画；续页静默进行。
        if (busy && shown.items.isEmpty()) item("loading", span = { GridItemSpan(maxLineSpan) }) {
            LoadingPane(.6f)
        }
        if (confirmed.loaded && !confirmed.more && shown.items.isEmpty() && error == null)
            item("empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("没有相关作品", "这个服务器没有返回该分类下可显示的作品。")
            }
    }
}
