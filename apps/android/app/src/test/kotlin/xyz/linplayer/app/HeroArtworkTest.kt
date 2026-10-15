package xyz.linplayer.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.components.HeroArtwork
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class HeroArtworkTest {
    @get:Rule val rule = createComposeRule()
    private val requested = CopyOnWriteArrayList<String>()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun loader(fetch: suspend (String) -> Int) {
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(
            ApplicationProvider.getApplicationContext<android.content.Context>())
            .coroutineContext(Dispatchers.Unconfined)
            .components { add(coil3.fetch.Fetcher.Factory<coil3.Uri> { data, _, _ ->
                coil3.fetch.Fetcher {
                    val url = data.toString()
                    requested += url
                    val color = fetch(url)
                    coil3.fetch.ImageFetchResult(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(color) }.asImage(), false, coil3.decode.DataSource.NETWORK)
                }
            }) }.build())
    }

    private fun green() = rule.onRoot().captureToImage().toPixelMap()[2, 2].let {
        it.green > .9f && it.red < .1f && it.blue < .1f
    }
    private fun blue() = rule.onRoot().captureToImage().toPixelMap()[2, 2].let {
        it.blue > .9f && it.red < .1f && it.green < .1f
    }

    @Test fun cachedPreviewStaysVisibleUntilOriginalArrives() {
        val original = CompletableDeferred<Unit>()
        loader { if (it == "fake:original") { original.await(); android.graphics.Color.BLUE }
            else android.graphics.Color.GREEN }
        rule.setContent { Box(Modifier.fillMaxSize()) {
            HeroArtwork("fake:original", "fake:cache", "fake:primary", Modifier.fillMaxSize())
        } }
        rule.waitUntil(5000) { "fake:original" in requested }
        assertTrue("等待原图时必须显示缓存", green())
        rule.runOnIdle { original.complete(Unit) }
        rule.waitUntil(5000) { blue() }
        assertFalse("原图成功后应替换预览", green())
    }

    @Test fun failedOriginalAndPrimaryKeepCachedPreview() {
        loader { if (it != "fake:cache") error("图片不可用") else android.graphics.Color.GREEN }
        rule.setContent { HeroArtwork("fake:original", "fake:cache", "fake:primary", Modifier.fillMaxSize()) }
        rule.waitUntil(5000) { "fake:primary" in requested }
        assertTrue("回源失败不能清掉缓存", green())
        assertEquals(1, requested.count { it == "fake:original" })
        assertEquals(1, requested.count { it == "fake:primary" })
    }

    @Test fun onlyActiveArtworkDownloadsAndOldResultCannotReplaceNewItem() {
        var active by mutableStateOf(false)
        var url by mutableStateOf("fake:old")
        val old = CompletableDeferred<Unit>()
        val oldCompleted = CompletableDeferred<Unit>()
        loader {
            when (it) {
                "fake:old" -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    old.await(); oldCompleted.complete(Unit); android.graphics.Color.RED
                }
                "fake:new" -> android.graphics.Color.BLUE
                else -> android.graphics.Color.GREEN
            }
        }
        rule.setContent { HeroArtwork(url, "fake:cache", null, Modifier.fillMaxSize(), active) }
        rule.waitForIdle()
        assertEquals(listOf("fake:cache"), requested.toList())
        rule.runOnIdle { active = true }
        rule.waitForIdle()
        rule.waitUntil(5000) { "fake:old" in requested }
        rule.runOnIdle { url = "fake:new" }
        rule.waitForIdle()
        rule.waitUntil(5000) { "fake:new" in requested }
        rule.waitForIdle()
        assertTrue(blue())
        rule.runOnIdle { old.complete(Unit) }
        rule.waitUntil(5000) { oldCompleted.isCompleted }
        rule.waitForIdle()
        assertTrue("旧图晚到不能覆盖新图", blue())
    }
}
