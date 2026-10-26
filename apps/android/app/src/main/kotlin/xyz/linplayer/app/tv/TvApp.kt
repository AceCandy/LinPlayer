package xyz.linplayer.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import androidx.compose.ui.graphics.compositeOver
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.LocalTvType
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvToast
import xyz.linplayer.app.tv.kit.TypeB
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.theme.LpTheme

/**
 * TV 根(UI_TV.md §7.0)。和手机根共用同一个 [AppState],浏览页走共享的 `PhoneRoot(tv = true)`。
 */
@Composable
fun TvRoot(app: AppState) {
    val input = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(input) { input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
    val base = LocalViewConfiguration.current
    val tvConfig = remember(base) {
        object : ViewConfiguration by base { override val longPressTimeoutMillis = 600L }
    }
    LaunchedEffect(app) { runCatching { app.call("companion.start") } }
    TvFrame(app, tvConfig) { xyz.linplayer.app.PhoneRoot(app, tv = true) }
}

/** 主题 + 字阶 + Toast。拆出来是给 JVM 出图 / 焦点测试直接挂真页面用的。 */
@Composable
fun TvFrame(app: AppState, config: ViewConfiguration = LocalViewConfiguration.current, content: @Composable () -> Unit) {
    val privateSpace = app.privateSpace.collectAsStateWithLifecycle().value
    SideEffect { TvC.privateMode = privateSpace }
    DisposableEffect(app) { onDispose { TvC.privateMode = false } }
    /* 插件 UI 在 TV 上必须整个 shell 都走可聚焦的那一套。只在插件整页上给的话,
       锚点块(详情页 / 设置页)画出来的是手机控件 —— 它们**没有 FocusTarget**,
       遥控器的方向键把它们当不存在直接跳过,而截图上按钮画得好好的。 */
    CompositionLocalProvider(
        LocalApp provides app, LocalTvType provides TypeB, LocalViewConfiguration provides config,
        xyz.linplayer.app.ui.plugin.LocalPluginTv provides true,
    ) {
        if (!privateSpace) xyz.linplayer.app.ui.plugin.LoadTakeovers()
        LpTheme(
            darkOverride = when (xyz.linplayer.app.data.UiPrefs.theme.value) {
                "dark" -> true
                "light" -> false
                else -> xyz.linplayer.app.ui.theme.PluginTheme.forcedDark
            },
            color = xyz.linplayer.app.data.UiPrefs.colorScheme.value,
            privateSpace = privateSpace,
        ) {
            val colors = xyz.linplayer.app.ui.theme.Lp.colors
            // 配色是进程级设备偏好；播放窗口退出不能清掉仍存活的浏览窗口配色。
            SideEffect {
                TvC.sharedColors = if (xyz.linplayer.app.ui.theme.PluginTheme.activeName.isEmpty()) colors else null
            }
            MaterialTheme(colorScheme = (if (colors.isDark) darkColorScheme() else lightColorScheme()).copy(
                background = colors.bg, surface = colors.s2.compositeOver(colors.bg), onSurface = colors.fg,
                primary = colors.acc, onPrimary = colors.accFg,
            )) {
                Box(Modifier.fillMaxSize().background(
                    xyz.linplayer.app.ui.plugin.pageBg(colors.bg))) {
                    // 壁纸垫在最底下(SPEC 11.5):它在内容之前组合,所以永远画在内容下面
                    if (!privateSpace) xyz.linplayer.app.ui.plugin.WallpaperLayer()
                    content()
                    // 对话框排在 content 后面:它的 BackHandler 组合得更晚,返回键先给它(§3.4)
                    if (!privateSpace) TvPluginDialog()
                    TvToastHost()
                    xyz.linplayer.app.ui.theme.ThemeFailedToast()
                }
            }
        }
    }
}

/** Toast(§5.5):底部居中,3 秒,**不抢焦点**,连续两条排队不叠放。 */
@Composable
private fun TvToastHost() {
    val app = LocalApp.current
    var cur by remember { mutableStateOf<xyz.linplayer.app.data.Toast?>(null) }
    LaunchedEffect(Unit) {
        app.toasts.collect {
            cur = it
            delay(3000)
            cur = null
        }
    }
    val t = cur ?: return
    Box(Modifier.fillMaxSize()) {
        TvToast(t.text, when (t.kind) { ToastKind.Ok -> TvC.ok; ToastKind.Error -> TvC.bad; ToastKind.Info -> TvC.warn })
    }
}

/**
 * 手机遥控的电视这一侧(§9.4)。
 *
 * ★ 按键注入走**和真遥控器同一个入口**:方向 / 确认 / 菜单合成真 KeyEvent 交给 Activity,
 *   返回走 OnBackPressedDispatcher(和遥控器返回键落到的是同一组 BackHandler)。不另写一套。
 */
@Composable
internal fun CompanionBridge(onHome: () -> Unit, onOpen: suspend (JsonObject?) -> Unit) {
    val app = LocalApp.current
    val activity = LocalContext.current as? ComponentActivity
    val home by androidx.compose.runtime.rememberUpdatedState(onHome)
    val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(app, activity) {
        app.core.events.collect { ev ->
            if (!owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@collect
            val d = ev.data.obj()
            when (ev.name) {
                "companion.key" -> when (val k = d.str("key")) {
                    "back" -> activity?.onBackPressedDispatcher?.onBackPressed()
                    "home" -> home()
                    else -> keyCodeOf(k)?.let { code ->
                        val t = SystemClock.uptimeMillis()
                        activity?.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                        activity?.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
                    }
                }
                "account.status" -> app.refreshSession()
                "companion.open" -> open(d)
            }
        }
    }
}

private fun keyCodeOf(k: String?): Int? = when (k) {
    "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
    "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
    "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "menu" -> KeyEvent.KEYCODE_MENU
    "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    else -> null
}

/**
 * 需要时切到另一台服务器。**成功后**才返回 true —— 不先切服就进详情页 =
 * 拿当前服的 token 去问不存在的 id =「点进去是空白页」(§7.7)。
 */
suspend fun switchServerIfNeeded(app: AppState, serverId: String): Boolean {
    val accounts = xyz.linplayer.app.data.Account.list(runCatching { app.call("account.listAccounts") }.getOrNull())
    val target = accounts.firstOrNull { it.id == serverId } ?: return false
    if (target.isActive) return true
    return runCatching { app.call("account.setActiveServer", args("server_id" to serverId)) }
        .onSuccess { app.refreshSession(); app.toast("已切换到 ${target.name}", ToastKind.Ok) }
        .onFailure { app.report(it) }.isSuccess
}
