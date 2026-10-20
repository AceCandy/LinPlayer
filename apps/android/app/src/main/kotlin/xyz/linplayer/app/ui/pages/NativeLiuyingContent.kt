package xyz.linplayer.app.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.theme.*
import xyz.linplayer.app.tv.LocalNav
import xyz.linplayer.app.tv.TvNav
import xyz.linplayer.app.tv.TvPlayerPage
import xyz.linplayer.app.tv.TvRoute

/** 候选由服务端限定入库资源；本页只持有当前有界窗口，不保存播放地址或断点。 */
@Composable
internal fun NativeLiuyingContent(onExit: () -> Unit, tv: Boolean) {
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
    val current = rows.getOrNull(index)

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
        seed = java.util.UUID.randomUUID().toString(); revision++
    }
    fun next() {
        if (loading) return
        if (index + 1 < rows.size) index++
        else {
            offset = if (more) offset + rows.size else 0
            if (!more) seed = java.util.UUID.randomUUID().toString()
            rows = emptyList(); index = 0; revision++
        }
    }
    val capabilityReady = capability == true
    LaunchedEffect(session, privateSpace, capabilityReady, revision) {
        if (!privateSpace || !capabilityReady) return@LaunchedEffect
        loading = true; failure = null
        try {
            val result = app.call("emby.extLiuYingItems", args("feed" to feed, "tab" to tab,
                "channel" to channel, "exposure" to exposure, "favorite" to favorites,
                "seed" to seed, "offset" to offset)).obj()
            coroutineContext.ensureActive()
            rows = result?.get("items").arr().mapNotNull { it.obj() }
            index = 0; more = result.bool("has_more")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "读取流影失败" }
        finally { loading = false }
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
                rows = rows.map { if (it.str("item_id") == id) JsonObject(it + args("favorite" to want)) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { app.report(error) }
            finally { favoriteBusy = false }
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().testTag("liuying.native")) {
        LazyRow(Modifier.fillMaxWidth().padding(Sp.x8).testTag("liuying.controls"), horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
            item { LpButton("返回", onExit, kind = BtnKind.Ghost) }
            item { LpButton(if (feed == "normal") "普通 / 切换18+" else "18+ / 切换普通", {
                feed = if (feed == "normal") "adult" else "normal"; tab = if (feed == "normal") "dance" else ""
                channel = ""; exposure = ""; reset()
            }, m = Modifier.testTag("liuying.bankai"), enabled = capability == true) }
            if (feed == "normal") item { LpButton(if (tab == "dance") "舞蹈 / 切换直播" else "直播 / 切换舞蹈", {
                tab = if (tab == "dance") "live" else "dance"; channel = ""; exposure = ""; reset()
            }, kind = BtnKind.Secondary, enabled = capability == true) }
            item { LpButton(if (favorites) "只看收藏" else "全部作品", { favorites = !favorites; reset() }, kind = BtnKind.Secondary, enabled = capability == true) }
            item { LpButton(if (current.bool("favorite")) "取消收藏" else "收藏", ::favorite, kind = BtnKind.Secondary, enabled = current != null && !favoriteBusy) }
            item { LpButton("换一个", ::next, enabled = current != null && !loading) }
        }
        if (feed == "normal" && tab == "dance" && capability == true) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = Sp.x8), horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                item { LpButton("全部标签", { channel = ""; exposure = ""; reset() }, kind = BtnKind.Ghost) }
                items(labels, key = { it.str("name").orEmpty() }) { label ->
                    val name = label.str("name").orEmpty()
                    val selected = channel == name || exposure == name
                    LpButton(label.str("title") ?: name, {
                        if (label.str("kind") == "channel") { channel = if (channel == name) "" else name; exposure = "" }
                        else exposure = if (exposure == name) "" else name
                        reset()
                    }, kind = if (selected) BtnKind.Primary else BtnKind.Ghost)
                }
            }
        }
        if (current != null) Text(listOfNotNull(current.str("title"), current.str("author")).joinToString(" · "),
            Modifier.padding(Sp.x8), color = Lp.colors.fg, style = LpText.list)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                capabilityError != null -> EmptyState("流影暂不可用", capabilityError!!, LpIcons.globe)
                capability == false -> EmptyState("服务器未提供流影能力", "请刷新普通首页更新能力，或检查账号权限", LpIcons.globe)
                capability == null || loading -> LoadingLabel("正在加载…")
                failure != null -> ErrorState(failure!!, { revision++ })
                current == null -> EmptyState("暂无可播放的入库资源", "可以切换来源、分类或标签；资料中心内容不会直接播放", LpIcons.globe)
                else -> key(session, seed, offset, index, revision) {
                    val id = current.str("item_id").orEmpty()
                    val title = current.str("title").orEmpty()
                    if (tv) CompositionLocalProvider(LocalNav provides tvNav) {
                        TvPlayerPage(TvRoute.Player(id, title, fromStart = true), onExit = { onExit() }, onCompleted = ::next)
                    } else PlayerPage(nav, null, onExit = { onExit() },
                        embeddedRoute = Route.Player(id, title, fromStart = true), onCompleted = ::next)
                }
            }
        }
    }
}
