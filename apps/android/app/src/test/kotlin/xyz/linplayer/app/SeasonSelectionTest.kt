package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.input.key.Key
import xyz.linplayer.app.tv.press
import androidx.compose.ui.test.performScrollTo
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
import xyz.linplayer.app.tv.TvFrame
import xyz.linplayer.app.tv.TvNav
import xyz.linplayer.app.tv.TvRoute
import xyz.linplayer.app.tv.TvShell
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
    private lateinit var tvNav: TvNav
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
        var hold = false
        val pending = mutableListOf<Pending>()
        val episodeArgs = mutableListOf<JsonObject?>()

        override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
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

    private fun open(tv: Boolean = false, total: Int = 1, holdAtStart: Boolean = false, resumeEpisode: Int? = null, dark: Boolean = false, episodeStates: Boolean = false) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val fake = FakeCore().loggedIn()
        fake.ret("emby.itemDetail", buildJsonObject {
            item("series", "测试剧集", "Series").forEach { (k, v) -> put(k, v) }
            if (episodeStates) put("overview", overview)
        })
        fake.ret("emby.seriesSeasons", buildJsonArray {
            for (i in 1..2) add(buildJsonObject {
                put("id", "s$i"); put("name", "第 $i 季"); put("type_", "Season")
                put("index_no", i); put("child_count", total); put("unplayed", total)
            })
        })
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
                            played = episodeStates && i == 0))
                    }
                })
            }
        }
        core = DelayedEpisodes(fake).apply { hold = holdAtStart }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        tvNav = TvNav().apply { push(TvRoute.Detail("series", "Series")) }
        rule.setContent {
            if (visible.value) {
                if (tv) TvFrame(app) {
                    if (tvNav.top.route is TvRoute.Player) Text("测试播放页") else TvShell(tvNav)
                }
                else LpTheme(darkOverride = dark) {
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

    private fun pick(season: Int, tv: Boolean) {
        if (tv) {
            rule.onNodeWithTag("season.s$season").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.DirectionCenter)
        }
        else {
            rule.onNodeWithContentDescription("选择季").performScrollTo().performClick()
            rule.onNode(hasText("第${season}季") and !hasContentDescription("选择季") and
                !hasContentDescription("查看第${season}季分集") and
                !hasAnyAncestor(hasContentDescription("查看第${season}季分集"))).performClick()
        }
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

    private fun batch(start: Int, end: Int, total: Int = 12) = buildJsonObject {
        put("total", total)
        put("items", buildJsonArray {
            for (i in start until end) add(item("s1-$i", "渐进分集 ${i + 1}", "Episode", season = 1, episode = i + 1))
        })
    }

    private fun progressiveRetry(tv: Boolean) {
        open(tv = tv, total = 12, holdAtStart = true)
        rule.runOnIdle { core.pending[0].complete(batch(0, 8)) }
        rule.waitForIdle()
        // 后续页仍挂起时，首批必须已可浏览。
        if (tv) pageText("E1 · 渐进分集 1").assertExists()
        else pageText("渐进分集 1", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(2, core.pending.size)
        if (tv) rule.onNodeWithTag("detail.ep.s1-0")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.runOnIdle { core.pending[1].fail() }
        rule.waitForIdle()
        pageText("渐进分集 1", substring = true).assertExists()
        pageText("分页测试失败", substring = true).assertExists()
        if (tv) {
            rule.onNodeWithTag("detail.ep.s1-0").assertIsFocused()
            press(rule, Key.DirectionDown)
            rule.onNodeWithTag("detail.episodes.retry").assertIsFocused()
            press(rule, Key.DirectionCenter)
        } else pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(0L, 8L, 8L), core.episodeArgs.map { it.long("start_index") })
        pageText("渐进分集 1", substring = true).assertExists()
        rule.runOnIdle { core.pending[2].complete(batch(8, 12)) }
        rule.waitForIdle()
        pageText("重试").assertDoesNotExist()
        if (tv) pageText("播放 S1E1").assertExists()
        else pageText("已看 0 / 12").performScrollTo().assertIsDisplayed()
    }

    @Test fun phoneShowsPartialSeasonAndRetriesFailedPage() = progressiveRetry(false)

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvShowsPartialSeasonAndRetriesFailedPage() = progressiveRetry(true)

    private fun appendKeepsPosition(tv: Boolean) {
        open(tv = tv, total = 12, holdAtStart = true, resumeEpisode = 7)
        rule.runOnIdle { core.pending[0].complete(batch(0, 8)) }
        rule.waitForIdle()
        if (!tv) pageText("渐进分集 1", substring = true).performScrollTo()
        val row = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("渐进分集 1", substring = true)))
        row.performScrollToIndex(4)
        val card = if (tv) rule.onNodeWithTag("detail.ep.s1-4") else pageText("渐进分集 5", substring = true)
        if (tv) {
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.DirectionRight)
        }
        rule.waitForIdle()
        val kept = if (tv) rule.onNodeWithTag("detail.ep.s1-5") else card
        val before = kept.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { core.pending[1].complete(batch(8, 12)) }
        rule.waitForIdle()
        if (tv) kept.assertIsFocused()
        assertEquals(before, kept.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun phoneAppendingKeepsScrollPosition() = appendKeepsPosition(false)

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvAppendingAndLateTargetKeepFocusAndPosition() = appendKeepsPosition(true)

    private fun firstPageRetry(tv: Boolean) {
        open(tv = tv, holdAtStart = true, dark = true)
        rule.runOnIdle { core.pending[0].fail() }
        rule.waitForIdle()
        if (tv) {
            rule.onNodeWithTag("detail.episodes.retry").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.DirectionCenter)
            rule.onNodeWithTag("detail.episodes.retry").assertIsFocused()
        } else pageText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle { core.pending[1].complete(batch(0, 1, 1)) }
        rule.waitForIdle()
        pageText("渐进分集 1", substring = true).assertExists()
        if (tv) {
            pageText("播放 S1E1").assertExists()
            rule.onNodeWithTag("detail.ep.s1-0").assertIsFocused()
        }
        assertEquals(listOf(0L, 0L), core.episodeArgs.map { it.long("start_index") })
    }

    @Test fun phoneRetriesFirstPageFailure() = firstPageRetry(false)

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvRetriesFirstPageAndRestoresPlaybackTarget() = firstPageRetry(true)

    private fun detailHierarchy(tv: Boolean, dark: Boolean) {
        open(tv = tv, total = 3, resumeEpisode = 2, episodeStates = true, dark = dark)
        if (tv) pageText("继续 S1E2", substring = true).assertExists()
        else rule.onNodeWithTag("detail.play").assertTextContains("继续观看")
        if (tv) rule.onNodeWithTag("detail.play.target").assertTextContains("S1E2 · s1 分集 2", substring = true)
        else rule.onNodeWithTag("detail.play.target").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/detail-ui/${if (tv) "tv" else if (dark) "phone-dark" else "phone-light"}-top.png")
        if (!tv) pageText("待播放").performScrollTo().assertIsDisplayed()
        else pageText("待播放", substring = true).assertExists()
        if (tv) pageText("已看完", substring = true).assertExists()
        else rule.onNodeWithContentDescription("已看完").assertExists()
        rule.onRoot().captureRoboImage("build/detail-ui/${if (tv) "tv" else if (dark) "phone-dark" else "phone-light"}-episodes.png")
        val row = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("s1 分集 2", substring = true)))
        row.performScrollToIndex(2)
        if (tv) pageText("未看", substring = true).assertExists()
        else {
            pageText("未看").assertDoesNotExist()
            rule.onNodeWithTag("detail.ep.s1-2").assertIsDisplayed().assertTextContains("30:00")
        }
        if (!tv) {
            rule.onNodeWithContentDescription("展开简介").performScrollTo()
            rule.onRoot().captureRoboImage("build/detail-ui/phone-${if (dark) "dark" else "light"}-overview.png")
        }
        val layouts = mutableListOf<TextLayoutResult>()
        pageText(overview).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(if (tv) 2 else 3, layouts.single().lineCount)
        if (!tv) {
            rule.onNodeWithContentDescription("展开简介").performClick()
            layouts.clear()
            pageText(overview).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount > 3)
            rule.onNodeWithContentDescription("收起简介").performScrollTo().performClick()
        }
        if (tv) {
            pick(2, true)
            rule.onNodeWithTag("detail.play.target").assertTextContains("S1E2 · s1 分集 2", substring = true)
            rule.onNodeWithTag("detail.play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.DirectionCenter)
            assertEquals("s1-1", (tvNav.top.route as TvRoute.Player).itemId)
        } else {
            rule.onNodeWithTag("detail.series.target").performScrollTo()
                .assert(hasAnyDescendant(hasText("S1E2：s1 分集 2", substring = true)))
            rule.onNodeWithTag("detail.play").performScrollTo().performClick()
            assertEquals("s1-1", phoneNav.currentBackStackEntry!!.toRoute<Route.Player>().itemId)
        }
    }

    @Test fun phoneDetailHierarchyLight() = detailHierarchy(false, false)
    @Test fun phoneDetailHierarchyDark() = detailHierarchy(false, true)

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvDetailHierarchyKeepsPlaybackTarget() = detailHierarchy(true, true)

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

    private fun lateResponse(tv: Boolean) {
        open(tv)
        rule.runOnIdle { core.hold = true }
        pick(2, tv)
        pick(1, tv)
        pick(2, tv)
        pick(2, tv) // 手机重复选择当前季不重拉，TV 保留原有同季重试。
        assertEquals(if (tv) listOf("s2", "s1", "s2", "s2") else listOf("s2", "s1", "s2"), core.pending.map { it.parent })
        rule.runOnIdle { core.pending.last().complete(page("s2", "当前季新响应")) }
        rule.waitForIdle()
        rule.runOnIdle {
            if (tv) core.pending[2].complete(page("s2", "同季迟到响应"))
            core.pending[1].complete(page("s1", "另一季迟到响应"))
            core.pending[0].complete(page("s2", "旧季迟到响应"))
        }
        rule.waitForIdle()
        pageText("当前季新响应", substring = true).assertExists()
        pageText("旧季迟到响应", substring = true).assertDoesNotExist()
        pageText("另一季迟到响应", substring = true).assertDoesNotExist()
        pageText("同季迟到响应", substring = true).assertDoesNotExist()
        assertTrue("切季没有取消旧请求", core.pending.dropLast(1).all { it.job.isCancelled })
    }

    @Test fun phoneRejectsLateSeasonResponse() = lateResponse(false)

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvRejectsLateSeasonResponse() = lateResponse(true)

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvInitialResponseKeepsTargetWithoutReplacingSelectedSeason() {
        open(tv = true, holdAtStart = true)
        pick(2, true)
        rule.runOnIdle { core.pending[1].complete(page("s2", "当前第二季")) }
        rule.waitForIdle()
        rule.runOnIdle { core.pending[0].complete(page("s1", "初始第一季")) }
        rule.waitForIdle()
        pageText("当前第二季", substring = true).assertExists()
        pageText("初始第一季", substring = true).assertDoesNotExist()
        pageText("播放 S1E1").assertExists()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvRetriesDefaultTargetWhileBrowsingAnotherSeason() {
        open(tv = true, holdAtStart = true)
        rule.runOnIdle { core.pending[0].complete(batch(0, 2, 3)) }
        rule.waitForIdle()
        pick(2, true)
        rule.runOnIdle { core.pending[2].complete(page("s2", "浏览第二季")) }
        rule.waitForIdle()
        rule.runOnIdle { core.pending[1].fail() }
        rule.waitForIdle()
        pageText("重试播放目标").assertExists()
        rule.onNodeWithTag("detail.play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        assertEquals(2L, core.episodeArgs.last().long("start_index"))
        rule.runOnIdle { core.pending[3].complete(batch(2, 3, 3)) }
        rule.waitForIdle()
        pageText("播放 S1E1").assertExists()
        pageText("浏览第二季", substring = true).assertExists()
        pageText("渐进分集 1", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("detail.play").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvTargetRetrySurvivesSwitchingTheBrowsedSeason() {
        open(tv = true, holdAtStart = true)
        rule.runOnIdle { core.pending[0].fail() }
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        pick(2, true)
        rule.runOnIdle { core.pending[2].complete(page("s2", "浏览第二季")) }
        rule.waitForIdle()
        rule.runOnIdle { core.pending[1].complete(batch(0, 1, 1)) }
        rule.waitForIdle()
        pageText("播放 S1E1").assertExists()
        pageText("浏览第二季", substring = true).assertExists()
    }

    @Test fun phoneDiscardsResponseAfterLeaving() {
        open(holdAtStart = true)
        rule.runOnIdle { phoneNav.navigate(Route.Detail("other", "Movie")) }
        rule.waitForIdle()
        assertTrue(core.pending.single().job.isCancelled)
        rule.runOnIdle { core.pending.single().complete(page("s1", "离页迟到响应")) }
        rule.waitForIdle()
        rule.runOnIdle { core.hold = false; phoneNav.popBackStack() }
        rule.waitForIdle()
        pageText("离页迟到响应", substring = true).assertDoesNotExist()
        pageText("s1 分集 1", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvReloadsCancelledSeasonOnReturn() {
        open(tv = true)
        rule.runOnIdle { core.hold = true }
        pick(2, true)
        rule.runOnIdle { tvNav.push(TvRoute.Home) }
        rule.waitForIdle()
        assertTrue(core.pending.single().job.isCancelled)
        rule.runOnIdle { core.pending.single().complete(page("s2", "离页迟到响应")) }
        rule.waitForIdle()
        rule.runOnIdle { core.hold = false; tvNav.pop() }
        rule.waitForIdle()
        pageText("s2 分集 1", substring = true).assertExists()
        rule.onNodeWithTag("season.s2").assertIsFocused()
        pageText("离页迟到响应", substring = true).assertDoesNotExist()
        pageText("播放 S1E1").assertExists()
    }
}
