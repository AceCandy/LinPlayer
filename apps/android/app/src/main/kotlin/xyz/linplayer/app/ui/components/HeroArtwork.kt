package xyz.linplayer.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage

/** 轮播先呈现缓存，当前可见项再取原图；失败保留缓存，解码尺寸由实际布局决定。 */
@Composable
internal fun HeroArtwork(
    originalUrl: String?, previewUrl: String?, fallbackUrl: String?,
    modifier: Modifier = Modifier, loadOriginal: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    var ready by remember(originalUrl) { mutableStateOf(false) }
    var failed by remember(originalUrl) { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = loadOriginal && visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    Box(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0f && bounds.height > 0f
    }) {
        if (!ready && previewUrl != null && previewUrl != originalUrl)
            AsyncImage(previewUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (active || ready) AsyncImage(if (failed) fallbackUrl else originalUrl, null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { ready = true }, onError = { failed = true })
    }
}
