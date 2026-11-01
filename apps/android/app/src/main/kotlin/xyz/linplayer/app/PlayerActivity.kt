package xyz.linplayer.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.theme.LpTheme

/** 手机播放窗口。方向由入口 Activity 的 Manifest 预设，切集与手动转屏复用当前窗口。 */
open class PlayerActivity : ComponentActivity() {
    private lateinit var app: AppState
    protected open fun playerApp(): AppState = (application as LinPlayerApp).appState
    private val askNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = readRoute(intent) ?: run { finish(); return }
        app = playerApp()
        app.enablePrivacyFiltering()
        val privatePlayback = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        val tv = intent.getBooleanExtra(EXTRA_TV, false)
        var navigationResult = false
        // 隐私播放只在已解锁的进程内恢复；进程回收不能靠旧Intent重新进入。
        if (privatePlayback != app.privateSpace.value) { finish(); return }
        UiPrefs.load(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        setContent {
            LpTheme(color = UiPrefs.colorScheme.value, privateSpace = privatePlayback, darkOverride = when (UiPrefs.theme.value) {
                "dark" -> true; "light" -> false; else -> null
            }) {
                CompositionLocalProvider(LocalApp provides app) {
                    val privateSpace by app.privateSpace.collectAsStateWithLifecycle()
                    LaunchedEffect(privateSpace) {
                        if (privateSpace != privatePlayback) finish()
                    }
                    var ready by remember { mutableStateOf(app.loggedIn.value != null) }
                    LaunchedEffect(Unit) {
                        // 进程恢复时先恢复会话；正常从浏览页进入直接复用当前会话。
                        if (!ready) { app.boot(); ready = true }
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        if (ready && tv) {
                            xyz.linplayer.app.tv.TvPlaybackHost(app, route,
                                onExit = { itemId ->
                                    if (!navigationResult) setResult(RESULT_OK, Intent().putExtra(PLAYED_ITEM, itemId))
                                    finish()
                                },
                                onNavigate = { name, params ->
                                    navigationResult = true
                                    setResult(RESULT_OK, Intent().putExtra(NAV_ROUTE, name)
                                        .putExtra(NAV_PARAMS, params.toString()).putExtra(NAV_REPLACE, true))
                                    finish()
                                })
                        } else if (ready) {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = route) {
                                composable<Route.Player> { entry ->
                                    PlayerPage(nav, entry,
                                        clickedAt = if (savedInstanceState == null && intent.hasExtra(EXTRA_CLICK))
                                            intent.getLongExtra(EXTRA_CLICK, 0) else null,
                                        onExit = { itemId ->
                                            if (!navigationResult) setResult(RESULT_OK, Intent().putExtra(PLAYED_ITEM, itemId))
                                            finish()
                                        })
                                }
                            }
                        }
                        if (!tv) ToastHost()
                    }
                }
            }
        }
    }

    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPlayerPip()
    }

    fun enterPlayerPip(returnToHome: Boolean = false): Boolean {
        if (intent.getBooleanExtra(EXTRA_TV, false) || !::app.isInitialized || Build.VERSION.SDK_INT < 26 || isFinishing ||
            isInPictureInPictureMode || isInMultiWindowMode || !app.wantsPip ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return runCatching {
            val entered = enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9)).build())
            // 手动小窗回到桌面，避免露出浏览窗口的黑色播放交接页；Home 手势无需重复跳转。
            if (entered && returnToHome) startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            entered
        }.getOrDefault(false)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        internal const val PLAYED_ITEM = "player.playedItem"
        internal const val PLAYBACK_RETURN = "player.returnRevision"
        internal const val NAV_ROUTE = "player.navRoute"
        internal const val NAV_PARAMS = "player.navParams"
        internal const val NAV_REPLACE = "player.navReplace"
        private const val EXTRA_ROUTE = "player.route"
        private const val EXTRA_CLICK = "player.clickedAt"
        private const val EXTRA_PRIVATE = "player.privateSpace"
        private const val EXTRA_TV = "player.tv"

        internal fun intent(context: Context, route: Route.Player, clickedAt: Long? = null, privateSpace: Boolean = false, tv: Boolean = false): Intent {
            val target = when {
                tv -> PlayerActivity::class.java
                !route.ar.isFinite() || route.ar <= 0 -> PlayerActivity::class.java
                route.ar > 1 -> PlayerActivity::class.java
                else -> PortraitPlayerActivity::class.java
            }
            return Intent(context, target).putExtra(EXTRA_ROUTE, Json.encodeToString(route))
                .putExtra(EXTRA_PRIVATE, privateSpace).putExtra(EXTRA_TV, tv).apply {
                clickedAt?.let { putExtra(EXTRA_CLICK, it) }
            }
        }

        internal fun readRoute(intent: Intent): Route.Player? = intent.getStringExtra(EXTRA_ROUTE)?.let {
            runCatching { Json.decodeFromString<Route.Player>(it) }.getOrNull()
        }
    }
}

/** 竖片独立入口，共用播放器生命周期与控制。 */
class PortraitPlayerActivity : PlayerActivity()
