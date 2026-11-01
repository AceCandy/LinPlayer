package xyz.linplayer.app.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.args

/** 共用浏览栈的播放交接；TV仍使用遥控器OSD和停止屏障。 */
@Composable
internal fun TvPlaybackHost(
    app: AppState, route: Route.Player,
    onExit: (String?) -> Unit, onNavigate: (String, JsonObject) -> Unit,
) {
    val target = remember(route) {
        TvRoute.Player(route.itemId, route.title, versionId = route.versionId, engine = route.engine,
            fromStart = route.fromStart, subtitle = route.subtitle)
    }
    val nav = remember { TvNav().apply { push(target) } }
    TvFrame(app) {
        CompanionBridge(onHome = { onNavigate("home", args()) }, onOpen = { data ->
            if (!app.privateSpace.value) {
                val id = data.str("item_id")
                val server = data.str("server_id")
                if (id != null && (server.isNullOrBlank() || switchServerIfNeeded(app, server)))
                    onNavigate("detail", args("id" to id, "type" to (data.str("type") ?: "Movie")))
            }
        })
        CompositionLocalProvider(LocalNav provides nav, LocalFocusMemory provides nav.top.memory) {
            TvPlayerPage(target, onExit)
        }
    }
}
