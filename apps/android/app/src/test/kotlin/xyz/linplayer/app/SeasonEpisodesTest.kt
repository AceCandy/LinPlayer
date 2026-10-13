package xyz.linplayer.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.long
import xyz.linplayer.app.tv.FakeCore

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonEpisodesTest {
    @Test fun 长季必须翻过首批二百集() = checkPages(200, listOf(0, 200, 400))
    @Test fun 服务端短页仍须按总数续取() = checkPages(150, listOf(0, 150, 300))

    @Test fun 未知总数遇空页才停止() = checkPages(150, listOf(0, 150, 300, 403), knownTotal = false)

    @Test fun 重复首批不能无限翻页() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val core = FakeCore()
        var calls = 0
        core.on("emby.seasonEpisodes") {
            calls++
            check(calls < 4) { "不应一直请求重复页" }
            buildJsonObject { put("items", buildJsonArray {
                add(buildJsonObject { put("id", "one"); put("name", "第一集") })
            }) }
        }
        val app = AppState(core, scope)
        try {
            val result = runCatching { app.seasonEpisodes("season") }
            org.junit.Assert.assertTrue(result.isFailure)
            assertEquals(2, calls)
        } finally { app.bg.cancel(); scope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun 已知季Id时不因缺失季号丢失当前集() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val core = FakeCore().apply {
            ret("emby.seasonEpisodes", buildJsonObject { put("total", 1); put("items", buildJsonArray {
                add(buildJsonObject { put("id", "one"); put("name", "第一集"); put("episode_no", 1) })
            }) })
        }
        val app = AppState(core, scope)
        try {
            val detail = buildJsonObject { put("type_", "Episode"); put("season_id", "season"); put("season_no", 1) }
            assertEquals(listOf("one"), xyz.linplayer.app.ui.player.playbackSeason(app, detail, "one").map { it.id })
        } finally { app.bg.cancel(); scope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun 分页请求超时结束等待并允许再次请求() = kotlinx.coroutines.test.runTest {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val fake = FakeCore()
        var hang = true
        val port = object : xyz.linplayer.app.core.CorePort by fake {
            override suspend fun callJson(command: String, args: kotlinx.serialization.json.JsonObject?, onPartial: ((kotlinx.serialization.json.JsonElement) -> Unit)?): kotlinx.serialization.json.JsonElement {
                if (command == "emby.seasonEpisodes" && hang) kotlinx.coroutines.awaitCancellation()
                return buildJsonObject { put("items", buildJsonArray {}) }
            }
        }
        val app = AppState(port, backgroundScope)
        try {
            val error = runCatching { app.seasonEpisodes("season") }.exceptionOrNull()
            org.junit.Assert.assertTrue(error is xyz.linplayer.app.core.CoreException)
            assertEquals(20_000L, testScheduler.currentTime)
            hang = false
            assertEquals(emptyList<Item>(), app.seasonEpisodes("season"))
        } finally { app.bg.cancel(); Dispatchers.resetMain() }
    }

    private fun checkPages(pageSize: Int, expectedStarts: List<Int>, knownTotal: Boolean = true) = runBlocking {
        val core = FakeCore()
        val starts = mutableListOf<Int>()
        val snapshots = mutableListOf<List<Item>>()
        core.on("emby.seasonEpisodes") { args ->
            val start = (args.long("start_index") ?: 0).toInt()
            if (start > 0) assertEquals(start, snapshots.last().size)
            starts += start
            buildJsonObject {
                if (knownTotal) put("total", 403)
                put("items", buildJsonArray {
                    for (i in start until minOf(start + pageSize, 403)) add(buildJsonObject {
                        put("id", i.toString()); put("name", "第 $i 集"); put("type_", "Episode")
                    })
                })
            }
        }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val app = AppState(core, scope)
            val episodes = app.seasonEpisodes("season") { items, total ->
                snapshots += items
                assertEquals(if (knownTotal) 403L else null, total)
            }
            assertEquals(pageSize, snapshots.first().size) // 后续追加不能改变已发布快照。
            assertEquals(403, episodes.size)
            assertEquals("402", episodes.last().id)
            assertEquals(expectedStarts, starts)
            app.bg.cancel()
        } finally { scope.cancel(); Dispatchers.resetMain() }
    }
}
