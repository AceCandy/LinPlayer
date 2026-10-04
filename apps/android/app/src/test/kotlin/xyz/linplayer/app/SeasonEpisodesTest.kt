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
