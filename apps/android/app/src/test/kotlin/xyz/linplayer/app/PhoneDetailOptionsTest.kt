package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.tv.episode
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.account
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.displayMediaPath
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneDetailOptionsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private val versionName = "2160p · 导演剪辑版 · 高动态范围收藏版"
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun stream(type: String, lang: String, title: String) = buildJsonObject {
        put("type_", type); put("language", lang); put("title", title); put("codec", "aac")
    }
    private fun core(preferred: Boolean = true) = FakeCore().loggedIn().movie().apply {
        ret("emby.itemMedia", arr(*(1..2).map { n -> buildJsonObject {
            put("id", "v$n"); put("name", if (n == 1) versionName else "1080p 标准版")
            put("preferred", preferred && n == 1); put("container", "mkv"); put("size_bytes", 10000000000L)
            put("streams", arr(
                stream("Audio", "jpn", "日语原声 · 多声道音轨"),
                stream("Audio", "eng", "英语配音 · 多声道音轨"),
                stream("Subtitle", "chi", "简体中文特效字幕 · 完整对白与注释"),
                stream("Subtitle", "eng", "英文字幕"),
            ))
        } }.toTypedArray()))
        ret("prefs.getPrefs", buildJsonObject { put("audio_lang", "jpn"); put("sub_lang", "chi") })
        ret("prefs.setPrefs", JsonNull)
    }
    private fun open(core: FakeCore, route: Route.Detail = Route.Detail("m1", "Movie")) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = route) {
                        composable<Route.Detail> { DetailPage(nav, it) }
                        composable<Route.Player> {
                            val r = it.toRoute<Route.Player>()
                            Text("播放目标：${r.itemId} / ${r.versionId ?: "自动"}" + if (r.fromStart) " / 从头" else "")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    private fun option(label: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription(label) and hasClickAction())
        return rule.onNode(hasContentDescription(label) and hasClickAction()).performScrollTo()
    }
    private fun dialogShot(name: String) = rule.onNode(isDialog()).captureRoboImage("build/detail-options/$name.png")

    @Test fun unknownAudioLabelsAreHiddenAndSubtitlePreviewPrefersSimplified() {
        val core = core().apply {
            ret("prefs.getPrefs", buildJsonObject { put("sub_enabled", true) })
            ret("emby.itemMedia", arr(buildJsonObject {
                put("id", "v1"); put("name", "标准版"); put("preferred", true)
                put("streams", arr(
                    buildJsonObject { put("type_", "Audio"); put("codec", "aac"); put("language", "und")
                        put("title", "未标注"); put("display_title", "未标注"); put("channel_layout", "stereo"); put("is_default", true) },
                    stream("Audio", "eng", "英语配音"),
                    buildJsonObject { put("type_", "Subtitle"); put("codec", "srt"); put("language", "chi")
                        put("title", "繁體中文"); put("is_default", true) },
                    buildJsonObject { put("type_", "Subtitle"); put("codec", "srt"); put("language", "chi")
                        put("title", "简体中文") },
                ))
            }))
        }
        open(core)
        rule.onNodeWithText("AAC stereo（默认）").assertIsDisplayed()
        rule.onNodeWithText("简体中文").assertIsDisplayed()
        option("音轨").performClick()
        rule.onNodeWithText("未标注").assertDoesNotExist()
        rule.onNodeWithText("AAC").assertIsDisplayed()
    }

    @Test fun mediaPathsHideRemoteCredentialsAndKeepLocalPaths() {
        assertEquals("/Videos/fixture.mp4", displayMediaPath("https://dummy:dummy@example.invalid/Videos/fixture.mp4?token=fixture#fragment"))
        assertEquals("/Videos/fixture.mp4", displayMediaPath("//dummy:dummy@example.invalid/Videos/fixture.mp4?token=fixture"))
        assertEquals("/Videos/local.mp4", displayMediaPath("/Videos/local.mp4"))
        assertEquals("C:\\Videos\\local.mp4", displayMediaPath("C:\\Videos\\local.mp4"))
    }

    @Test fun plainOverviewExpandsAndMediaTracksScrollWithFileMetadata() {
        val overview = "这是一段剧情简介，描述人物关系与故事的发展，完整内容可以点击展开阅读。".repeat(8)
        val core = core().apply {
            ret("emby.itemDetail", buildJsonObject {
                put("id", "m1"); put("name", "测试影片"); put("type_", "Movie"); put("overview", overview)
            })
            ret("emby.itemMedia", arr(buildJsonObject {
                put("id", "v1"); put("name", "标准版"); put("preferred", true)
                put("path", "/Videos/fixture/stream.mp4"); put("date_created", "2026-09-30T12:36:00.123Z")
                put("container", "mp4"); put("size_bytes", 5669356830L)
                put("streams", arr(
                    buildJsonObject {
                        put("type_", "Video"); put("index", 0); put("codec", "h264"); put("profile", "Main")
                        put("display_title", "1080p H264"); put("language", "und")
                        put("width", 1920); put("height", 1080); put("frame_rate", 29.97)
                        put("video_range", "SDR"); put("bitrate", 5720000)
                        put("bit_depth", 8); put("color_space", "bt709"); put("pixel_format", "yuv420p")
                    },
                    buildJsonObject {
                        put("type_", "Audio"); put("index", 1); put("codec", "aac"); put("profile", "LC")
                        put("display_title", "AAC stereo（默认）"); put("channel_layout", "stereo")
                        put("bitrate", 125000); put("is_default", true); put("is_forced", false)
                    },
                ))
            }))
        }
        open(core)
        rule.onNodeWithText("简介").assertDoesNotExist()
        val vertical = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        vertical.performScrollToNode(hasContentDescription("展开简介"))
        val collapsed = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithContentDescription("展开简介").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(collapsed) }
        assertEquals(3, collapsed.single().lineCount)
        rule.onNodeWithContentDescription("展开简介").performClick()
        val expanded = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithContentDescription("收起简介").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(expanded) }
        assertFalse(expanded.single().lineCount <= 3)
        rule.onNodeWithContentDescription("收起简介").performClick()
        vertical.performScrollToNode(hasText("媒体信息"))
        rule.onNodeWithText("/Videos/fixture/stream.mp4").assertIsDisplayed()
        rule.onNodeWithText("添加于", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("detail.media.tracks").performScrollTo()
        rule.onNodeWithText("位深: 8").assertIsDisplayed()
        rule.onNodeWithText("色域: bt709").assertIsDisplayed()
        rule.onNodeWithText("像素格式: yuv420p").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/media-tracks-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/detail-options/media-tracks-dark.png")
        rule.onNodeWithText("类型: Audio").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("比特率: 125 kbps").assertIsDisplayed()
        rule.onNodeWithText("强制: false").assertIsDisplayed()
    }

    @Test fun compactOptionsHideAbsentSubtitlesAndSingleLineAndShowExactTimeOnce() {
        val core = core().apply {
            ret("emby.itemDetail", buildJsonObject {
                put("id", "m1"); put("name", "短片"); put("type_", "Movie")
                put("year", 2026); put("premiere_date", "2026-08-02T00:00:00.000Z")
                put("runtime_secs", 86); put("resume_secs", 30)
            })
            ret("account.listAccounts", arr(account("http://emby-a.invalid", "服务器 A", "", true,
                listOf("主线路" to "https://media.example.net"))))
            ret("emby.itemMedia", arr(buildJsonObject {
                put("id", "v1"); put("name", "标准版"); put("preferred", true)
                put("streams", arr(
                    buildJsonObject { put("type_", "Video"); put("codec", "hevc"); put("height", 1080) },
                    buildJsonObject { put("type_", "Audio"); put("codec", "aac")
                        put("channel_layout", "stereo"); put("is_default", true) },
                ))
            }))
            ret("emby.setPlayed", JsonNull)
        }
        open(core)
        rule.onNodeWithText("1080p HEVC").assertIsDisplayed()
        rule.onNodeWithText("AAC stereo（默认）").assertIsDisplayed()
        rule.onNodeWithContentDescription("字幕").assertDoesNotExist()
        rule.onNodeWithContentDescription("线路").assertDoesNotExist()
        rule.onNodeWithText("年份").assertDoesNotExist()
        rule.onNodeWithText("分钟").assertDoesNotExist()
        rule.onAllNodesWithText("2026/08/02 · 1分26秒").assertCountEquals(1)
        rule.onNodeWithText("还剩", substring = true).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/detail-options/compact-light.png")
        rule.onNodeWithContentDescription("标已看").performClick()
        rule.onNodeWithContentDescription("标未看").assertIsDisplayed()
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/detail-options/compact-dark-watched.png")
    }

    @Test fun episodeOpensAtCurrentEpisodeAndDownloadIsInToolbar() {
        val core = core().episode().apply {
            ret("emby.seriesSeasons", arr(item("sh", "第 1 季", "Season")))
            ret("download.enqueue", JsonNull)
            ret("emby.setPlayed", JsonNull)
        }
        open(core, Route.Detail("sh8", "Episode"))
        rule.onNodeWithText("幕府将军").assertIsDisplayed()
        rule.onNodeWithText("S1E8：大地之心").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/episode-header-light.png")
        rule.runOnIdle { dark.value = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/detail-options/episode-header-dark.png")
        rule.runOnIdle { dark.value = false }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("来自第1季"))
        rule.onNodeWithText("当前集").assertIsDisplayed()
        rule.onNodeWithText("EP 08").assertIsDisplayed()
        rule.onNodeWithText("EP 01").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/detail-options/episode-current-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/detail-options/episode-current-dark.png")
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performScrollToIndex(12)
        val vertical = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        vertical.performScrollToNode(hasText("相似推荐"))
        vertical.performScrollToNode(hasText("来自第1季"))
        rule.onNodeWithText("EP 13").assertIsDisplayed()
        rule.onNodeWithContentDescription("收藏").assertDoesNotExist()
        rule.onNodeWithText("收藏").assertDoesNotExist()
        rule.onNodeWithContentDescription("标已看").performClick()
        assertEquals("sh8", core.calls.last { it.first == "emby.setPlayed" }.second.str("item_id"))
        rule.onNodeWithContentDescription("下载").performClick()
        assertEquals("sh8", core.calls.last { it.first == "download.enqueue" }.second.str("item_id"))
    }

    @Test fun episodeOverviewPrecedesSeasonAndCastRolesAreShown() {
        val core = core().episode().apply {
            ret("emby.itemDetail", buildJsonObject {
                put("id", "sh8"); put("name", "大地之心"); put("type_", "Episode")
                put("series_name", "幕府将军"); put("series_id", "s2"); put("season_id", "sh")
                put("season_no", 1); put("episode_no", 8); put("runtime_secs", 3660)
                put("overview", "本集简介")
                put("people", arr(buildJsonObject { put("id", "actor1"); put("name", "演员甲"); put("role", "角色甲") },
                    buildJsonObject { put("id", "actor2"); put("name", "演员乙") }))
            })
            ret("emby.seriesSeasons", arr(buildJsonObject {
                put("id", "sh"); put("name", "冬之章"); put("index_no", 1)
            }))
        }
        open(core, Route.Detail("sh8", "Episode"))
        val play = rule.onNodeWithTag("detail.play").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(play.width < 180f)
        val vertical = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        vertical.performScrollToNode(hasText("本集简介"))
        val overview = rule.onNodeWithText("本集简介").fetchSemanticsNode().boundsInRoot
        val season = rule.onNodeWithText("来自第1季：冬之章").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(overview.bottom < season.top)
        rule.onNodeWithText("未看", substring = true).assertDoesNotExist()
        rule.onNodeWithText("还剩", substring = true).assertDoesNotExist()
        rule.onNodeWithText("剩余 24:48").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/episode-reordered.png")
        vertical.performScrollToNode(hasText("角色甲"))
        rule.onNodeWithText("角色甲").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/cast-roles.png")
    }

    @Test fun seasonLabelsUseExplicitNumberWithoutDuplicatingDefaultNames() {
        val season = xyz.linplayer.app.data.Item("season", "冬之章", "Season", seasonNo = 1)
        assertEquals("第1季：冬之章", xyz.linplayer.app.ui.pages.seasonLabel(season))
        assertEquals("第1季", xyz.linplayer.app.ui.pages.seasonLabel(season.copy(name = "")))
        assertEquals("第1季", xyz.linplayer.app.ui.pages.seasonLabel(season.copy(name = "第 1 季")))
        assertEquals("第1季", xyz.linplayer.app.ui.pages.seasonLabel(season.copy(name = "Season 1")))
        assertEquals("第0季：特别篇", xyz.linplayer.app.ui.pages.seasonLabel(season.copy(name = "特别篇", seasonNo = 0)))
    }

    @Test fun seasonMenuChangesDisplayedSeasonAndRequestsItsEpisodes() {
        val core = core().episode().apply {
            ret("emby.seriesSeasons", arr(item("sh", "第 1 季", "Season"), item("sh2", "第 2 季", "Season")))
        }
        open(core, Route.Detail("sh8", "Episode"))
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("来自第1季"))
        rule.onNodeWithContentDescription("选择季").performClick()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onNodeWithText("第2季").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/season-menu-light.png")
        rule.runOnIdle { dark.value = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/detail-options/season-menu-dark.png")
        rule.onNodeWithText("第2季").performClick()
        rule.onNodeWithText("来自第2季").assertIsDisplayed()
        assertEquals("sh2", core.calls.last { it.first == "emby.seasonEpisodes" }.second.str("parent_id"))
    }

    @Test fun restartMenuIsAnchoredAndKeepsPreferredVersion() {
        open(core())
        rule.onNodeWithTag("detail.play").assertIsDisplayed()
        val progress = rule.onNodeWithTag("detail.play").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertFalse(progress.current <= 0f || progress.current >= 1f)
        rule.onNodeWithContentDescription("播放更多操作").performClick()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onNodeWithText("从头开始播放").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/restart-light.png")
        rule.runOnIdle { dark.value = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/detail-options/restart-dark.png")
        rule.onNodeWithText("从头开始播放").performClick()
        rule.onNodeWithText("播放目标：m1 / v1 / 从头").assertIsDisplayed()
    }

    @Test fun versionDialogAndManualSelectionKeepPlaybackTarget() {
        open(core())
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("继续观看", substring = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        rule.onNodeWithText("2小时46分0秒", substring = true).assertIsDisplayed()
        rule.onNodeWithText("剩余：2小时16分12秒", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/movie-light-large.png")
        val layout = layouts.single()
        assertFalse("大字号续播按钮不能截断",
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        option("版本").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/options-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/detail-options/options-dark-large.png")
        option("版本").performClick()
        rule.onNodeWithText("选择版本").assertIsDisplayed()
        rule.onNodeWithText("正则选中").assertIsDisplayed()
        dialogShot("version-dark-large")
        rule.runOnIdle { dark.value = false }
        dialogShot("version-light-large")
        rule.onNodeWithText("1080p 标准版").performClick()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        rule.onNodeWithText("继续观看", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("播放目标：m1 / v2").assertIsDisplayed()
    }

    @Test fun languageDialogsPreserveOtherPreference() {
        val core = core()
        open(core)
        option("音轨").performClick()
        dialogShot("audio-light-large")
        rule.onNode(hasText("英语") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        var saved = core.calls.last { it.first == "prefs.setPrefs" }.second
        assertEquals("eng", saved.str("audio_lang")); assertEquals("chi", saved.str("sub_lang"))
        option("字幕").performClick()
        rule.onNode(hasText("简体中文特效字幕 · 完整对白与注释") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        dialogShot("subtitle-light-large")
        rule.runOnIdle { dark.value = true }
        dialogShot("subtitle-dark-large")
        rule.onNodeWithText("不显示字幕").performClick()
        saved = core.calls.last { it.first == "prefs.setPrefs" }.second
        assertEquals("eng", saved.str("audio_lang")); assertEquals("", saved.str("sub_lang"))
        assertEquals("false", saved?.get("sub_enabled").toString())
    }

    @Test fun displayFallbackDoesNotForceFirstVersion() {
        open(core(preferred = false))
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        rule.onNodeWithText("继续观看", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("播放目标：m1 / 自动").assertIsDisplayed()
    }
}
