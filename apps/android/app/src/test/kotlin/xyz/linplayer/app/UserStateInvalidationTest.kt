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
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.tv.FakeCore

/** 用户状态写入通知只接受成功且仍属于当前会话的结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UserStateInvalidationTest {
    @Test fun subscriptionExistsBeforeDispatcherRunsFirstQueuedTask() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val core = FakeCore().apply { ret("emby.setPlayed", JsonNull) }
        val app = AppState(core, scope)
        try {
            app.call("emby.setPlayed")
            while (pending.isNotEmpty()) pending.removeFirst().run()
            assertEquals(1L, app.userStateRevision.value)
        } finally {
            scope.cancel(); app.bg.cancel()
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    @Test fun successfulWritesInvalidateButFailuresDoNot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore()
        val app = AppState(core, scope)
        val seen = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { app.invalidate.collect { seen += it } }
        try {
            for (command in listOf("emby.setPlayed", "emby.setFavorite", "emby.hideResume")) {
                core.ret(command, JsonNull)
                app.call(command)
            }
            yield()
            assertEquals(listOf("all", "library", "resume"), seen)
            core.on("emby.setPlayed") { throw CoreException("E_NETWORK", "测试失败", true) }
            assertTrue(runCatching { app.call("emby.setPlayed") }.isFailure)
            yield()
            assertEquals(3, seen.size)
            assertEquals(3L, app.userStateRevision.value)
        } finally { collector.cancelAndJoin(); scope.cancel(); app.bg.cancel() }
    }

    @Test fun resultFromPreviousSpaceDoesNotInvalidateCurrentPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JsonElement>()
        val fake = FakeCore()
        val app = AppState(object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                started.complete(Unit)
                val result = response.await()
                fake.events.emit(CoreEvent("data.userState", buildJsonObject {
                    put("id", args!!.getValue("request_scope").jsonObject.getValue("id"))
                    put("scope", "all")
                }))
                return result
            }
        }, scope)
        val seen = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { app.invalidate.collect { seen += it } }
        try {
            val write = launch { app.call("emby.setPlayed") }
            started.await()
            app.enablePrivacyFiltering()
            response.complete(JsonNull)
            write.join()
            yield()
            assertTrue(seen.isEmpty())
            assertEquals(0L, app.userStateRevision.value)
        } finally { response.complete(JsonNull); collector.cancelAndJoin(); scope.cancel(); app.bg.cancel() }
    }
}
