package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.gridCellWidth
import xyz.linplayer.app.ui.components.posterImageHeight
import xyz.linplayer.app.ui.theme.LocalLpColors
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.ui.theme.Sp

/** 收藏仍由流影入库候选接口授权；点选交接准确条目，不重新随机取片。 */
@Composable
internal fun PhoneLiuyingFavorites(
    feed: String, initialTab: String, onClose: () -> Unit,
    onSelect: (String, LiuYingPageWindow, Int) -> Unit,
) {
    val app = LocalApp.current
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    val session by app.session.collectAsState()
    val privateSpace by app.privateSpace.collectAsState()
    var tab by remember(feed) { mutableStateOf(if (feed == "normal") initialTab else "") }
    key(session, privateSpace, feed, tab) {
        val seed = remember { java.util.UUID.randomUUID().toString() }
        var pages by remember { mutableStateOf<List<LiuYingPageWindow>>(emptyList()) }
        var offset by remember { mutableIntStateOf(0) }
        var retry by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var failure by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(offset, retry) {
            loading = true; failure = null
            try {
                val result = app.call("emby.extLiuYingItems", args("feed" to feed, "tab" to tab,
                    "channel" to "", "exposure" to "", "favorite" to true, "seed" to seed, "offset" to offset)).obj()
                coroutineContext.ensureActive()
                val rows = result?.get("items").arr().mapNotNull { it.obj() }
                pages = pages + LiuYingPageWindow(seed, offset, rows, rows.isNotEmpty() && result.bool("has_more"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                failure = error.message ?: "读取收藏失败"
            } finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
        }
        PhoneLiuyingSheet(if (feed == "normal") "收藏的流影" else "卍解收藏", "liuying.favoriteSheet", onClose,
            showHeader = tv) {
            // 与顶栏分类同一样式：靠左排列，选中项白字加粗并带下划线
            if (feed == "normal") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sp.x16)) {
                for ((value, title) in listOf("dance" to "舞蹈", "live" to "直播")) {
                    Column(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                        .testTag("liuying.favoriteTab.$value").semantics { selected = tab == value }
                        .pressable({ tab = value }).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, color = if (tab == value) Color.White else FeedSecondary,
                            style = LpText.list.copy(fontWeight = if (tab == value) FontWeight.SemiBold else FontWeight.Normal))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.size(20.dp, 3.dp).background(
                            if (tab == value) Color.White else Color.Transparent, RoundedCornerShape(2.dp)))
                    }
                }
            }
            // 流影面板固定深色；复用海报卡时不继承外层浅色文字。
            CompositionLocalProvider(LocalLpColors provides Lp.colors.copy(fg = Color.White, fg3 = Color.White.copy(alpha = .7f))) {
                val columns = posterColumns()
                val imageHeight = posterImageHeight(width = gridCellWidth(columns))
                LazyVerticalGrid(if (tv) GridCells.Adaptive(160.dp) else GridCells.Fixed(columns),
                    Modifier.fillMaxWidth().weight(1f, fill = false).testTag("liuying.favoriteList"),
                    contentPadding = PaddingValues(vertical = Sp.x12),
                    horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                    verticalArrangement = Arrangement.spacedBy(Sp.x16)) {
                    for (page in pages) items(page.rows.size, key = { "${page.offset}:$it" }) { index ->
                        val row = page.rows[index]
                        val id = row.str("item_id").orEmpty()
                        MediaCard(Item(id, row.str("title").orEmpty(), ""), app.imageUrl(id, height = imageHeight),
                            { onSelect(tab, page, index) }, Modifier.fillMaxWidth().testTag("liuying.favoriteItem.$id"),
                            entrance = false)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            when {
                                // 首屏没有内容时用整块等待动画；续页静默进行。
                                loading -> if (pages.isEmpty()) xyz.linplayer.app.ui.components.LoadingPane(.4f)
                                failure != null -> {
                                    Text(failure!!, color = Color.White, style = LpText.body)
                                    Text("重试", Modifier.heightIn(min = 48.dp).wrapContentHeight()
                                        .testTag("liuying.favoriteRetry").pressable({ retry++ }), color = Color.White)
                                }
                                pages.all { it.rows.isEmpty() } -> Text("暂无收藏视频", color = Color.White, style = LpText.body)
                                // 末尾进入视口即续页，不需要手动点击。
                                pages.lastOrNull()?.more == true -> LaunchedEffect(pages.size) {
                                    offset = pages.last().let { it.offset + it.rows.size }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
