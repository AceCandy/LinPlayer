package xyz.linplayer.app

import android.app.Application
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.theme.LpTheme
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import xyz.linplayer.app.core.CoreException
import kotlin.coroutines.suspendCoroutine

/** 真详情页回归：长季完整性、切季乱序及离页后的迟到响应。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class SeasonSelectionTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val visible = mutableStateOf(true)
    private lateinit var core: DelayedEpisodes
    private lateinit var phoneNav: NavHostController
    private val overview = "远航归来，故乡已悄然改变。\n多年未见的朋友再次相聚。\n一封迟到的信开启新的旅程。\n他们沿着海岸寻找旧日的约定。\n每个选择都会改变归途。\n故事仍在继续。"

    private class DelayedEpisodes(val fake: FakeCore) : CorePort by fake {
        class Pending(val parent: String, val job: Job, val continuation: Continuation<JsonElement>) {
            fun fail() {
                check(!completed)
                completed = true
                continuation.resumeWithException(CoreException("E_NETWORK", "分页测试失败", true))
            }
            var completed = false
            fun complete(value: JsonElement) {
                check(!completed)
                completed = true
                continuation.resume(value)
            }
        }
        var heldCommand: String? = null
        val pendingActions = mutableListOf<Pending>()
        var hold = false
        val pending = mutableListOf<Pending>()
        val episodeArgs = mutableListOf<JsonObject?>()

        override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
            if (command == heldCommand) {
                val job = currentCoroutineContext()[Job]!!
                return suspendCoroutine { pendingActions += Pending(command, job, it) }
            }
            if (command == "emby.seasonEpisodes") {
                episodeArgs += args
                if (hold) {
                    val job = currentCoroutineContext()[Job]!!
                    // 故意允许取消后仍返回，确保测试真正覆盖旧响应写回。
                    return suspendCoroutine { pending += Pending(args.str("parent_id")!!, job, it) }
                }
            }
            return fake.callJson(command, args, onPartial)
        }
    }

    private fun page(parent: String, label: String) = buildJsonObject {
        put("total", 1)
        put("items", arr(item("$parent-$label", label, "Episode", season = if (parent == "s1") 1 else 2, episode = 1)))
    }

    @Test fun phoneSeasonAndEpisodeLongPressActions() {
        open(episodeStates = true, dark = true)
        val detailReads = core.fake.calls.count { it.first == "emby.itemDetail" }
        core.fake.ret("download.enqueue", kotlinx.serialization.json.JsonNull)
        core.fake.ret("download.enqueueSeason", kotlinx.serialization.json.JsonNull)
        rule.onNodeWithTag("detail.ep.s1-0").performScrollTo().performTouchInput { longClick() }
        rule.onNodeWithText("标为未看").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag("poster.menu").captureRoboImage("build/menu-check/episode-dark.png")
        rule.onNodeWithText("标为未看").performClick()
        rule.waitUntil(5000) { core.fake.calls.any { it.first == "emby.setPlayed" } }
        assertEquals("s1-0", core.fake.calls.last { it.first == "emby.setPlayed" }.second.str("item_id"))
        assertEquals(JsonPrimitive(false), core.fake.calls.last { it.first == "emby.setPlayed" }.second?.get("played"))
        rule.onNodeWithTag("detail.ep.s1-0").performTouchInput { longClick() }
        rule.onNodeWithText("标为已看").assertIsDisplayed()
        rule.onNodeWithText("收藏").assertDoesNotExist()
        rule.onNodeWithText("下载").performClick()
        rule.waitUntil(5000) { core.fake.calls.any { it.first == "download.enqueue" } }
        assertEquals("s1-0", core.fake.calls.last { it.first == "download.enqueue" }.second.str("item_id"))
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription("查看第1季分集"))
        rule.onNodeWithContentDescription("查看第1季分集").performTouchInput { longClick() }
        rule.onNodeWithText("标为已看").performClick()
        rule.waitUntil(5000) { core.fake.calls.count { it.first == "emby.setPlayed" } == 2 }
        assertEquals("s1", core.fake.calls.last { it.first == "emby.setPlayed" }.second.str("item_id"))
        rule.onNodeWithContentDescription("查看第1季分集").performTouchInput { longClick() }
        rule.onNodeWithText("标为未看").assertIsDisplayed()
        rule.onNodeWithText("下载").performClick()
        rule.waitUntil(5000) { core.fake.calls.any { it.first == "download.enqueueSeason" } }
        assertEquals("s1", core.fake.calls.last { it.first == "download.enqueueSeason" }.second.str("parent_id"))
        assertEquals(detailReads, core.fake.calls.count { it.first == "emby.itemDetail" })
        val preloads = core.fake.calls.filter { it.first == "prefs.preloadItem" }
        assertTrue(preloads.isNotEmpty())
        assertTrue(preloads.all { it.second.str("item_id")?.startsWith("s1-") == true })
    }

    @Test fun seasonStatisticsUseSeasonPayloadAndDoNotInventCompletion() {
        fun status(fields: String) = xyz.linplayer.app.ui.components.homeEpisodeStatus(
            xyz.linplayer.app.ui.pages.seasonItem(kotlinx.serialization.json.Json.parseToJsonElement(
                """{"id":"s1","index_no":1,$fields}"""))!!)
        assertEquals(60L, status(""""unplayed":60,"child_count":60,"unplayed_count_known":true"""))
        for (played in listOf("true", "false", "null")) {
            assertEquals(0L, status(""""unplayed":0,"child_count":60,"unplayed_count_known":true,"played":$played"""))
        }
        assertEquals(0L, status(""""unplayed":0,"child_count":60,"played":true"""))
        assertEquals(0L, status(""""played":true"""))
        for (fields in listOf(
            """"child_count":60""",
            """"unplayed":0,"child_count":60,"played":false""",
            """"unplayed":-1,"child_count":60,"unplayed_count_known":true""",
            """"unplayed":0,"child_count":0,"unplayed_count_known":true,"played":false""",
        )) org.junit.Assert.assertNull(status(fields))
    }

    @Test fun phoneSeasonBadgeUsesCompleteEpisodesWithoutSeasonStatistics() = completeSeasonBadge(buildJsonObject {})

    @Test fun phoneSeasonBadgeUsesCompleteEpisodesOverStaleSeasonStatistics() = completeSeasonBadge(buildJsonObject {
        put("child_count", 60); put("unplayed", 60); put("unplayed_count_known", true); put("played", false)
    })

    private fun completeSeasonBadge(state: JsonObject) {
        open(total = 60, seasonState = state, allPlayed = true)
        pageText("已看 60 / 60").performScrollTo().assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToKey("seasons")
        val season = rule.onNodeWithContentDescription("查看第1季分集")
        rule.onNode(hasContentDescription("已看完") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("detail.seasons").captureRoboImage("build/menu-check/season-complete.png")
        assertEquals(1, core.episodeArgs.size)
        season.performTouchInput { longClick() }
        rule.onNodeWithText("标为未看").performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("60 集未观看") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(JsonPrimitive(false), core.fake.calls.last { it.first == "emby.setPlayed" }.second?.get("played"))
    }

    @Test fun phoneSeasonBadgeDoesNotCompleteFromPartialOrFailedEpisodes() {
        open(total = 12, holdAtStart = true, seasonState = buildJsonObject {})
        rule.runOnIdle { core.pending[0].complete(batch(0, 8, played = true)) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToKey("seasons")
        val badge = rule.onNode(hasContentDescription("已看完") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true)
        badge.assertDoesNotExist()
        rule.runOnIdle { core.pending[1].fail() }
        rule.waitForIdle()
        badge.assertDoesNotExist()
        pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle { core.pending[2].complete(batch(8, 12, played = true)) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToKey("seasons")
        badge.assertIsDisplayed()
    }

    @Test fun phoneSeasonBadgeDoesNotCompleteFromEarlyEmptyPage() {
        open(total = 12, holdAtStart = true, seasonState = buildJsonObject {})
        rule.runOnIdle { core.pending[0].complete(batch(0, 8, played = true)) }
        rule.waitForIdle()
        rule.runOnIdle { core.pending[1].complete(batch(8, 8)) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToKey("seasons")
        rule.onNode(hasContentDescription("已看完") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun phoneSeasonUnplayedBadgeRefreshesAfterLongPress() {
        open(total = 60)
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToKey("seasons")
        val season = rule.onNodeWithContentDescription("查看第1季分集")
        rule.onNode(hasContentDescription("60 集未观看") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("detail.seasons").captureRoboImage("build/menu-check/season-unplayed.png")
        season.performTouchInput { longClick() }
        rule.onNodeWithText("标为已看").assertIsDisplayed()
        rule.onNodeWithText("标为已看").performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("已看完") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true).assertIsDisplayed()
        season.performTouchInput { longClick() }
        rule.onNodeWithText("标为未看").performClick()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("60 集未观看") and
            hasAnyAncestor(hasContentDescription("查看第1季分集")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("s1", core.fake.calls.last { it.first == "emby.setPlayed" }.second.str("item_id"))
        assertEquals(JsonPrimitive(false), core.fake.calls.last { it.first == "emby.setPlayed" }.second?.get("played"))
    }

    @Test fun phonePlayedActionRefreshesBadgesSummaryAndTarget() {
        open(total = 2)
        rule.onNodeWithTag("detail.ep.s1-0").performScrollTo().performTouchInput { longClick() }
        rule.onNodeWithText("标为已看").performClick()
        rule.waitForIdle()
        pageText("已看 1 / 2").assertIsDisplayed()
        rule.onNode(hasContentDescription("已看完") and hasAnyAncestor(hasTestTag("detail.ep.s1-0")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("待播放") and hasAnyAncestor(hasTestTag("detail.ep.s1-1")), useUnmergedTree = true).assertExists()
        rule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToKey("seasons")
        rule.onNodeWithContentDescription("查看第1季分集").performScrollTo().performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("标为已看").performClick()
        rule.waitForIdle()
        pageText("已看 2 / 2").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("detail.ep.s1-1").performScrollTo().performTouchInput { longClick() }
        rule.onNodeWithText("标为未看").performClick()
        rule.waitForIdle()
        pageText("已看 1 / 2").assertIsDisplayed()
    }

    @Test fun phoneEpisodeActionSurvivesCardRecycling() = pendingEpisodeActionSurvives("emby.setPlayed", "标为已看")
    @Test fun phoneEpisodeDownloadSurvivesCardRecycling() = pendingEpisodeActionSurvives("download.enqueue", "下载")

    private fun pendingEpisodeActionSurvives(command: String, label: String) {
        open(total = 60)
        core.heldCommand = command
        rule.onNodeWithTag("detail.ep.s1-0").performScrollTo().performTouchInput { longClick() }
        rule.onNodeWithText(label).performClick()
        rule.waitUntil(5000) { core.pendingActions.size == 1 }
        val row = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasTestTag("detail.ep.s1-0")))
        row.performScrollToIndex(40)
        rule.waitForIdle()
        rule.onNodeWithTag("detail.ep.s1-0").assertDoesNotExist()
        assertTrue("卡片回收不应取消用户已提交的操作", core.pendingActions.single().job.isActive)
        rule.runOnIdle { core.pendingActions.single().complete(kotlinx.serialization.json.JsonNull) }
        rule.waitForIdle()
        assertTrue("操作应正常完成", core.pendingActions.single().job.isCompleted && !core.pendingActions.single().job.isCancelled)
    }

    private fun open(total: Int = 1, holdAtStart: Boolean = false, resumeEpisode: Int? = null, dark: Boolean = false, episodeStates: Boolean = false, noSeasons: Boolean = false, seasonFailure: Boolean = false, seasonState: JsonObject? = null, allPlayed: Boolean = false) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val fake = FakeCore().loggedIn()
        fake.ret("emby.itemDetail", buildJsonObject {
            item("series", "测试剧集", "Series").forEach { (k, v) -> put(k, v) }
            if (episodeStates) put("overview", overview)
        })
        val watched = mutableMapOf<String, Boolean>()
        fun isPlayed(id: String, index: Int) = watched[id] ?: (allPlayed || episodeStates && index == 0)
        fake.on("emby.setPlayed") { request ->
            val id = request.str("item_id")!!
            val value = request?.get("played") == JsonPrimitive(true)
            if (id == "s1" || id == "s2") (0 until total).forEach { watched["$id-$it"] = value }
            else watched[id] = value
            buildJsonObject { put("item_id", id); put("played", value) }
        }
        fake.on("emby.seriesSeasons") { buildJsonArray {
            for (i in 1..if (noSeasons) 0 else 2) add(buildJsonObject {
                put("id", "s$i"); put("name", "第 $i 季")
                put("index_no", i)
                if (seasonState != null) seasonState.forEach { (key, value) -> put(key, value) }
                else {
                    put("child_count", total); put("unplayed", (0 until total).count { !isPlayed("s$i-$it", it) })
                    put("unplayed_count_known", true)
                    // 容器 Played 尚未同步时，也应按明确的零未看统计显示完成。
                    put("played", false)
                }
            })
        } }
        if (seasonFailure) fake.on("emby.seriesSeasons") { throw CoreException("E_NETWORK", "季列表测试失败", true) }
        fake.ret("emby.listResume", if (resumeEpisode == null) arr() else arr(
            item("s1-${resumeEpisode - 1}", "续播目标", "Episode", season = 1, episode = resumeEpisode, seriesId = "series")))
        fake.on("emby.seasonEpisodes") { args ->
            val parent = args.str("parent_id")!!
            val start = (args.long("start_index") ?: 0).toInt()
            val limit = minOf((args.long("limit") ?: 30).toInt(), 150)
            buildJsonObject {
                put("total", total)
                put("items", buildJsonArray {
                    for (i in start until minOf(start + limit, total)) {
                        add(item("$parent-$i", "$parent 分集 ${i + 1}", "Episode", season = if (parent == "s1") 1 else 2, episode = i + 1,
                            runtime = if (episodeStates) 1800.0 else 0.0,
                            resume = if (episodeStates && i == 1) 120.0 else 0.0,
                            played = isPlayed("$parent-$i", i)))
                    }
                })
            }
        }
        core = DelayedEpisodes(fake).apply { hold = holdAtStart }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            if (visible.value) {
                LpTheme(darkOverride = dark) {
                    CompositionLocalProvider(LocalApp provides app) {
                        val nav = rememberNavController()
                        phoneNav = nav
                        NavHost(nav, startDestination = Route.Detail("series", "Series")) {
                            composable<Route.Detail> { DetailPage(nav, it) }
                            composable<Route.Player> { Text("测试播放页") }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    // 播放副标题与分集卡会显示同一集名，旧回归在这里明确查分集/页面正文。
    private fun pageText(text: String, substring: Boolean = false) =
        rule.onNode(hasText(text, substring = substring) and !hasTestTag("detail.play.target") and
            !hasTestTag("detail.series.target") and !hasAnyAncestor(hasTestTag("detail.series.target")))

    private fun pick(season: Int) {
        rule.onNodeWithContentDescription("选择季").performScrollTo().performClick()
        rule.onNode(hasText("第${season}季") and !hasContentDescription("选择季") and
            !hasContentDescription("查看第${season}季分集") and
            !hasAnyAncestor(hasContentDescription("查看第${season}季分集"))).performClick()
        rule.waitForIdle()
    }

    @After fun clean() {
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        if (::core.isInitialized) rule.runOnIdle {
            core.pending.filter { !it.completed }.forEach { it.complete(page(it.parent, "清理响应")) }
        }
        scope.cancel()
        PageCache.clear()
    }

    @Test fun phoneLoadsEveryPageOfLongSeason() {
        open(total = 403)
        assertEquals(listOf(0L, 150L, 300L), core.episodeArgs.map { it.long("start_index") ?: 0 })
        pageText("已看 0 / 403").performScrollTo().assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("S1E1：s1 分集 1")))
            .performScrollToIndex(402)
        pageText("s1 分集 403", substring = true).assertIsDisplayed()
    }

    @Test fun phoneWithoutSeasonsLoadsEveryPageAndPlaysEpisode() {
        open(total = 403, noSeasons = true)
        assertEquals(listOf("series", "series", "series"), core.episodeArgs.map { it.str("parent_id") })
        assertEquals(listOf(0L, 150L, 300L), core.episodeArgs.map { it.long("start_index") })
        pageText("已看 0 / 403").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("detail.play").performScrollTo().performClick()
        assertEquals("series-0", phoneNav.currentBackStackEntry!!.toRoute<Route.Player>().itemId)
    }

    @Test fun phoneWithoutSeasonsRetriesFirstPage() {
        open(noSeasons = true, holdAtStart = true)
        assertEquals("series", core.pending.single().parent)
        pageText("暂无可用选集").assertDoesNotExist()
        rule.runOnIdle { core.pending.single().fail() }
        rule.waitForIdle()
        pageText("分页测试失败", substring = true).performScrollTo().assertIsDisplayed()
        pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("series", "series"), core.pending.map { it.parent })
        rule.runOnIdle { core.pending.last().complete(page("series", "直接挂集")) }
        rule.waitForIdle()
        pageText("直接挂集", substring = true).performScrollTo().assertIsDisplayed()
        pageText("重试").assertDoesNotExist()
    }

    @Test fun phoneWithoutSeasonsShowsEmptyOnlyAfterEpisodesReturn() {
        open(noSeasons = true, holdAtStart = true)
        assertEquals("series", core.pending.single().parent)
        pageText("暂无可用选集").assertDoesNotExist()
        rule.runOnIdle { core.pending.single().complete(buildJsonObject {
            put("total", 0); put("items", arr())
        }) }
        rule.waitForIdle()
        pageText("暂无可用选集").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("正在加载分集…").assertDoesNotExist()
    }

    @Test fun phoneSeasonFailureDoesNotFallBackToSeries() {
        open(seasonFailure = true)
        assertTrue(core.episodeArgs.isEmpty())
        pageText("季列表测试失败", substring = true).performScrollTo().assertIsDisplayed()
        rule.runOnIdle { core.fake.ret("emby.seriesSeasons", arr()) }
        pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("series"), core.episodeArgs.map { it.str("parent_id") })
        pageText("series 分集 1", substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun emptySeasonsOnDetail(type: String, seasonId: String? = null) {
        open(noSeasons = true)
        rule.runOnIdle {
            core.fake.ret("emby.itemDetail", buildJsonObject {
                item("target", "无季列表详情", type, seriesId = "series").forEach { (k, v) -> put(k, v) }
                if (seasonId != null) put("season_id", seasonId)
            })
            core.episodeArgs.clear()
            phoneNav.navigate(Route.Detail("target", type))
        }
        rule.waitForIdle()
        val parent = if (type == "Season") "target" else seasonId ?: "series"
        assertEquals(listOf(parent), core.episodeArgs.map { it.str("parent_id") })
        pageText("$parent 分集 1", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun phoneEpisodeWithoutSeasonsUsesSeries() = emptySeasonsOnDetail("Episode")
    @Test fun phoneEpisodeWithoutSeasonsKeepsKnownSeason() = emptySeasonsOnDetail("Episode", "known-season")
    @Test fun phoneSeasonWithoutSeasonsKeepsRouteParent() = emptySeasonsOnDetail("Season")

    private fun batch(start: Int, end: Int, total: Int = 12, played: Boolean = false) = buildJsonObject {
        put("total", total)
        put("items", buildJsonArray {
            for (i in start until end) add(item("s1-$i", "渐进分集 ${i + 1}", "Episode", season = 1, episode = i + 1, played = played))
        })
    }

    @Test fun phoneShowsPartialSeasonAndRetriesFailedPage() {
        open(total = 12, holdAtStart = true)
        rule.runOnIdle { core.pending[0].complete(batch(0, 8)) }
        rule.waitForIdle()
        // 后续页仍挂起时，首批必须已可浏览。
        pageText("渐进分集 1", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(2, core.pending.size)
        rule.runOnIdle { core.pending[1].fail() }
        rule.waitForIdle()
        pageText("渐进分集 1", substring = true).assertExists()
        pageText("分页测试失败", substring = true).assertExists()
        pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(0L, 8L, 8L), core.episodeArgs.map { it.long("start_index") })
        pageText("渐进分集 1", substring = true).assertExists()
        rule.runOnIdle { core.pending[2].complete(batch(8, 12)) }
        rule.waitForIdle()
        pageText("重试").assertDoesNotExist()
        pageText("已看 0 / 12").performScrollTo().assertIsDisplayed()
    }

    @Test fun phoneAppendingKeepsScrollPosition() {
        open(total = 12, holdAtStart = true, resumeEpisode = 7)
        rule.runOnIdle { core.pending[0].complete(batch(0, 8)) }
        rule.waitForIdle()
        pageText("渐进分集 1", substring = true).performScrollTo()
        val row = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("渐进分集 1", substring = true)))
        row.performScrollToIndex(4)
        val card = pageText("渐进分集 5", substring = true)
        rule.waitForIdle()
        val before = card.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { core.pending[1].complete(batch(8, 12)) }
        rule.waitForIdle()
        assertEquals(before, card.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun phoneRetriesFirstPageFailure() {
        open(holdAtStart = true, dark = true)
        rule.runOnIdle { core.pending[0].fail() }
        rule.waitForIdle()
        pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle { core.pending[1].complete(batch(0, 1, 1)) }
        rule.waitForIdle()
        pageText("渐进分集 1", substring = true).assertExists()
        assertEquals(listOf(0L, 0L), core.episodeArgs.map { it.long("start_index") })
    }

    private fun detailHierarchy(dark: Boolean) {
        open(total = 3, resumeEpisode = 2, episodeStates = true, dark = dark)
        rule.onNodeWithTag("detail.play").assertTextContains("继续观看")
        rule.onNodeWithTag("detail.play.target").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/detail-ui/${if (dark) "phone-dark" else "phone-light"}-top.png")
        pageText("待播放").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("已看完").assertExists()
        rule.onRoot().captureRoboImage("build/detail-ui/${if (dark) "phone-dark" else "phone-light"}-episodes.png")
        val row = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("s1 分集 2", substring = true)))
        row.performScrollToIndex(2)
        pageText("未看").assertDoesNotExist()
        rule.onNodeWithTag("detail.ep.s1-2").assertIsDisplayed().assertTextContains("30:00")
        rule.onNodeWithContentDescription("展开简介").performScrollTo()
        rule.onRoot().captureRoboImage("build/detail-ui/phone-${if (dark) "dark" else "light"}-overview.png")
        val layouts = mutableListOf<TextLayoutResult>()
        pageText(overview).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(3, layouts.single().lineCount)
        rule.onNodeWithContentDescription("展开简介").performClick()
        layouts.clear()
        pageText(overview).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 3)
        rule.onNodeWithContentDescription("收起简介").performScrollTo().performClick()
        rule.onNodeWithTag("detail.series.target").performScrollTo()
            .assert(hasAnyDescendant(hasText("S1E2：s1 分集 2", substring = true)))
        rule.onNodeWithTag("detail.play").performScrollTo().performClick()
        assertEquals("s1-1", phoneNav.currentBackStackEntry!!.toRoute<Route.Player>().itemId)
    }

    @Test fun phoneDetailHierarchyLight() = detailHierarchy(false)
    @Test fun phoneDetailHierarchyDark() = detailHierarchy(true)

    @Test fun phoneEpisodeMarksCurrentItem() {
        open(total = 3, episodeStates = true)
        rule.runOnIdle {
            core.fake.ret("emby.itemDetail", item("s1-1", "当前单集", "Episode", seriesId = "series", season = 1, episode = 2))
            phoneNav.navigate(Route.Detail("s1-1", "Episode"))
        }
        rule.waitForIdle()
        pageText("当前集").performScrollTo().assertIsDisplayed()
        pageText("待播放").assertDoesNotExist()
    }

    @Test fun phoneRejectsLateSeasonResponse() {
        open()
        rule.runOnIdle { core.hold = true }
        pick(2)
        pick(1)
        pick(2)
        pick(2) // 重复选择当前季不重拉。
        assertEquals(listOf("s2", "s1", "s2"), core.pending.map { it.parent })
        rule.runOnIdle { core.pending.last().complete(page("s2", "当前季新响应")) }
        rule.waitForIdle()
        rule.runOnIdle {
            core.pending[1].complete(page("s1", "另一季迟到响应"))
            core.pending[0].complete(page("s2", "旧季迟到响应"))
        }
        rule.waitForIdle()
        pageText("当前季新响应", substring = true).assertExists()
        pageText("旧季迟到响应", substring = true).assertDoesNotExist()
        pageText("另一季迟到响应", substring = true).assertDoesNotExist()
        assertTrue("切季没有取消旧请求", core.pending.dropLast(1).all { it.job.isCancelled })
    }

    private fun phoneDiscardsResponseAfterLeaving(noSeasons: Boolean) {
        open(holdAtStart = true, noSeasons = noSeasons)
        rule.runOnIdle { phoneNav.navigate(Route.Detail("other", "Movie")) }
        rule.waitForIdle()
        assertTrue(core.pending.single().job.isCancelled)
        rule.runOnIdle { core.pending.single().complete(page("s1", "离页迟到响应")) }
        rule.waitForIdle()
        rule.runOnIdle { core.hold = false; phoneNav.popBackStack() }
        rule.waitForIdle()
        pageText("离页迟到响应", substring = true).assertDoesNotExist()
        pageText("${if (noSeasons) "series" else "s1"} 分集 1", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun phoneDiscardsResponseAfterLeaving() = phoneDiscardsResponseAfterLeaving(false)
    @Test fun phoneWithoutSeasonsDiscardsResponseAfterLeaving() = phoneDiscardsResponseAfterLeaving(true)
}
