package xyz.linplayer.app

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.FakeCore

/** 事件线程的缓存快照必须先于实时结果交付，快速失败也不能吞快照。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DisplayCacheTest {
    private val old = buildJsonArray { add(buildJsonObject { put("id", "old") }) }
    private val fresh = buildJsonArray { add(buildJsonObject { put("id", "new") }) }

    private fun app(scope: CoroutineScope, fail: String? = null, hold: CompletableDeferred<JsonElement>? = null) =
        AppState(object : CorePort by FakeCore() {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                onPartial?.invoke(old)
                if (fail != null) throw CoreException(fail, "刷新失败", true)
                return hold?.await() ?: fresh
            }
        }, scope)

    @Test fun immediateResultStillDeliversOldBeforeFresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val app = app(scope)
        try {
            val seen = mutableListOf<JsonElement>()
            val result = app.displayBlock("emby.search") { seen += it }
            assertEquals(listOf(old), seen)
            assertEquals(fresh, result.valueOrNull)
        } finally { scope.cancel(); app.bg.cancel() }
    }

    @Test fun immediateNetworkFailureKeepsCachedDisplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val app = app(scope, "E_NETWORK")
        try {
            var shown: JsonElement? = null
            val result = app.displayBlock("emby.search") { shown = it }
            assertEquals(old, shown)
            assertEquals(old, result.valueOrNull)
        } finally { scope.cancel(); app.bg.cancel() }
    }

    @Test fun authAndPlaybackDoNotReturnOldAsRealtimeData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val app = app(scope, "E_AUTH")
        try {
            val result = app.displayBlock("emby.itemDetail", retainOnFailure = false) { }
            assertEquals("E_AUTH", (result as Block.Fail).code)
            val playback = app.block("emby.itemMedia")
            assertEquals("E_AUTH", (playback as Block.Fail).code)
        } finally { scope.cancel(); app.bg.cancel() }
    }

    @Test fun episodePreviewDoesNotBecomeConfirmedPrefix() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val app = AppState(object : CorePort by FakeCore() {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                assertEquals("emby.seasonEpisodes", command)
                assertEquals(0L, args.long("start_index"))
                onPartial?.invoke(buildJsonObject { put("items", old); put("total", 1) })
                return buildJsonObject { put("items", fresh); put("total", 1) }
            }
        }, scope)
        try {
            val episodes = app.seasonEpisodes("season", onCached = { items, _ -> assertEquals(listOf("old"), items.map { it.id }) })
            assertEquals(listOf("new"), episodes.map { it.id })
        } finally { scope.cancel(); app.bg.cancel() }
    }

    @Test fun cancellationNeverDeliversLateResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val pending = CompletableDeferred<JsonElement>()
        val app = app(scope, hold = pending)
        try {
            var final: Block<JsonElement>? = null
            val started = CompletableDeferred<Unit>()
            val request = launch { final = app.displayBlock("emby.search") { started.complete(Unit) } }
            started.await()
            request.cancelAndJoin()
            pending.complete(fresh)
            yield()
            assertNull(final)
        } finally { scope.cancel(); app.bg.cancel() }
    }
}
