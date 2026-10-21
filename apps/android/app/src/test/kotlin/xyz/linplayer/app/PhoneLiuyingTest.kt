package xyz.linplayer.app

import android.app.Application
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.pages.LiuyingContent
import xyz.linplayer.app.ui.theme.LpTheme

/** 原生入口只消费入库候选；能力缺失与空库不能退回网页。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneLiuyingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private var showing by mutableStateOf(true)

    @After fun clean() { PageCache.clear(); scope.cancel(); if (::app.isInitialized) app.bg.cancel() }

    private fun open(enabled: Boolean = true, populated: Boolean = false, more: Boolean = false, fontScale: Float = 1f): FakeCore {
        PageCache.clear()
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", enabled) }) })
            ret("emby.extLiuYingLabels", arr(
                buildJsonObject { put("name", "tg-3398168133"); put("title", "抖奶"); put("kind", "channel"); put("count", 9) },
                buildJsonObject { put("name", "side_breast_exposure"); put("title", "露左右"); put("kind", "exposure"); put("count", 4) },
            ))
            ret("emby.extLiuYingItems", buildJsonObject {
                put("items", if (populated) arr(*listOf("ly-work-1", "ly-work-2").map { id ->
                    buildJsonObject { put("item_id", id); put("title", id) }
                }.toTypedArray()) else arr())
                put("has_more", more)
            })
            on("emby.itemDetail") { a -> buildJsonObject { put("id", a.str("item_id")); put("type_", "Movie"); put("name", "入库视频") } }
            ret("player.stopPlayback", JsonNull)
        }
        app = AppState(core, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(privateSpace = true) { CompositionLocalProvider(LocalApp provides app,
            LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            if (showing) LiuyingContent({ showing = false })
        } } }
        advance(rule, 1000)
        return core
    }

    private fun openFilters() {
        rule.onNodeWithTag("liuying.filter").performClick(); advance(rule, 300)
    }

    private fun moveVideo(next: Boolean, wait: Long = 600) {
        openFilters()
        rule.onNodeWithTag(if (next) "liuying.next" else "liuying.previous").performScrollTo().performClick()
        advance(rule, wait)
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
    }

    @Test fun missingCapabilityDoesNotRequestVideosOrLoadWebPage() {
        val core = open(enabled = false)
        rule.onNodeWithText("服务器未提供流影能力").assertIsDisplayed()
        rule.onNodeWithTag("liuying.web").assertDoesNotExist()
        assertFalse(core.calls.any { it.first == "emby.extLiuYingItems" || it.first == "player.play" })
        assertEquals(1, core.calls.count { it.first == "emby.extCapabilities" })
        assertNull(core.calls.single { it.first == "emby.extCapabilities" }.second?.get("refresh"))
    }

    @Test fun filterSheetStartsHiddenAndOpeningDoesNotRestartPlayback() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        rule.onNodeWithTag("liuying.label.tg-3398168133").assertDoesNotExist()
        rule.onNodeWithTag("liuying.filter").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
    }

    @Test fun sheetBackAndOutsideCloseWithoutExitingOrRestarting() {
        val core = open(populated = true)
        openFilters()
        rule.onNodeWithText("筛选视频").performTouchInput { click() }; advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        rule.runOnIdle {
            val manager = Shadow.extract<ShadowWindowManagerImpl>(
                RuntimeEnvironment.getApplication().getSystemService(WindowManager::class.java))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        assertTrue(showing)
        openFilters()
        rule.onNode(isPopup()).performTouchInput { click(Offset(width / 2f, 8f)) }; advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        assertTrue(showing)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
    }

    /** 真实流影入口和播放器组合；宿主占位背景不是解码画面，截图仅验证完整控件接线。 */
    private fun nativeLayout(name: String, fontScale: Float = 1f) {
        val core = open(populated = true, fontScale = fontScale)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { core.tick(1.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(1.25) }; advance(rule, 300)
        for (tag in listOf("favorite", "filter", "mode", "sound", "progress"))
            rule.onNodeWithTag("liuying.$tag").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/liuying-mobile/$name-no-decoded-frame.png")
        openFilters()
        rule.onNodeWithTag("liuying.next").performScrollTo().performTouchInput { click() }; advance(rule, 600)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(1, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w873dp-h393dp-land-mdpi")
    fun actualLandscapePageKeepsAllActionsAndSheetNextReachable() = nativeLayout("native-landscape")

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun actualSmallPageLargeTextKeepsAllActionsReachable() = nativeLayout("native-small-large-text", 1.3f)

    @Test fun favoritesAndClearRemainUsableAcrossFilterReloads() {
        val core = open(populated = true)
        openFilters()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.label.tg-3398168133").assertIsSelected()
        rule.onNodeWithTag("liuying.favorites").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.favorites").assertIsSelected()
        assertTrue(core.calls.last { it.first == "emby.extLiuYingItems" }.second.bool("favorite"))
        rule.onNodeWithTag("liuying.clear").performScrollTo().performClick(); advance(rule, 300)
        val query = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("", query.str("channel")); assertEquals("", query.str("exposure"))
        assertFalse(query.bool("favorite"))
        rule.onNodeWithTag("liuying.favorites").assertIsNotSelected()
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
    }

    @Test fun sheetDraggingDoesNotSeekOrSwitchAndProgressOnlySeeks() {
        val core = open(populated = true)
        rule.runOnIdle { core.tick(30.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(30.25) }; advance(rule, 300)
        val seeks = core.calls.count { it.first == "player.seek" }
        openFilters()
        rule.onNodeWithTag("liuying.actions").performTouchInput { swipeUp(); swipeDown() }; advance(rule, 300)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals(seeks, core.calls.count { it.first == "player.seek" })
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.progress").performTouchInput {
            swipe(Offset(width * .2f, height / 2f), Offset(width * .7f, height / 2f))
        }; advance(rule, 400)
        assertEquals(seeks + 1, core.calls.count { it.first == "player.seek" })
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun emptyLibraryAndFeedSwitchStayNativeAndUseCachedCapability() {
        val core = open()
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        assertEquals("dance", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("tab"))
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 400)
        val args = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("adult", args.str("feed")); assertEquals("", args.str("tab"))
        assertEquals("", args.str("channel")); assertEquals("", args.str("exposure"))
        assertEquals(1, core.calls.count { it.first == "emby.extCapabilities" })
        assertFalse(core.calls.any { it.first == "player.play" })
    }

    @Test fun channelAndExposureIntersectAndSourceSwitchClearsBoth() {
        val core = open()
        openFilters()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        rule.onNodeWithTag("liuying.label.side_breast_exposure").performScrollTo().performClick(); advance(rule, 300)
        val combined = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("tg-3398168133", combined.str("channel"))
        assertEquals("side_breast_exposure", combined.str("exposure"))
        assertEquals(3, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        val adult = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("", adult.str("channel")); assertEquals("", adult.str("exposure"))
        assertEquals(4, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun favoriteChangesOnlyAfterServerSuccess() {
        val core = open(populated = true)
        core.on("emby.setFavorite") { throw xyz.linplayer.app.core.CoreException("E_NETWORK", "收藏失败", true) }
        rule.onNodeWithText("收藏", useUnmergedTree = true).performClick(); advance(rule, 300)
        rule.onNodeWithText("取消收藏").assertDoesNotExist()
        core.ret("emby.setFavorite", JsonNull)
        rule.onNodeWithText("收藏", useUnmergedTree = true).performClick(); advance(rule, 300)
        rule.onNodeWithText("取消收藏").assertExists()
        val write = core.calls.last { it.first == "emby.setFavorite" }.second
        assertEquals("ly-work-1", write.str("item_id")); assertTrue(write.bool("fav"))
        assertEquals(2, core.calls.count { it.first == "emby.setFavorite" })
    }

    @Test fun completedVideoAdvancesOnceThroughExistingStopBarrier() {
        val core = open(populated = true)
        rule.runOnIdle { core.tick(3658.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3658.25) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3660.0, eof = true) }; advance(rule, 800)
        assertEquals(2, core.calls.count { it.first == "player.play" })
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        val play = core.calls.indexOfLast { it.first == "player.play" }
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } in 0 until play)
        rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status", buildJsonObject {
            put("item_id", "ly-work-1"); put("position", 3660.0); put("duration", 3660.0); put("eof", true)
        })) }
        advance(rule, 500)
        assertEquals(2, core.calls.count { it.first == "player.play" })
    }

    @Test fun verticalSwipeMovesWithinSelectedFeedAndCanReturn() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .45f, height * .3f), Offset(width * .45f, height * .65f))
        }; advance(rule, 300)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .45f, height * .65f), Offset(width * .45f, height * .3f))
        }; advance(rule, 800)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .45f, height * .3f), Offset(width * .45f, height * .65f))
        }; advance(rule, 800)
        assertEquals("ly-work-1", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(1, core.calls.count { it.first == "emby.extLiuYingItems" })
        assertFalse(core.calls.any { it.first == "player.setVolume" })
    }

    @Test fun entryAutoPlaysAndManualNextStopsPreviousBeforeStarting() {
        val core = open(populated = true)
        assertEquals("ly-work-1", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertTrue(core.calls.last { it.first == "player.play" }.second.bool("from_start"))
        moveVideo(true, 800)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        val play = core.calls.indexOfLast { it.first == "player.play" }
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } in 0 until play)
        assertEquals(1, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.runOnIdle { showing = false }; advance(rule, 400)
        assertEquals(2, core.calls.count { it.first == "player.stopPlayback" })
    }

    @Test fun loopReplaysCurrentInsteadOfAdvancingAndLabelsOnlyAppearInSheet() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.mode").performClick(); advance(rule, 300)
        rule.onNodeWithText("循环当前").assertExists()
        rule.runOnIdle { core.tick(3658.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3658.25) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3660.0, eof = true) }; advance(rule, 800)
        assertEquals(2, core.calls.count { it.first == "player.play" })
        assertEquals("ly-work-1", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(1, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithTag("liuying.next").assertDoesNotExist()
        openFilters()
        rule.onNodeWithTag("liuying.next").performScrollTo()
        rule.onNodeWithTag("liuying.next").assertExists()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo(); advance(rule, 300)
        rule.onNodeWithText("9 部").assertIsDisplayed()
    }

    @Test fun pageBoundaryKeepsSeedAndPreviousPageAndFailedRequestRetriesSamePage() {
        val core = open(populated = true, more = true)
        var fail = true
        core.on("emby.extLiuYingItems") { a ->
            if (fail) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分页失败", true)
            buildJsonObject {
                put("items", arr(buildJsonObject { put("item_id", "ly-work-3"); put("title", "第三条") }))
                put("has_more", false); put("offset", a.long("offset") ?: 0)
            }
        }
        moveVideo(true, 500)
        moveVideo(true, 500)
        val failed = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals(2, failed.long("offset")!!.toInt())
        val seed = failed.str("seed")
        assertEquals(core.calls.first { it.first == "emby.extLiuYingItems" }.second.str("seed"), seed)
        rule.runOnIdle { fail = false }
        rule.onNodeWithText("重试").performClick(); advance(rule, 800)
        assertEquals(3, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("第三条").assertExists()
        assertEquals(seed, core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("seed"))
        assertEquals("ly-work-3", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        moveVideo(false)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(3, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun muteSurvivesVideoSwitchAndFeedControlsRender() {
        val core = open(populated = true)
        core.ret("player.setVolume", JsonNull)
        rule.runOnIdle { core.tick(1.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(1.25) }; advance(rule, 300)
        rule.onRoot().captureRoboImage("build/liuying-mobile/phone-feed.png")
        rule.onNodeWithText("静音").performClick(); advance(rule, 300)
        assertEquals(0, core.calls.last { it.first == "player.setVolume" }.second.long("volume")!!.toInt())
        moveVideo(true)
        rule.onNodeWithText("开声音").assertExists()
        val play = core.calls.indexOfLast { it.first == "player.play" }
        val volume = core.calls.indexOfLast { it.first == "player.setVolume" }
        assertTrue(volume in 0 until play)
        assertEquals(0, core.calls[volume].second.long("volume")!!.toInt())
    }

    @Test fun emptyNextPageCanReturnToRetainedVideo() {
        val core = open(populated = true, more = true)
        core.ret("emby.extLiuYingItems", buildJsonObject {
            put("items", arr()); put("has_more", false)
        })
        moveVideo(true, 500)
        moveVideo(true, 500)
        rule.onNodeWithText("暂无可播放的入库资源").assertExists()
        openFilters()
        rule.onNodeWithTag("liuying.previous").performScrollTo().assertIsEnabled().performClick(); advance(rule, 600)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun horizontalGestureSeeksWithoutChangingVideo() {
        val core = open(populated = true)
        rule.runOnIdle { core.tick(30.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(30.25) }; advance(rule, 300)
        val before = core.calls.count { it.first == "player.seek" }
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .08f, height * .45f), Offset(width * .55f, height * .45f))
        }
        advance(rule, 400)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals(before + 1, core.calls.count { it.first == "player.seek" })
        assertFalse(core.calls.any { it.first == "player.setVolume" })
    }
}
