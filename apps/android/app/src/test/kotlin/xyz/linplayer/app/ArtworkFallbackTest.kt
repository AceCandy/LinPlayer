package xyz.linplayer.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.artworkFallbacks
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.MediaCard
import kotlinx.serialization.json.Json
import xyz.linplayer.app.ui.theme.LpTheme
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ArtworkFallbackTest {
    @get:Rule val rule = createComposeRule()
    private val episode = Item("ep", "单集", "Episode", seriesId = "series", seasonId = "season", hasPrimary = true, hasBackdrop = true)
    private fun primary(id: String) = "http://local.test/img?src=${Uri.encode("https://media.test/emby/Items/$id/Images/Primary?quality=90")}&h=330"
    private fun path(url: String) = Uri.parse(Uri.parse(url).getQueryParameter("src")).path!!

    @Test fun candidatesKeepParentOrderServerAndSizeWithoutChangingItem() {
        val urls = artworkFallbacks(primary("ep"), episode)
        assertEquals(listOf("ep/Images/Backdrop/0", "season/Images/Primary", "series/Images/Backdrop/0", "series/Images/Primary"),
            urls.map { path(it).removePrefix("/emby/Items/") })
        urls.forEach {
            assertEquals("330", Uri.parse(it).getQueryParameter("h"))
            assertEquals("media.test", Uri.parse(Uri.parse(it).getQueryParameter("src")).host)
        }
        assertEquals("ep", episode.id)
        assertEquals(listOf("/emby/Items/series/Images/Primary"),
            artworkFallbacks(primary("season"), Item("season", "季", "Season", seriesId = "series")).map(::path))
        assertTrue(artworkFallbacks(primary("series"), Item("series", "剧", "Series", seriesId = "other")).isEmpty())
        assertEquals(1, artworkFallbacks(primary("ep"), episode.copy(seasonId = null, seriesId = null)).size)
        assertTrue(artworkFallbacks("https://plugin.test/poster.jpg", episode).isEmpty())
        assertTrue(artworkFallbacks("fake:poster", episode).isEmpty())
    }

    @Test fun missingOwnArtworkSkipsToUnknownParent() {
        val calls = images("/emby/Items/season/Images/Primary")
        val item = episode.copy(hasPrimary = false, hasBackdrop = false)
        rule.setContent { LpTheme {
            MediaCard(item, primary("ep"), {}, Modifier.size(160.dp))
        } }
        rule.waitUntil(5000) { calls.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf("/emby/Items/season/Images/Primary"), calls.toList())
    }

    @Test fun missingMovieShowsPlaceholderWithoutFetching() {
        val calls = images(null)
        val item = Item.from(Json.parseToJsonElement(
            """{"id":"movie","name":"无图电影","type_":"Movie","has_primary":false}"""))!!
        assertEquals(false, item.hasPrimary)
        assertFalse(item.hasBackdrop)
        rule.setContent { LpTheme { MediaCard(item, primary("movie"), {}, Modifier.size(160.dp)) } }
        rule.waitForIdle()
        rule.onNodeWithText("暂无封面").assertIsDisplayed()
        assertTrue(calls.isEmpty())
        assertNull(Item("synthetic", "未知图片", "Movie").hasPrimary)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun images(success: String?): CopyOnWriteArrayList<String> {
        val calls = CopyOnWriteArrayList<String>()
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).components {
            add(object : Fetcher.Factory<coil3.Uri> {
                override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader) = Fetcher {
                    val p = path(data.toString())
                    calls.add(p)
                    if (p != success) error("fixture missing artwork")
                    ImageFetchResult(Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.NETWORK)
                }
            })
        }.build())
        return calls
    }

    @Test fun failedOwnImagesUseSeasonAndStopBeforeSeries() {
        val calls = images("/emby/Items/season/Images/Primary")
        var result: Boolean? = null
        rule.setContent { LpTheme { NetImage(primary("ep"), null, Modifier.size(160.dp),
            fallbackUrls = artworkFallbacks(primary("ep"), episode), onLoadResult = { result = it }) } }
        rule.waitUntil(5000) { result == true }
        assertEquals(listOf("/emby/Items/ep/Images/Primary", "/emby/Items/ep/Images/Backdrop/0", "/emby/Items/season/Images/Primary"), calls.toList())
    }

    @Test fun allFailuresShowTextAndChangedItemRestartsAtOwnImage() {
        val calls = images(null)
        val item = mutableStateOf(episode)
        var result: Boolean? = null
        rule.setContent { LpTheme { NetImage(primary(item.value.id), null, Modifier.size(160.dp),
            fallbackUrls = artworkFallbacks(primary(item.value.id), item.value),
            onLoadResult = { result = it }, placeholder = { Text("暂无图片") }) } }
        rule.waitUntil(5000) { result == false }
        assertEquals(5, calls.size)
        rule.onNodeWithText("暂无图片").assertIsDisplayed()
        rule.runOnIdle { result = null; item.value = episode.copy(id = "next") }
        rule.waitForIdle()
        rule.waitUntil(5000) { result == false }
        assertEquals("/emby/Items/next/Images/Primary", calls[5])
    }
}
