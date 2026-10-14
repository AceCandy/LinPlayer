package xyz.linplayer.app.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.str
import xyz.linplayer.app.officialRoute
import xyz.linplayer.app.PlayerActivity
import xyz.linplayer.app.ui.Route

/** 所有手机播放路由共用此交接点；返回后弹出交接页，恢复原有浏览栈。 */
@Composable
internal fun PlayerLaunchPage(nav: NavController, entry: NavBackStackEntry) {
    val context = LocalContext.current
    var launched by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(PlayerActivity.PLAYED_ITEM)?.let { itemId ->
            nav.previousBackStackEntry?.savedStateHandle?.let { state ->
                state[PlayerActivity.PLAYED_ITEM] = itemId
                state[PlayerActivity.PLAYBACK_RETURN] = (state.get<Int>(PlayerActivity.PLAYBACK_RETURN) ?: 0) + 1
            }
        }
        val next = result.data?.getStringExtra(PlayerActivity.NAV_ROUTE)?.let { name ->
            val params = result.data?.getStringExtra(PlayerActivity.NAV_PARAMS)?.let {
                Json.decodeFromString<JsonObject>(it)
            }
            officialRoute(name) { params.str(it) }
        }
        if (next != null && result.data?.getBooleanExtra(PlayerActivity.NAV_REPLACE, false) != true) {
            // push保留播放路由，返回时按原路由重新进入；replace与普通退出弹出当前路由。
            launched = false
        } else nav.popBackStack()
        next?.let { nav.navigate(it) }
    }
    LaunchedEffect(entry) {
        if (!launched) {
            launched = true
            launcher.launch(PlayerActivity.intent(context, entry.toRoute<Route.Player>(), PlaybackClickTimes.take(entry)),
                ActivityOptionsCompat.makeCustomAnimation(context, 0, 0))
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black))
}
