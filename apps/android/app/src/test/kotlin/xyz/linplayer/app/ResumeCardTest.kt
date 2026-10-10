package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.test.core.app.ApplicationProvider
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.ui.components.LpTabBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.components.CardAction
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpTheme

/** 续播卡片的主题、字号与原有操作入口。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class ResumeCardTest {
    @get:Rule val rule = createComposeRule()
    private val episode = Item("ep", "漫长旅途中的重逢与新的开始", "Episode", seriesName = "远方的故事",
        seasonNo = 1, episodeNo = 12, runtimeSecs = 3600.0, resumeSecs = 1801.0)

    private fun render(dark: Boolean, scale: Float) {
        var opened = ""
        var removed = false
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Column(Modifier.background(Lp.colors.bg)) {
                        LpRow("继续观看", listOf(episode), { null }, { opened = it.id }, thumb = true,
                            resume = true, menu = { listOf(CardAction("取消观看记录", false) { removed = true }) })
                        LpRow("普通卡片", listOf(episode.copy(id = "normal", name = "普通单集", resumeSecs = 0.0)), { null }, {}, thumb = true)
                    }
                }
            }
        }
        rule.onNodeWithText("漫长旅途中的重逢与新的开始", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("剩余 29:59", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("S1E12", useUnmergedTree = true).assertCountEquals(2)
        rule.onRoot().captureRoboImage("build/resume-ui/${if (dark) "dark" else "light"}-$scale.png")
        rule.onNode(hasClickAction() and hasText("剩余 29:59")).performClick()
        rule.runOnIdle { assertEquals("ep", opened) }
        rule.onNode(hasClickAction() and hasText("剩余 29:59")).performTouchInput { longClick() }
        rule.onNodeWithText("取消观看记录").performClick()
        rule.runOnIdle { assertEquals(true, removed) }
    }

    @Test fun unwatchedResumeMovieShowsFullDuration() {
        rule.setContent {
            LpTheme(darkOverride = false) {
                LpRow("继续观看", listOf(Item("movie", "未观看影片", "Movie", runtimeSecs = 5401.0)), { null }, {}, resume = true)
            }
        }
        rule.onNodeWithText("1:30:01", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun ordinaryCardsNeverShowDurationEvenWithProgress() {
        rule.setContent {
            LpTheme(darkOverride = false) {
                Column {
                    LpRow("影片", listOf(Item("movie", "未观看影片", "Movie", runtimeSecs = 5401.0)), { null }, {})
                    LpRow("单集", listOf(episode), { null }, {}, thumb = true)
                }
            }
        }
        rule.onAllNodesWithText("1:30:01").assertCountEquals(0)
        rule.onAllNodesWithText("剩余 ", substring = true).assertCountEquals(0)
    }

    @Test fun ratingCoexistsWithUnplayedCount() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        rule.setContent {
            LpTheme(darkOverride = true) {
                Box(Modifier.fillMaxSize().background(Lp.colors.bg)) {
                    Column {
                        LpRow("媒体库", listOf(Item("lib", "国产剧", "CollectionFolder"),
                            Item("lib2", "日韩剧", "CollectionFolder")), { "fake:${it.id}" }, {}, thumb = true)
                        LpRow("继续观看", listOf(episode, Item("movie", "远方的来信", "Movie", runtimeSecs = 3047.0)),
                            { "fake:${it.id}" }, {}, thumb = true, resume = true)
                        LpRow("国产剧", listOf(Item("series", "余红旧事", "Series", unplayed = 24, unplayedCountKnown = true, doubanRating = 8.0, year = 2026),
                            Item("series2", "我不是大师", "Series", unplayed = 19, unplayedCountKnown = true, year = 2026),
                            Item("series3", "如期", "Series", played = true, unplayedCountKnown = true, doubanRating = 9.1, year = 2026)),
                            { "fake:${it.id}" }, {}, onMore = {})
                    }
                    Box(Modifier.align(Alignment.BottomCenter)) { LpTabBar(0, onSearch = {}) {} }
                }
            }
        }
        rule.onNodeWithText("24", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("8.0", useUnmergedTree = true).assertIsDisplayed()
        val count = rule.onNodeWithText("24", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val score = rule.onNodeWithText("8.0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("评分应位于计数右下方", score.top > count.bottom)
        rule.onRoot().captureRoboImage("build/resume-ui/reference-rating.png")
    }

    @Test fun largeUnplayedCountsRemainSingleLineAtLargeFontSize() {
        rule.setContent {
            LpTheme(darkOverride = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    Column(Modifier.background(Lp.colors.bg)) {
                        LpRow("长篇剧集", listOf(Item("long", "长篇剧集", "Series", unplayed = 403, unplayedCountKnown = true, doubanRating = 8.0)), { null }, {})
                    }
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("403", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        org.junit.Assert.assertFalse("计数文字不能裁切: ${layouts.single().size}, width=${layouts.single().didOverflowWidth}, height=${layouts.single().didOverflowHeight}", layouts.single().hasVisualOverflow)
        rule.onNodeWithText("8.0", useUnmergedTree = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/resume-ui/reference-large-count.png")
    }

    @Test fun light() = render(false, 1f)
    @Test fun darkLargeText() = render(true, 1.3f)

    @Test fun unknownOrCompletedDurationHasNoRemainingBadge() {
        rule.setContent {
            LpTheme(darkOverride = false) {
                LpRow("继续观看", listOf(
                    episode.copy(id = "unknown", runtimeSecs = 0.0),
                    episode.copy(id = "done", resumeSecs = 3600.0),
                ), { null }, {}, thumb = true, resume = true)
            }
        }
        rule.onAllNodesWithText("剩余", substring = true).assertCountEquals(0)
    }
}
