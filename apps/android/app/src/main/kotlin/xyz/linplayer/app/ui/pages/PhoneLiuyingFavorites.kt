package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.LpText

/** 收藏仍由流影入库候选接口授权；点选交接原始页和seed，不能重新随机取片。 */
@Composable
internal fun PhoneLiuyingFavorites(
    feed: String, initialTab: String, onClose: () -> Unit, onAll: (String) -> Unit,
    onSelect: (String, LiuYingPageWindow, Int) -> Unit,
) {
    val app = LocalApp.current
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
        PhoneLiuyingSheet(if (feed == "normal") "收藏的流影" else "卍解收藏", "liuying.favoriteSheet", onClose) {
            if (feed == "normal") Row(Modifier.fillMaxWidth()) {
                for ((value, title) in listOf("dance" to "舞蹈", "live" to "直播")) {
                    Text(title, Modifier.weight(1f).heightIn(min = 48.dp).wrapContentHeight()
                        .testTag("liuying.favoriteTab.$value").semantics { selected = tab == value }
                        .pressable({ tab = value }), color = if (tab == value) Color.White else Color.Gray,
                        style = LpText.list)
                }
            }
            Text("全部频道与衣着 · 点选后只在收藏范围内播放",
                color = Color.White.copy(alpha = .7f), style = LpText.caption)
            Text("返回全部视频", Modifier.fillMaxWidth().heightIn(min = 48.dp).wrapContentHeight()
                .testTag("liuying.returnAll").pressable({ onAll(tab) }),
                color = Color.White, style = LpText.body)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("liuying.favoriteList"),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                for (page in pages) items(page.rows.size, key = { "${page.offset}:$it" }) { index ->
                    val row = page.rows[index]
                    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .testTag("liuying.favoriteItem.${row.str("item_id")}")
                        .pressable({ onSelect(tab, page, index) }).padding(vertical = 12.dp)) {
                        Text(row.str("title").orEmpty(), color = Color.White, style = LpText.body,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        row.str("author")?.takeIf { it.isNotBlank() }?.let {
                            Text("@${it.removePrefix("@")}", color = Color.White.copy(alpha = .7f), style = LpText.caption)
                        }
                    }
                }
                item {
                    when {
                        loading -> Text("正在加载收藏…", color = Color.White, style = LpText.body)
                        failure != null -> {
                            Text(failure!!, color = Color.White, style = LpText.body)
                            Text("重试", Modifier.heightIn(min = 48.dp).wrapContentHeight()
                                .testTag("liuying.favoriteRetry").pressable({ retry++ }), color = Color.White)
                        }
                        pages.all { it.rows.isEmpty() } -> Text("暂无收藏视频", color = Color.White, style = LpText.body)
                        pages.lastOrNull()?.more == true -> Text("加载更多",
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).wrapContentHeight()
                                .testTag("liuying.favoriteMore").pressable({
                                    offset = pages.last().let { it.offset + it.rows.size }
                                }), color = Color.White, style = LpText.body)
                    }
                }
            }
        }
    }
}
