package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class HomeCinemaTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unreadCountsRequireExplicitUserStatistics() {
        val series = Item("s", "剧集", "Series", unplayedCountKnown = true)
        assertEquals(24L, homeEpisodeStatus(series.copy(unplayed = 24)))
        assertEquals(8L, homeEpisodeStatus(series.copy(unplayed = 8)))
        assertEquals(1L, homeEpisodeStatus(series.copy(unplayed = 1)))
        assertEquals(0L, homeEpisodeStatus(series.copy(played = true)))
        assertNull(homeEpisodeStatus(series.copy(played = true, unplayedCountKnown = false)))
        assertNull(homeEpisodeStatus(series))
        assertNull(homeEpisodeStatus(series.copy(unplayed = -1)))
        assertNull(homeEpisodeStatus(Item("m", "电影", "Movie", unplayed = 1)))
        assertEquals(0L, homeEpisodeStatus(Item("m", "电影", "Movie", played = true)))
        assertEquals(0L, homeEpisodeStatus(Item("ep", "分集", "Episode", played = true)))
        assertNull(homeEpisodeStatus(Item("ep", "分集", "Episode", unplayed = 1)))
    }

    @Test fun communityRatingNeverBecomesDouban() {
        val community = Item.from(Json.parseToJsonElement("""{"id":"m","type_":"Movie","rating":8.6}"""))!!
        assertNull(community.doubanRating)
        for (invalid in listOf("null", "-1", "10.1")) {
            assertNull(Item.from(Json.parseToJsonElement("""{"id":"m","DoubanRating":$invalid}"""))!!.doubanRating)
        }
        assertEquals(8.0, Item.from(Json.parseToJsonElement("""{"id":"m","DoubanRating":8}"""))!!.doubanRating!!, 0.0)
    }

    @Test fun homepageBadgesAreSmallSeparateAndUpdate() {
        var score by mutableStateOf<Double?>(8.6)
        rule.setContent {
            LpTheme(darkOverride = true) {
                MediaCard(Item.from(Json.parseToJsonElement("""{
                    "id":"s","name":"长标题仅一行显示","type_":"Series",
                    "unplayed_item_count":24,"unplayed_count_known":true,
                    "DoubanRating":${score ?: "null"},"rating":9.9,"year":2026
                }"""))!!,
                    null, {})
            }
        }
        rule.onNodeWithContentDescription("24 集未观看").assertExists()
        rule.onNodeWithText("24", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("豆", useUnmergedTree = true).assertIsDisplayed()
        val count = rule.onNodeWithText("24", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rating = rule.onNodeWithText("8.6", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(rating.top > count.bottom)
        rule.onNodeWithText("9.9", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { score = 8.0 }
        rule.onNodeWithText("8.0", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { score = null }
        rule.onNodeWithText("豆", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun continueCardRetainsProgressTimeClickAndMenuWithLessText() {
        var opened = false
        var removed = false
        rule.setContent {
            LpTheme(darkOverride = true) {
                LpRow("继续观看", listOf(Item("ep", "单集说明", "Episode", seriesName = "真实剧名",
                    seasonNo = 1, episodeNo = 7, runtimeSecs = 1800.0, resumeSecs = 600.0)),
                    { null }, { opened = true }, thumb = true, resume = true,
                    menu = { listOf(CardAction("取消观看记录") { removed = true }) })
            }
        }
        rule.onNodeWithText("S1E7", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("单集说明", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("剩余 20:00", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasClickAction() and hasText("剩余 20:00")).performClick()
        rule.runOnIdle { assertTrue(opened) }
        rule.onNode(hasClickAction() and hasText("剩余 20:00")).performTouchInput { longClick() }
        rule.onNodeWithText("取消观看记录").performClick()
        rule.runOnIdle { assertTrue(removed) }
    }
}
