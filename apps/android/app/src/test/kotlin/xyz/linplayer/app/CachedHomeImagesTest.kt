package xyz.linplayer.app

import android.app.Application
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.cachedHomeImages
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn

/** 缓存轮播候选只走本机只读路由，不把未缓存图片补成远程请求。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CachedHomeImagesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var server: HttpServer? = null
    private val requests = CopyOnWriteArrayList<String>()
    @After fun clean() { server?.stop(0); scope.cancel() }

    private fun app(candidates: List<String>? = null, disconnect: (String) -> Boolean = { false }, hit: (String) -> Boolean): AppState {
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = local
        local.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests.add(path)
            assertEquals("/img-cache", exchange.requestURI.path)
            assertEquals("HEAD", exchange.requestMethod)
            assertEquals("fixture", exchange.requestHeaders.getFirst("X-LP-Token"))
            val src = Uri.parse(path).getQueryParameter("src").orEmpty()
            if (disconnect(src)) { exchange.close(); return@createContext }
            val found = hit(src)
            if (found) exchange.responseHeaders.set("X-LP-Cache", "hit")
            exchange.sendResponseHeaders(if (found) 200 else 404, -1)
            exchange.close()
        }
        local.start()
        val fake = FakeCore().loggedIn()
        candidates?.let { ids -> fake.ret("emby.heroCandidates", JsonArray(ids.map(::JsonPrimitive))) }
        return AppState(object : CorePort by fake {
            override val localBaseUrl = "http://127.0.0.1:${local.address.port}"
            override val localToken = "fixture"
        }, scope).also { runBlocking { it.boot() } }
    }

    @Test fun cachedBackgroundsArePreferredAndItemsAreNotDuplicated() = runBlocking {
        val app = app(candidates = listOf("a", "b")) { it.contains("/Items/a/Images/") || it.contains("/Items/b/Images/Primary") }
        val images = app.cachedHomeImages(listOf(Item("a", "背景", "Movie"), Item("b", "海报", "Series"),
            Item("a", "重复", "Movie"), Item("person", "人物", "Person")))
        assertEquals(2, images.size)
        assertEquals("a", images[0].item.id)
        assertTrue(images[0].backdrop)
        assertFalse(images[1].backdrop)
        assertTrue(images.all { Uri.parse(it.url).path == "/img-cache" })
        assertTrue("已有原图缓存优先，不能强制1080",
            images.all { Uri.parse(it.url).getQueryParameter("h") == null })
        assertFalse(requests.any { it.contains("person") })
    }

    @Test fun missingCacheKeepsCandidateWithoutDownloadingDuringScan() = runBlocking {
        val app = app { false }
        assertEquals("a", app.cachedHomeImages(listOf(Item("a", "未缓存", "Movie"))).single().item.id)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.startsWith("/img-cache?") })
    }

    @Test fun originalImageUrlHasNoResizeOrQualityParameters() = runBlocking {
        val app = app { false }
        val url = Uri.parse(app.imageUrl("a", "Backdrop", original = true))
        assertEquals("/img", url.path)
        assertEquals(setOf("src"), url.queryParameterNames)
        val upstream = Uri.parse(url.getQueryParameter("src"))
        assertTrue(upstream.path!!.endsWith("/Items/a/Images/Backdrop/0"))
        assertNull(upstream.query)
        val normal = Uri.parse(app.imageUrl("a", "Backdrop", 720))
        assertEquals("720", normal.getQueryParameter("h"))
        assertEquals("90", Uri.parse(normal.getQueryParameter("src")).getQueryParameter("quality"))
    }

    @Test fun originalMissStillUsesExistingThumbnailAsPreview() = runBlocking {
        val app = app { it.contains("quality=90") }
        val image = app.cachedHomeImages(listOf(Item("a", "旧缓存", "Movie"))).single()
        assertEquals("1080", Uri.parse(image.url).getQueryParameter("h"))
        assertTrue(requests.all { it.startsWith("/img-cache?") })
    }

    @Test fun entirePoolBeyondTwentyFourIsAvailableWithoutSixImageLimit() = runBlocking {
        val app = app { true }
        val items = (1..35).map { Item("film-$it", "作品$it", "Movie") }
        assertEquals(items.map { it.id }, app.cachedHomeImages(items).map { it.item.id })
        assertEquals(35, requests.size)
    }

    @Test fun watchedAndResumingItemsAlsoExcludeTheirSeries() = runBlocking {
        val app = app(candidates = listOf("fresh")) { true }
        val images = app.cachedHomeImages(listOf(
            Item("seen", "已看电影", "Movie", played = true),
            Item("partial", "续播电影", "Movie", resumeSecs = 60.0),
            Item("series", "正在看剧", "Series"),
            Item("episode", "正在看集", "Episode", seriesId = "series", resumeSecs = 20.0),
            Item("fresh", "未看电影", "Movie"),
        ))
        assertEquals(listOf("fresh"), images.map { it.item.id })
        assertFalse(requests.any { it.contains("/Items/series/") })
    }

    @Test fun multipleUnwatchedEpisodesDoNotRepeatTheSameWork() = runBlocking {
        val app = app(candidates = listOf("series")) { true }
        val images = app.cachedHomeImages(listOf(
            Item("episode1", "第一集", "Episode", seriesId = "series"),
            Item("episode2", "第二集", "Episode", seriesId = "series"),
            Item("series", "整剧", "Series"),
        ))
        assertEquals(listOf("series"), images.map { it.item.id })
    }

    @Test fun oneInterruptedProbeDoesNotDiscardLaterCachedWorks() = runBlocking {
        val app = app(disconnect = { it.contains("/Items/film-1/") }) { true }
        val images = app.cachedHomeImages((1..35).map { Item("film-$it", "作品$it", "Movie") })
        assertEquals((1..35).map { "film-$it" }, images.map { it.item.id })
    }
}
