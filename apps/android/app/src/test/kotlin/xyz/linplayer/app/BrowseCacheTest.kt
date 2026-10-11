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
    @Test fun heroPoolCombinesPagesSurvivesRestartAndIsolatesAccount() = runBlocking {
        val directory = Files.createTempDirectory("hero-pool-test").toFile()
        try {
            val cache = BrowseCache(directory)
            for (page in 0..2) cache.recordHeroItems("source", "user", buildJsonArray {
                repeat(30) { offset -> add(buildJsonObject {
                    put("id", "film-${page * 30 + offset}"); put("name", "真实作品"); put("type_", "Movie")
                    put("token", "private-test-value"); put("path", "private-test-path")
                }) }
            }, cache.generation)
            val restarted = BrowseCache(directory)
            assertEquals(90, restarted.heroItems("source", "user", restarted.generation).size)
            assertTrue(restarted.heroItems("source", "another", restarted.generation).isEmpty())
            assertTrue(restarted.heroItems("another", "user", restarted.generation).isEmpty())
            assertFalse(directory.listFiles()!!.any { it.readText().contains("private-test") })
            restarted.clear()
            assertTrue(restarted.heroItems("source", "user", restarted.generation).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun heroPoolUpdatesWatchingStatusAndRejectsWritesAfterClear() = runBlocking {
        val cache = BrowseCache()
        fun movie(played: Boolean) = buildJsonObject {
            put("id", "film"); put("name", "作品"); put("type_", "Movie"); put("played", played)
            put("genres", buildJsonArray { add("动作") })
        }
        cache.recordHeroItems("s", "u", movie(false), cache.generation)
        cache.recordHeroItems("s", "u", buildJsonObject {
            put("id", "film"); put("played", true)
        }, cache.generation)
        assertTrue(cache.heroItems("s", "u", cache.generation).single().played)
        assertEquals(listOf("动作"), cache.heroItems("s", "u", cache.generation).single().genres)
        val epoch = cache.generation
        cache.clear()
        cache.recordHeroItems("s", "u", movie(false), epoch)
        assertTrue(cache.heroItems("s", "u", cache.generation).isEmpty())
    }

    @Test fun heroPoolFitsExistingByteBudgetAndEvictsOldestMetadata() = runBlocking {
        val directory = Files.createTempDirectory("hero-budget-test").toFile()
        try {
            val cache = BrowseCache(directory)
            cache.recordHeroItems("s", "u", buildJsonArray {
                repeat(2000) { index -> add(buildJsonObject {
                    put("id", "film-$index"); put("name", "片名".repeat(150)); put("type_", "Movie")
                }) }
            }, cache.generation)
            assertTrue(cache.sizeBytes() <= 512 * 1024)
            val pool = cache.heroItems("s", "u", cache.generation)
            assertTrue(pool.size > 6)
            assertEquals("film-1999", pool.last().id)
            assertFalse(pool.any { it.id == "film-0" })
        } finally { directory.deleteRecursively() }
    }

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
            for (index in 0..1000) {
                val key = cache.key("s", "u", "$index")
                cache.put(key, JsonArray(emptyList()), cache.generation)
                java.io.File(directory, key).setLastModified(1000L + index)
            }
            assertEquals(1000, directory.listFiles()!!.size)
            assertNotNull(cache.peek(cache.key("s", "u", "1")))
            assertNull(cache.peek(cache.key("s", "u", "0")))
            assertNotNull(cache.peek(cache.key("s", "u", "1000")))
            val restarted = BrowseCache(directory)
            assertNull(restarted.load(cache.key("s", "u", "0"), restarted.generation))
            assertNotNull(restarted.load(cache.key("s", "u", "1"), restarted.generation))
            assertNotNull(restarted.load(cache.key("s", "u", "1000"), restarted.generation))
        } finally { directory.deleteRecursively() }
    }
}
