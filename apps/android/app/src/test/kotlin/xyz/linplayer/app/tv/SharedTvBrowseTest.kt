package xyz.linplayer.app.tv

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import xyz.linplayer.app.data.str
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache

/** 从实际TV根进入，共用页面与遥控路径必须同时成立。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class SharedTvBrowseTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var core: FakeCore
    private lateinit var app: AppState
    private lateinit var back: OnBackPressedDispatcher

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun mount(configure: (FakeCore) -> Unit = {}) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        core = FakeCore().loggedIn().movie().also(configure)
        app = AppState(core, scope)
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvRoot(app)
        }
        rule.waitForIdle()
    }

    private fun localFiles(core: FakeCore) {
        core.ret("emby.currentSession", JsonNull)
        core.ret("account.listAccounts", arr(buildJsonObject {
            put("server", "local-library"); put("name", "本地影片"); put("active", true); put("source_kind", "local")
        }))
        core.ret("source.currentSource", buildJsonObject { put("server_id", "local-library"); put("server_name", "本地影片"); put("kind", "local") })
        core.on("source.listDir") { args ->
            if (args.str("dir_id") == "folder") arr(buildJsonObject {
                put("id", "episode"); put("name", "子目录影片.mkv"); put("is_video", true)
            }) else arr(
                buildJsonObject { put("id", "folder"); put("name", "剧集目录"); put("is_dir", true) },
                buildJsonObject { put("id", "movie"); put("name", "电影.mkv"); put("is_video", true) },
                buildJsonObject { put("id", "note"); put("name", "说明.txt"); put("is_video", false) },
            )
        }
    }

    private fun openFilesFromSettings() {
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginNav.host!!.push("settings", JsonObject(emptyMap()), false) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("文件浏览"))
        rule.onNodeWithText("文件浏览").performClick()
        rule.waitForIdle()
    }

    @Test fun localHomeOffersFileBrowserWithoutEmbyRequests() {
        mount(::localFiles)
        rule.onNodeWithText("打开文件夹").assertIsDisplayed()
        press(rule, Key.DirectionDown)
        rule.onNodeWithText("打开文件夹").assertIsFocused()
        rule.onRoot().captureRoboImage("build/shared-tv/local-home.png")
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("电影.mkv").assertIsDisplayed()
        assertFalse(core.calls.any { it.first in setOf("emby.listResume", "emby.views", "emby.listLatest") })
        rule.onRoot().captureRoboImage("build/shared-tv/local-files.png")
    }

    @Test fun localHomeUpdatesWhenSwitchingFromEmbyAndBack() {
        mount()
        rule.runOnIdle { localFiles(core); kotlinx.coroutines.runBlocking { app.refreshSession() } }
        rule.waitForIdle()
        rule.onNodeWithText("打开文件夹").assertIsDisplayed()
        assertEquals("local", app.localSource.value?.kind)
        assertNull(app.activeSource.value)
        rule.runOnIdle { core.loggedIn(); kotlinx.coroutines.runBlocking { app.refreshSession() } }
        rule.waitForIdle()
        assertNull(app.localSource.value)
        rule.onNodeWithText("打开文件夹").assertDoesNotExist()
        rule.onNodeWithContentDescription("谎言的代价").assertExists()
    }

    @Test fun televisionFileBrowserOnlyShowsFoldersAndVideos() {
        mount(::localFiles)
        openFilesFromSettings()
        rule.onNodeWithText("剧集目录").assertIsDisplayed()
        rule.onNodeWithText("电影.mkv").assertIsDisplayed()
        rule.onNodeWithText("说明.txt").assertDoesNotExist()
    }

    @Test fun televisionBackLeavesFolderBeforeLeavingFileBrowser() {
        mount(::localFiles)
        openFilesFromSettings()
        rule.onNodeWithText("剧集目录").performClick()
        rule.onNodeWithText("子目录影片.mkv").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("电影.mkv").assertIsDisplayed()
        rule.onNodeWithText("子目录影片.mkv").assertDoesNotExist()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("文件浏览").assertExists()
        rule.onNodeWithText("电影.mkv").assertDoesNotExist()
    }

    @Test fun actualTvEntryUsesSharedHomeAndTopNavigation() {
        mount()
        rule.onNodeWithText("聚合视界").assertIsDisplayed()
        rule.onNodeWithTag("phone.tabs").assertDoesNotExist()
        assertFalse(core.calls.any { it.first == "emby.listRandom" || it.first == "emby.listNextUp" })
        rule.onRoot().captureRoboImage("build/shared-tv/home.png")
    }

    @Test fun directionsOnlyMoveFocusAndConfirmOpensSharedAggregateWithoutDownloads() {
        mount()
        rule.onNodeWithTag("rail.1").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag("rail.4").assertIsFocused().assertIsNotSelected()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("rail.4").assertIsSelected()
        rule.onNodeWithText("排行榜").assertIsDisplayed()
        rule.onNodeWithText("日历").assertIsDisplayed()
        rule.onNodeWithText("下载").assertDoesNotExist()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("rail.4").assertIsNotFocused()
        rule.onRoot().captureRoboImage("build/shared-tv/aggregate.png")
    }

    @Test fun cardMenuAndDetailReturnKeepRemoteFocusAndHideDownload() {
        mount()
        val card = rule.onNodeWithContentDescription("谎言的代价")
        card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        card.assertIsFocused()
        press(rule, Key.Menu)
        rule.onNodeWithText("标为已看").assertIsDisplayed()
        rule.onNodeWithText("下载").assertDoesNotExist()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.hero").assertIsDisplayed()
        val hero = rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("TV详情头图应在首屏内留出播放区域", hero.height < screen.height * .65f)
        rule.onNodeWithContentDescription("下载").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/shared-tv/detail.png")
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused()
    }

    @Test fun directionalHomeCardOpensDetailAndRestoresTheSameCard() {
        mount()
        press(rule, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionLeft)
        val card = rule.onNodeWithContentDescription("谎言的代价").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.hero").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused()
    }

    @Test fun castOpensPersonWorksAndReturnsToSameActor() {
        mount { core ->
            core.ret("emby.personDetail", buildJsonObject { put("id", "p0"); put("name", "提莫西·查拉梅") })
            core.ret("emby.personItemsPage", buildJsonObject {
                put("items", arr(item("person-film", "人物关联作品")))
                put("total", 1); put("next_index", 1); put("has_more", false)
            })
        }
        rule.onNodeWithContentDescription("谎言的代价").performClick()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag("detail.people"))
        val actor = rule.onNodeWithTag("detail.person.p0")
        actor.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        actor.assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("人物作品").assertIsDisplayed()
        val work = rule.onNodeWithContentDescription("人物关联作品")
        work.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        work.assertIsFocused()
        assertEquals("p0", core.calls.last { it.first == "emby.personItemsPage" }.second.str("person_id"))
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.hero").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        work.assertIsFocused()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        actor.assertIsFocused()
    }

    @Test fun serverNameIsSingleManagementEntryAndPluginCannotOpenDownloads() {
        mount()
        rule.onAllNodesWithText("服务器 A").assertCountEquals(1)
        rule.onNodeWithTag("rail.1").assertIsFocused()
        press(rule, Key.DirectionLeft, Key.DirectionCenter)
        rule.onNodeWithText("服务器", substring = false).assertIsDisplayed()
        rule.runOnIdle {
            assertFalse(xyz.linplayer.app.plugin.PluginNav.host!!.push("downloads", kotlinx.serialization.json.JsonObject(emptyMap()), false))
        }
        assertFalse(core.calls.any { it.first == "download.list" })
    }
}
