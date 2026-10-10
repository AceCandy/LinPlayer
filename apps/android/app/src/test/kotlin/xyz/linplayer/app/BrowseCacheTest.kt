package xyz.linplayer.app

import android.app.Application
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.BrowseCache
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

/** 展示缓存重启复用、身份隔离、白名单及清理回写屏障。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowseCacheTest {
    @Test fun restartReadsOnlyWhitelistedDataAndKeysIsolateIdentityAndQuery() = runBlocking {
        val directory = Files.createTempDirectory("browse-test").toFile()
        try {
            val cache = BrowseCache(directory)
            val key = cache.key("source-a", "user-a", "library-a:sort-a")
            val value = buildJsonObject {
                put("items", buildJsonArray { add(buildJsonObject {
                    put("id", "film-a"); put("name", "缓存作品"); put("type_", "Movie")
                    put("token", "private-test-value"); put("path", "private-test-path")
                    put("year", 2024); put("DoubanRating", 8.6)
                }) })
                put("total", 45); put("credentials", "private-test-value")
            }
            cache.put(key, value, cache.generation)
            val restarted = BrowseCache(directory)
            assertNull(restarted.peek(key))
            val stored = restarted.load(key, restarted.generation).obj()!!
            assertEquals("缓存作品", stored["items"]!!.jsonArray[0].obj().str("name"))
            assertEquals("45", stored["total"].toString())
            assertEquals(8.6, xyz.linplayer.app.data.Item.list(stored).single().doubanRating!!, 0.0)
            assertFalse(stored.toString().contains("private-test"))
            for (other in listOf(cache.key("source-b", "user-a", "library-a:sort-a"),
                cache.key("source-a", "user-b", "library-a:sort-a"),
                cache.key("source-a", "user-a", "library-a:sort-b"))) {
                assertNotEquals(key, other)
                assertNull(restarted.load(other, restarted.generation))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun clearingRejectsOldWritesAndCorruptFilesFallBackToNetwork() = runBlocking {
        val directory = Files.createTempDirectory("browse-test").toFile()
        try {
            val cache = BrowseCache(directory)
            val key = cache.key("source", "user", "views")
            val epoch = cache.generation
            cache.put(key, JsonArray(emptyList()), epoch)
            assertNotNull(cache.peek(key))
            cache.clear()
            cache.put(key, JsonArray(emptyList()), epoch)
            assertNull(cache.peek(key))
            assertEquals(0L, cache.sizeBytes())
            java.io.File(directory, key).writeText("{broken")
            assertNull(cache.load(key, cache.generation))
            assertEquals(0L, cache.sizeBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun diskAndMemoryStayBounded() = runBlocking {
        val directory = Files.createTempDirectory("browse-test").toFile()
        try {
            val cache = BrowseCache(directory)
            for (index in 0..70) cache.put(cache.key("s", "u", "$index"), JsonArray(emptyList()), cache.generation)
            assertTrue(directory.listFiles()!!.size <= 64)
            assertNull(cache.peek(cache.key("s", "u", "0")))
            assertNotNull(cache.peek(cache.key("s", "u", "70")))
        } finally { directory.deleteRecursively() }
    }
}
