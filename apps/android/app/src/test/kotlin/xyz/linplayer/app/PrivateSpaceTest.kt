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
            assertEquals(JsonPrimitive(false), core.calls.last().second?.get("request_scope")?.jsonObject?.get("filter"))
            app.enablePrivacyFiltering()
            val normalRevision = app.spaceRevision.value
            app.call("emby.listResume")
            assertEquals(JsonPrimitive(false), core.calls.last().second?.get("request_scope")?.jsonObject?.get("private"))
            app.enterPrivateSpace()
            assertTrue(app.privateSpace.value)
            val menu = xyz.linplayer.app.ui.pages.cardActions(app, scope,
                Item.from(buildJsonObject { put("id", "film"); put("name", "作品") })!!)
            assertFalse(menu.any { it.label == "下载" })
            assertTrue(app.spaceRevision.value > normalRevision)
            app.call("emby.listResume")
            assertEquals(JsonPrimitive(true), core.calls.last().second?.get("request_scope")?.jsonObject?.get("private"))
            core.ret("emby.currentSession", buildJsonObject {
                put("server", "https://other.invalid"); put("user_id", "u"); put("token", "t")
            })
            app.refreshSession()
            assertFalse(app.privateSpace.value)
            assertFalse(app.privateSpace.value)
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }

    @Test fun privateScopePreservesIdentityAndPropagatesCorePolicyErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore().loggedIn().apply { ret("emby.views", libraries()) }
        val app = AppState(core, scope)
        try {
            app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace()
            core.on("emby.search") { args ->
                val request = args!!.getValue("request_scope").jsonObject
                assertEquals(JsonPrimitive(true), request["private"])
                assertEquals(JsonPrimitive("http://emby-a.invalid"), request["server"])
                assertEquals(JsonPrimitive("u"), request["user_id"])
                assertEquals(JsonPrimitive("other"), args["server"])
                throw xyz.linplayer.app.core.CoreException("E_UNSUPPORTED", "隐私空间仅支持当前服务器", false)
            }
            try {
                app.call("emby.search", buildJsonObject { put("server", "other") })
                fail("核心拒绝未透传")
            } catch (e: xyz.linplayer.app.core.CoreException) { assertEquals("E_UNSUPPORTED", e.code) }
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
            app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace()
            var partials = 0
            val request = async { app.call("emby.listResume") { partials++ } }
            started.await()
            app.leavePrivateSpace()
            release.complete(Unit)
            try { request.await(); fail("迟到隐私结果未被拒绝") } catch (_: CancellationException) { }
            assertEquals(0, partials)
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }

    @Test fun oldServerCannotOpenPrivateSpaceAndOldWritesCannotCrossNamespaces() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", buildJsonArray { add(buildJsonObject { put("id", "legacy") }) })
        }
        val app = AppState(core, scope)
        try {
            app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace()
            assertFalse(app.privateSpace.value)
        } finally { scope.cancel(); app.bg.cancel(); PageCache.clear() }
    }
}
