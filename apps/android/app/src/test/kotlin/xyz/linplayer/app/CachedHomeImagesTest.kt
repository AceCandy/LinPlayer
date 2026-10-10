package xyz.linplayer.app

import android.app.Application
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
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

    private fun app(hit: (String) -> Boolean): AppState {
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = local
        local.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests.add(path)
            assertEquals("/img-cache", exchange.requestURI.path)
            assertEquals("HEAD", exchange.requestMethod)
            assertEquals("fixture", exchange.requestHeaders.getFirst("X-LP-Token"))
            val src = Uri.parse(path).getQueryParameter("src").orEmpty()
            val found = hit(src)
            if (found) exchange.responseHeaders.set("X-LP-Cache", "hit")
            exchange.sendResponseHeaders(if (found) 200 else 404, -1)
            exchange.close()
        }
        local.start()
        val fake = FakeCore().loggedIn()
        return AppState(object : CorePort by fake {
            override val localBaseUrl = "http://127.0.0.1:${local.address.port}"
            override val localToken = "fixture"
        }, scope).also { runBlocking { it.boot() } }
    }

    @Test fun cachedBackgroundsArePreferredAndItemsAreNotDuplicated() = runBlocking {
        val app = app { it.contains("/Items/a/Images/") || it.contains("/Items/b/Images/Primary") }
        val images = app.cachedHomeImages(listOf(Item("a", "背景", "Movie"), Item("b", "海报", "Series"),
            Item("a", "重复", "Movie"), Item("person", "人物", "Person")))
        assertEquals(2, images.size)
        assertEquals("a", images[0].item.id)
        assertTrue(images[0].backdrop)
        assertFalse(images[1].backdrop)
        assertTrue(images.all { Uri.parse(it.url).path == "/img-cache" })
        assertFalse(requests.any { it.contains("person") })
    }

    @Test fun missingCacheProducesNoBannerAndNoOrdinaryImageRequests() = runBlocking {
        val app = app { false }
        assertTrue(app.cachedHomeImages(listOf(Item("a", "未缓存", "Movie"))).isEmpty())
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.startsWith("/img-cache?") })
    }
}
