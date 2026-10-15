package xyz.linplayer.app

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PrivateSpaceTest {
    private fun libraries() = buildJsonArray {
        add(buildJsonObject { put("id", "normal"); put("is_adult", false) })
        add(buildJsonObject { put("id", "adult"); put("is_adult", true) })
    }

    @Test fun requestModesCacheNamespacesAndAccountReset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", libraries())
            ret("emby.listResume", JsonArray(emptyList()))
        }
        val app = AppState(core, scope)
        try {
            app.boot()
            app.call("emby.listResume")
            assertNull(core.calls.last().second?.get("is_adult"))
            app.enablePhonePrivacy()
            val normalKey = app.browseCache.key("s", "u", "query")
            app.call("emby.listResume")
            assertEquals(JsonPrimitive(false), core.calls.last().second?.get("is_adult"))
            app.enterPrivateSpace()
            assertTrue(app.privateSpace.value)
            val menu = xyz.linplayer.app.ui.pages.cardActions(app, scope,
                Item.from(buildJsonObject { put("id", "film"); put("name", "作品") })!!)
            assertFalse(menu.any { it.label == "下载" })
            assertNotEquals(normalKey, app.browseCache.key("s", "u", "query"))
            app.call("emby.listResume")
            assertEquals(JsonPrimitive(true), core.calls.last().second?.get("is_adult"))
            core.ret("emby.currentSession", buildJsonObject {
                put("server", "https://other.invalid"); put("user_id", "u"); put("token", "t")
            })
            app.refreshSession()
            assertFalse(app.privateSpace.value)
            assertEquals(normalKey, app.browseCache.key("s", "u", "query"))
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }

    @Test fun privateSpaceRejectsLocalHistoryAndCrossServerRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", libraries())
            ret("emby.watchHistoryList", JsonArray(emptyList()))
            ret("source.aggregateSearch", JsonArray(emptyList()))
        }
        val app = AppState(core, scope)
        try {
            app.enablePhonePrivacy(); app.boot(); app.enterPrivateSpace()
            val before = core.calls.size
            for (command in listOf("emby.watchHistoryList", "emby.watchHistoryClear", "emby.aggregateOverview",
                "emby.aggregateSearch", "emby.aggregateVersions", "source.aggregateSearch")) {
                try { app.call(command); fail("私密命令未被拒绝: $command") }
                catch (e: xyz.linplayer.app.core.CoreException) { assertEquals("E_UNSUPPORTED", e.code) }
            }
            try {
                app.call("emby.search", buildJsonObject { put("server", "https://other.invalid") })
                fail("跨服请求未被拒绝")
            } catch (e: xyz.linplayer.app.core.CoreException) { assertEquals("E_UNSUPPORTED", e.code) }
            assertEquals(before, core.calls.size)
            app.leavePrivateSpace()
            app.call("emby.watchHistoryList")
            app.call("source.aggregateSearch")
            assertEquals(before + 2, core.calls.size)
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }

    @Test fun latePrivateResultsAndPartialsCannotReachNormalSpace() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = FakeCore().loggedIn().apply { ret("emby.views", libraries()) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "emby.listResume") return fake.callJson(command, args, onPartial)
                started.complete(Unit); release.await()
                val value = buildJsonArray { add(buildJsonObject { put("id", "private-film") }) }
                onPartial?.invoke(value)
                return value
            }
        }
        val app = AppState(core, scope)
        try {
            app.enablePhonePrivacy(); app.boot(); app.enterPrivateSpace()
            var partials = 0
            val request = async { app.call("emby.listResume") { partials++ } }
            started.await()
            app.leavePrivateSpace()
            release.complete(Unit)
            try { request.await(); fail("迟到隐私结果未被拒绝") } catch (_: CancellationException) { }
            assertEquals(0, partials)
            assertTrue(app.browseCache.heroItems("http://emby-a.invalid", "u", app.browseCache.generation).isEmpty())
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }

    @Test fun oldServerCannotOpenPrivateSpaceAndOldWritesCannotCrossNamespaces() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", buildJsonArray { add(buildJsonObject { put("id", "legacy") }) })
        }
        val app = AppState(core, scope)
        try {
            app.enablePhonePrivacy(); app.boot(); app.enterPrivateSpace()
            assertFalse(app.privateSpace.value)
            val cache = app.browseCache
            cache.setNamespace("adult-v1")
            val epoch = cache.generation
            val privateKey = cache.key("s", "u", "query")
            cache.setNamespace("normal-v1")
            cache.put(privateKey, buildJsonArray { add(buildJsonObject { put("id", "private-film") }) }, epoch)
            assertNull(cache.peek(privateKey))
            assertNotEquals(privateKey, cache.key("s", "u", "query"))
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }
}
