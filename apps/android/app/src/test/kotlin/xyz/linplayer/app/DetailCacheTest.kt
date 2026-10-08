package xyz.linplayer.app

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.DetailCache
import xyz.linplayer.app.data.str

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = android.app.Application::class)
class DetailCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private fun metadata(id: String = "m1") = buildJsonObject {
        put("id", id); put("name", "缓存片名"); put("overview", "缓存简介"); put("rating", 8.1)
        put("resume_secs", 900); put("played", true); put("is_favorite", true)
        put("token", "fixture-only"); put("url", "https://example.invalid/video")
        put("children", JsonArray(listOf(buildJsonObject { put("resume_secs", 300) })))
        put("people", JsonArray(listOf(buildJsonObject {
            put("id", "p1"); put("name", "演员"); put("role", "角色"); put("token", "fixture-only")
        })))
    }

    @Test fun restartRestoresOnlyDisplayFieldsAndSeparatesAccountsAndItems() = runBlocking {
        val dir = temp.newFolder()
        val cache = DetailCache(dir)
        val key = cache.key("server-a", "user-a", "m1")
        cache.put(key, metadata(), cache.generation)
        val next = DetailCache(dir)
        val got = next.load(key, next.generation)!!
        assertEquals("缓存简介", got.str("overview"))
        assertEquals("角色", got["people"]!!.jsonArray.single().jsonObject.str("role"))
        listOf("resume_secs", "played", "is_favorite", "token", "url", "children").forEach {
            assertFalse("must not persist $it", File(dir, key).readText().contains(it))
        }
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertNull(next.load(next.key("server-b", "user-a", "m1"), next.generation))
        assertNull(next.load(next.key("server-a", "user-b", "m1"), next.generation))
        assertNull(next.load(next.key("server-a", "user-a", "m2"), next.generation))
    }

    @Test fun leastRecentlyReadEntryIsEvictedAndOversizeEntryIsNotStored() = runBlocking {
        val dir = temp.newFolder()
        val cache = DetailCache(dir)
        fun key(n: Int) = cache.key("s", "u", "m$n")
        repeat(64) { n ->
            cache.put(key(n), metadata("m$n"), cache.generation)
            File(dir, key(n)).setLastModified(1000L + n)
        }
        cache.load(key(0), cache.generation)
        cache.put(key(64), metadata("m64"), cache.generation)
        assertTrue(File(dir, key(0)).exists())
        assertFalse(File(dir, key(1)).exists())
        assertTrue(dir.listFiles()!!.size <= 64)
        assertTrue(cache.sizeBytes() <= 64L * 128 * 1024)
        cache.put(key(65), JsonObject(metadata("m65") + ("overview" to JsonPrimitive("a".repeat(128 * 1024)))), cache.generation)
        assertNull(cache.peek(key(65)))
        assertFalse(File(dir, key(65)).exists())
    }

    @Test fun corruptAndIncompleteCacheFallsBackToNetwork() = runBlocking {
        val dir = temp.newFolder()
        val cache = DetailCache(dir)
        val key = cache.key("s", "u", "m1")
        listOf("broken", "{}", "[]").forEach {
            File(dir, key).writeText(it)
            assertNull(cache.load(key, cache.generation))
            assertFalse(File(dir, key).exists())
        }
    }

    @Test fun clearRejectsOldRequestsButAllowsNewRefresh() = runBlocking {
        val dir = temp.newFolder()
        val cache = DetailCache(dir)
        val key = cache.key("s", "u", "m1")
        val old = cache.generation
        cache.put(key, metadata(), old)
        cache.clear()
        cache.put(key, metadata(), old)
        assertNull(cache.peek(key))
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.load(key, old))
        cache.put(key, metadata(), cache.generation)
        assertEquals("缓存片名", cache.peek(key)?.str("name"))
        cache.remove(key, cache.generation)
        assertNull(cache.peek(key))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun diskUnavailableStillKeepsMemoryPreview() = runBlocking {
        val cache = DetailCache(temp.newFile())
        val key = cache.key("s", "u", "m1")
        cache.put(key, metadata(), cache.generation)
        assertEquals("缓存片名", cache.peek(key)?.str("name"))
    }
}
