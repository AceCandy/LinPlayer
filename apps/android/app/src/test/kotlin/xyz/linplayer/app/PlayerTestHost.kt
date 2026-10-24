package xyz.linplayer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlinx.coroutines.cancel
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.tv.FakeCore

/** 直接组合播放器时也提供正式入口依赖的应用状态，并收尾测试作用域。 */
internal fun ComposeContentTestRule.setPlayerContent(content: @Composable () -> Unit) {
    setContent {
        val scope = rememberCoroutineScope()
        val app = remember { AppState(FakeCore(), scope) }
        DisposableEffect(app) { onDispose { app.bg.cancel() } }
        CompositionLocalProvider(LocalApp provides app, content = content)
    }
}
