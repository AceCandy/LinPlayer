package xyz.linplayer.app.tv

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import xyz.linplayer.app.core.CorePort
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
import xyz.linplayer.app.data.UiPrefs

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
    private lateinit var activity: android.app.Activity
    private val oldHideLibraries = UiPrefs.hideHomeLibraries.value
    private val oldTheme = UiPrefs.theme.value

    @After fun clean() {
        scope.cancel(); if (::app.isInitialized) app.bg.cancel(); PageCache.clear()
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), oldHideLibraries)
        UiPrefs.setTheme(ApplicationProvider.getApplicationContext(), oldTheme)
    }

    private fun mount(intercept: ((FakeCore) -> CorePort)? = null, fontScale: Float? = null, configure: (FakeCore) -> Unit = {}) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        core = FakeCore().loggedIn().movie().also(configure)
        app = AppState(intercept?.invoke(core) ?: core, scope)
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    (fontScale?.let { androidx.compose.ui.unit.Density(density.density, it) } ?: density),
            ) { TvRoot(app) }
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
        mount(configure = ::localFiles)
        rule.onNodeWithText("打开文件夹").assertIsDisplayed()
        rule.onNodeWithTag("nav.refresh").assertDoesNotExist()
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
        rule.onNodeWithTag("nav.refresh").assertDoesNotExist()
    }

    @Test fun televisionFileBrowserOnlyShowsFoldersAndVideos() {
        mount(configure = ::localFiles)
        openFilesFromSettings()
        rule.onNodeWithText("剧集目录").assertIsDisplayed()
        rule.onNodeWithText("电影.mkv").assertIsDisplayed()
        rule.onNodeWithText("说明.txt").assertDoesNotExist()
    }

    @Test fun televisionBackLeavesFolderBeforeLeavingFileBrowser() {
        mount(configure = ::localFiles)
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

    @Test fun rootBackKeepsHomeAndDetailReturnDoesNotArmExit() {
        mount()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        assertFalse(activity.isFinishing)
        rule.onNodeWithContentDescription("谎言的代价").assertExists()
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("谎言的代价").assertExists()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        assertFalse(activity.isFinishing)
        rule.onNodeWithContentDescription("谎言的代价").assertExists()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        assertTrue(activity.isFinishing)
    }

    @Test fun seriesRemoteUpRestoresWholeHero() {
        mount(configure = ::seriesDetails)
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "s1", "type" to "Series"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        val initial = rule.onNodeWithTag("detail.hero").fetchSemanticsNode().positionInRoot.y
        repeat(3) { press(rule, Key.DirectionDown) }
        rule.onNodeWithTag("detail.play").assertIsNotFocused()
        repeat(12) {
            if (rule.onAllNodes(hasTestTag("detail.play") and isFocused()).fetchSemanticsNodes().isEmpty())
                press(rule, Key.DirectionUp)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        assertEquals(initial, rule.onNodeWithTag("detail.hero").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test fun switchingFromFavoritesRestoresHomeHeroWithoutManualScroll() {
        mount { it.ret("emby.cachedItems", arr(item("hero", "首页轮播", year = 2026))) }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("home.banners").fetchSemanticsNodes().isNotEmpty() }
        val initial = rule.onNodeWithTag("home.banners").fetchSemanticsNode().positionInRoot.y
        repeat(4) { press(rule, Key.DirectionDown) }
        rule.onNodeWithTag("rail.1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight)
        rule.onNodeWithTag("rail.3").assertIsFocused()
        press(rule, Key.DirectionLeft)
        rule.onNodeWithTag("rail.1").assertIsFocused()
        assertEquals(initial, rule.onNodeWithTag("home.banners").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test fun focusedNavigationTabKeepsShortLineWithoutPillInDarkTheme() = checkNavigationTab("dark")
    @Test fun focusedNavigationTabKeepsShortLineWithoutPillInLightTheme() = checkNavigationTab("light")

    private fun checkNavigationTab(theme: String) {
        UiPrefs.setTheme(ApplicationProvider.getApplicationContext(), theme)
        mount()
        press(rule, Key.DirectionRight, Key.DirectionLeft)
        rule.onNodeWithTag("rail.1").assertIsFocused().assertIsSelected()
        val bounds = rule.onNodeWithTag("rail.1").fetchSemanticsNode().boundsInRoot
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val y = bounds.center.y.toInt()
        assertEquals("栏目聚焦仍保持透明底，不恢复胶囊或描边",
            pixels[bounds.right.toInt() + 4, y].luminance(),
            pixels[bounds.left.toInt() + 10, y].luminance(), .04f)
        rule.onRoot().captureRoboImage("build/shared-tv/focus-navigation-$theme.png")
    }

    @Test fun homeDownFromMiddleResumeLeavesResumeRow() {
        mount()
        press(rule, Key.DirectionDown, Key.DirectionDown)
        rule.onNodeWithContentDescription("谎言的代价").assertIsFocused()
        repeat(4) { press(rule, Key.DirectionRight) }
        rule.onNodeWithContentDescription("黑暗森林").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("黑暗森林").assertIsNotFocused()
        rule.onNode(isFocused()).assertIsDisplayed()
    }

    @Test fun compactLibraryHeaderMovesDownToFirstPoster() {
        mount()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("电影") and hasClickAction())
        rule.onNode(hasText("电影") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val header = rule.onNode(hasText("电影") and isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("栏目焦点只覆盖左侧标题", header.width < 240f)
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("沙丘 2").assertIsFocused()
        repeat(7) { press(rule, Key.DirectionRight) }
        rule.onNode(hasText("电影") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("沙丘 2").assertIsFocused()
    }

    @Test fun hiddenLibrariesMakeResumeTheFirstContentRow() {
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), true)
        mount()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("谎言的代价").assertIsFocused()
    }

    @Test fun homeUpToNavigationRestoresWholeHero() = checkHomeReturnToTop(false, false)
    @Test fun hiddenLibrariesUpToNavigationRestoresWholeHero() = checkHomeReturnToTop(true, false)
    @Test fun emptyResumeUpToNavigationRestoresWholeHero() = checkHomeReturnToTop(false, true)
    @Test fun hiddenLibrariesAndEmptyResumeUpToNavigationRestoresWholeHero() = checkHomeReturnToTop(true, true)

    private fun checkHomeReturnToTop(hideLibraries: Boolean, emptyResume: Boolean) {
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), hideLibraries)
        mount {
            it.ret("emby.cachedItems", arr(item("hero", "首页轮播", year = 2026)))
            if (emptyResume) it.ret("emby.listResume", arr())
        }
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(0)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("home.banners").fetchSemanticsNodes().isNotEmpty() }
        val initial = rule.onNodeWithTag("home.banners").fetchSemanticsNode().positionInRoot.y
        repeat(4) { press(rule, Key.DirectionDown) }
        rule.onNodeWithTag("rail.1").assertIsNotFocused()
        val scrolledHero = rule.onAllNodesWithTag("home.banners").fetchSemanticsNodes().firstOrNull()
        assertTrue("应先遥控下滚，不能只验证停在首页顶端", scrolledHero == null || scrolledHero.positionInRoot.y < initial)
        repeat(12) {
            if (rule.onAllNodes(hasTestTag("rail.1") and isFocused()).fetchSemanticsNodes().isEmpty())
                press(rule, Key.DirectionUp)
        }
        rule.onNodeWithTag("rail.1").assertIsFocused()
        rule.waitForIdle()
        assertEquals("回到导航时轮播应回到原始起点", initial,
            rule.onNodeWithTag("home.banners").fetchSemanticsNode().positionInRoot.y, 1f)
        rule.onRoot().captureRoboImage("build/shared-tv/home-return-$hideLibraries-$emptyResume.png")
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("rail.1").assertIsNotFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("rail.1").assertIsFocused()
    }

    @Test fun televisionColumnsReadWindowAndKeepPluginOverride() {
        val theme = xyz.linplayer.app.ui.theme.PluginTheme
        val savedColumns = buildJsonObject {
            for ((name, width) in listOf("compact" to 411, "medium" to 960, "expanded" to 1280)) {
                val columns = theme.posterColumns(width, 0)
                if (columns > 0) put(name, columns)
            }
        }
        val revision = androidx.compose.runtime.mutableIntStateOf(0)
        try {
            theme.applyLayout(buildJsonObject { put("posterColumns", buildJsonObject {}) }, "android_tv")
            rule.setContent {
                androidx.compose.runtime.key(revision.intValue) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalTvMode provides true) {
                        androidx.compose.material3.Text("列数：${xyz.linplayer.app.ui.pages.posterColumns()}")
                    }
                }
            }
            rule.onNodeWithText("列数：7").assertExists()
            rule.runOnIdle {
                theme.applyLayout(buildJsonObject { put("posterColumns", buildJsonObject { put("medium", 9) }) }, "android_tv")
                revision.intValue++
            }
            rule.onNodeWithText("列数：9").assertExists()
        } finally {
            theme.applyLayout(buildJsonObject { put("posterColumns", savedColumns) }, "android_tv")
        }
    }

    @Test fun homeRemoteRefreshIsReachableAndCoalescesWhileLoading() {
        val release = CompletableDeferred<Unit>()
        var hold = false
        mount(intercept = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = fake.callJson(command, args, onPartial)
                if (command == "emby.views" && hold) release.await()
                return result
            }
        } })
        try {
            val before = core.calls.count { it.first == "emby.views" }
            val caps = core.calls.count { it.first == "emby.extCapabilities" && it.second?.get("refresh") == JsonPrimitive(true) }
            rule.onNodeWithTag("nav.refresh").assertDoesNotExist()
            rule.onNodeWithTag("rail.1").assertIsFocused()
            hold = true
            press(rule, Key.DirectionCenter)
            rule.waitForIdle()
            assertEquals(before + 1, core.calls.count { it.first == "emby.views" })
            repeat(2) { press(rule, Key.DirectionCenter); rule.waitForIdle() }
            rule.onNodeWithTag("rail.1").assertIsFocused()
            assertEquals(before + 1, core.calls.count { it.first == "emby.views" })
            assertEquals(caps + 1, core.calls.count { it.first == "emby.extCapabilities" && it.second?.get("refresh") == JsonPrimitive(true) })
            rule.runOnIdle { hold = false; release.complete(Unit) }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("build/shared-tv/home-refresh.png")
            rule.onNodeWithTag("rail.1").assertIsFocused()
            press(rule, Key.DirectionCenter)
            rule.waitForIdle()
            assertEquals(before + 2, core.calls.count { it.first == "emby.views" })
        } finally { release.complete(Unit) }
    }

    @Test fun homeRefreshIsRemovedOnDetailAndReboundOnReturnAndAccountChange() {
        mount()
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("nav.refresh").assertDoesNotExist()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        fun refresh() {
            val before = core.calls.count { it.first == "emby.views" }
            rule.onNodeWithTag("rail.1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            rule.onNodeWithTag("rail.1").assertIsFocused()
            press(rule, Key.DirectionCenter)
            rule.waitForIdle()
            assertEquals(before + 1, core.calls.count { it.first == "emby.views" })
        }
        refresh()
        rule.runOnIdle {
            core.ret("emby.currentSession", buildJsonObject {
                put("server", "https://emby-b.invalid"); put("user_id", "b"); put("token", "test")
            })
            kotlinx.coroutines.runBlocking { app.refreshSession() }
        }
        rule.waitForIdle()
        refresh()
        val last = core.calls.last { it.first == "emby.views" }.second!!
        assertEquals("https://emby-b.invalid", last.str("server"))
        assertEquals("b", last.str("user_id"))
        assertEquals(JsonPrimitive(false), last["request_scope"]?.jsonObject?.get("private"))
    }

    @Test fun pluginSourceHomeHasNoEmbyRefreshAction() {
        mount { fake ->
            fake.ret("emby.currentSession", JsonNull)
            fake.ret("account.listAccounts", arr(buildJsonObject {
                put("id", "plugin:test/source"); put("server", "plugin:test/source")
                put("name", "测试数据源"); put("active", true)
                put("plugin", buildJsonObject { put("id", "test/source") })
            }))
            fake.ret("source.caps", buildJsonObject { put("home", true) })
            fake.ret("source.home", buildJsonObject { put("categories", arr()); put("recommended", arr()) })
        }
        assertNotNull(app.activeSource.value)
        rule.onNodeWithTag("nav.refresh").assertDoesNotExist()
        assertFalse(core.calls.any { it.first == "emby.views" })
    }

    @Test fun moviePlayIsVisibleBeforeOptionsAndRemoteOpensTheOriginalTarget() {
        mount()
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.waitForIdle()
        val play = rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("rail.1").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        play.assertIsFocused()
        rule.onNodeWithTag("detail.options").performScrollTo().assertIsDisplayed()
        play.performScrollTo().assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.waitForIdle()
        val launch = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult
        val intent = launch.intent
        val route = xyz.linplayer.app.PlayerActivity.readRoute(intent)!!
        assertEquals("m1", route.itemId)
        assertNull(route.src)
        assertTrue(intent.getBooleanExtra("player.tv", false))
        rule.runOnIdle {
            (activity as androidx.activity.ComponentActivity).activityResultRegistry.dispatchResult(
                launch.requestCode, android.app.Activity.RESULT_OK,
                android.content.Intent().putExtra(xyz.linplayer.app.PlayerActivity.PLAYED_ITEM, "m1"))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsEnabled().assertIsFocused()
    }

    @Test fun playbackReturnRestoresFocusAfterDelayedDetailRefresh() = playbackReturnAfterRefresh(false)

    @Test fun delayedPlaybackRefreshDoesNotStealFocusMovedToAnotherAction() = playbackReturnAfterRefresh(true)

    private fun playbackReturnAfterRefresh(moveToAnotherAction: Boolean) {
        var returning = false
        val release = CompletableDeferred<Unit>()
        mount(intercept = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = fake.callJson(command, args, onPartial)
                if (returning && command == "emby.itemDetail") release.await()
                return result
            }
        } })
        try {
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
            }
            rule.onNodeWithTag("detail.play").assertIsFocused()
            press(rule, Key.DirectionCenter)
            val launch = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult
            rule.runOnIdle {
                returning = true
                (activity as androidx.activity.ComponentActivity).activityResultRegistry.dispatchResult(
                    launch.requestCode, android.app.Activity.RESULT_OK,
                    android.content.Intent().putExtra(xyz.linplayer.app.PlayerActivity.PLAYED_ITEM, "m1"))
            }
            rule.waitForIdle()
            rule.onNodeWithTag("detail.play").assertIsNotEnabled()
            if (moveToAnotherAction) {
                for (step in 0 until 8) {
                    if (rule.onAllNodes(hasContentDescription("换源") and isFocused()).fetchSemanticsNodes().isNotEmpty()) break
                    press(rule, Key.DirectionRight)
                }
                rule.onNodeWithContentDescription("换源").assertIsFocused()
            }
            rule.runOnIdle { release.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsEnabled()
            if (moveToAnotherAction) rule.onNodeWithContentDescription("换源").assertIsFocused()
            else rule.onNodeWithTag("detail.play").assertIsFocused()
        } finally { release.complete(Unit) }
    }

    @Test fun detailActionsAndDownwardScrollKeepRemoteFocus() {
        mount { fake ->
            fake.ret("prefs.preloadItem", arr(
                buildJsonObject { put("id", "va"); put("name", "1080p"); put("preferred", true) },
                buildJsonObject { put("id", "vb"); put("name", "4K") },
            ))
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionRight)
        rule.onNodeWithTag("detail.play.options").assertIsFocused()
        rule.onRoot().captureRoboImage("build/shared-tv/detail-options-focus.png")
        press(rule, Key.DirectionRight)
        rule.onNodeWithContentDescription("收藏").assertIsFocused()
        press(rule, Key.DirectionCenter)
        assertTrue(core.calls.any { it.first == "emby.setFavorite" && it.second?.get("fav") == JsonPrimitive(true) })
        press(rule, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionDown)
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        val actor = SemanticsMatcher("演职员卡片") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("detail.person.") == true
        } and isFocused()
        for (step in 0 until 16) {
            if (rule.onAllNodes(actor).fetchSemanticsNodes().isNotEmpty()) break
            press(rule, Key.DirectionDown)
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        }
        assertTrue(rule.onNode(isFocused()).printToString(),
            rule.onAllNodes(actor).fetchSemanticsNodes().isNotEmpty())
        rule.onNode(actor).assertIsDisplayed()
        for (step in 0 until 16) {
            if (rule.onAllNodes(hasTestTag("detail.play") and isFocused()).fetchSemanticsNodes().isNotEmpty()) break
            press(rule, Key.DirectionUp)
        }
        rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail.tags"))).assertIsDisplayed()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-mdpi")
    fun detailLongTitleAndLargeTextWrapWithoutClipping() {
        val title = "穿越山海与星空的漫长旅程：写给远方的一封信"
        mount(fontScale = 1.3f) { fake ->
            fake.ret("emby.itemDetail", buildJsonObject {
                item("m1", title).forEach { (key, value) -> put(key, value) }
                put("overview", "一段跨越山海的旅程，也是一封写给远方的信。".repeat(6))
            })
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("detail.title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsFocused()
        press(rule, Key.DirectionRight)
        rule.onNodeWithTag("detail.play.options").assertIsDisplayed().assertIsFocused()
        rule.onRoot().captureRoboImage("build/shared-tv/detail-large-text.png")
    }

    private fun seriesDetails(fake: FakeCore) {
        fake.series()
        fake.on("emby.itemDetail") { args ->
            val id = args.str("item_id")!!
            when (id) {
                "s1" -> item(id, "遥控剧集", "Series")
                "se2" -> item(id, "第2季", "Season", seriesId = "s1")
                "se2e2" -> buildJsonObject {
                    item(id, "分集详情", "Episode", seriesId = "s1", season = 2, episode = 2)
                        .forEach { (key, value) -> put(key, value) }
                    put("season_id", "se2")
                }
                else -> error("非预期详情目标：$id")
            }
        }
        fake.ret("emby.seriesSeasons", arr(*(1..2).map { season ->
            buildJsonObject {
                put("id", "se$season"); put("index_no", season); put("child_count", 2)
                put("unplayed", 1); put("unplayed_count_known", true)
            }
        }.toTypedArray()))
        fake.on("emby.seasonEpisodes") { args ->
            val parent = args.str("parent_id")!!
            check(parent == "se1" || parent == "se2")
            val season = if (parent == "se1") 1 else 2
            buildJsonObject {
                put("total", 2)
                put("items", arr(*(1..2).map { episode ->
                    item("${parent}e$episode", "第${season}季第${episode}集", "Episode",
                        seriesId = "s1", season = season, episode = episode, played = episode == 1,
                        runtime = 1800.0, resume = if (episode == 2) 120.0 else 0.0)
                }.toTypedArray()))
            }
        }
    }

    private fun cinemaMedia(target: String) = arr(*(0..1).map { version ->
        buildJsonObject {
            put("id", "$target-v$version")
            put("name", "$target ${if (version == 0) "标准版" else "高码率"}")
            put("preferred", version == 0)
            put("streams", arr(
                buildJsonObject {
                    put("index", 0); put("type_", "Video"); put("codec", "h264")
                    put("width", 1920); put("height", 1080)
                },
                buildJsonObject { put("index", 1); put("type_", "Audio"); put("codec", "aac"); put("language", "jpn") },
                buildJsonObject { put("index", 2); put("type_", "Audio"); put("codec", "aac"); put("language", "eng") },
                buildJsonObject { put("index", 3); put("type_", "Subtitle"); put("codec", "srt"); put("language", "chi"); put("title", "简体中文") },
                buildJsonObject { put("index", 4); put("type_", "Subtitle"); put("codec", "srt"); put("language", "eng"); put("title", "英文字幕") },
            ))
        }
    }.toTypedArray())

    private fun cinemaSeries(fake: FakeCore) {
        seriesDetails(fake)
        val detail = fake.handlers.getValue("emby.itemDetail")
        fake.on("emby.itemDetail") { request ->
            JsonObject(detail(request).jsonObject + buildJsonObject {
                put("overview", "一封迟到的来信，让远行的人重新踏上归途。穿越山海，也重新认识彼此。")
                put("genres", arr(JsonPrimitive("剧情"), JsonPrimitive("冒险")))
            })
        }
        fake.on("emby.itemMedia") { cinemaMedia(it.str("item_id")!!) }
        fake.ret("account.listAccounts", arr(account("test-account", "测试媒体库", "", true)))
        fake.ret("prefs.setPrefs", JsonNull)
    }

    @Test fun cinemaSeriesShowsTargetOptionsAndEpisodeArtworkInFirstViewport() {
        mount(configure = ::cinemaSeries)
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "s1", "type" to "Series"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.onNode(hasContentDescription("版本") and hasAnyAncestor(hasTestTag("detail.hero"))).assertIsDisplayed()
        rule.onNodeWithText("se1e2 标准版").assertIsDisplayed()
        val screen = rule.onRoot().getUnclippedBoundsInRoot()
        val artwork = rule.onNodeWithTag("detail.ep.image.se1e1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("首屏应看清整张分集缩略图", artwork.bottom <= screen.bottom)
        assertTrue((artwork.bottom - artwork.top).value > (screen.bottom - screen.top).value * .15f)
        assertTrue(core.calls.filter { it.first == "prefs.preloadItem" }.all { it.second.str("item_id") == "se1e2" })
        rule.onRoot().captureRoboImage("build/shared-tv/cinema-series.png")
    }

    @Test fun cinemaSeriesRemoteOptionsReachPlayerWithTargetIdentity() {
        mount(configure = ::cinemaSeries)
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "s1", "type" to "Series"), false)
        }
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("se1e2 高码率").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        press(rule, Key.DirectionRight)
        rule.onNodeWithContentDescription("音轨").assertIsFocused()
        rule.onNodeWithContentDescription("视频").assertHasNoClickAction()
        rule.onNode(hasContentDescription("视频") and isFocused()).assertDoesNotExist()
        press(rule, Key.DirectionRight, Key.DirectionCenter)
        rule.onNodeWithText("英文字幕").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("字幕").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("选择季").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionCenter)
        val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult.intent
        val route = xyz.linplayer.app.PlayerActivity.readRoute(intent)!!
        assertEquals("se1e2", route.itemId)
        assertEquals("se1e2-v1", route.versionId)
        val subtitle = Json.decodeFromString<xyz.linplayer.app.ui.player.InitialSubtitle>(route.subtitle!!)
        assertEquals("se1e2-v1", subtitle.sourceId)
        assertEquals(4L, subtitle.index)
    }

    @Test fun cinemaSeriesTargetChangeDropsOldParametersWhileMediaLoads() {
        val release = CompletableDeferred<Unit>()
        mount(intercept = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = fake.callJson(command, args, onPartial)
                if (command == "prefs.preloadItem" && args.str("item_id") == "se2e2") release.await()
                return result
            }
        } }, configure = ::cinemaSeries)
        try {
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to "s1", "type" to "Series"), false)
            }
            rule.onNodeWithContentDescription("版本").performClick()
            rule.onNodeWithText("se1e2 高码率").performClick()
            rule.onNodeWithContentDescription("字幕").performClick()
            rule.onNodeWithText("英文字幕").performClick()
            rule.onNodeWithContentDescription("选择季").performClick()
            rule.onNode(hasText("第2季") and hasAnyAncestor(isPopup())).performClick()
            rule.waitForIdle()
            rule.onNodeWithText("se1e2 高码率").assertDoesNotExist()
            rule.onNodeWithContentDescription("版本").assertDoesNotExist()
            assertEquals("se2e2", core.calls.last { it.first == "prefs.preloadItem" }.second.str("item_id"))
            rule.runOnIdle { release.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithText("se2e2 标准版").assertIsDisplayed()
            rule.onNodeWithTag("detail.play").performClick()
            rule.waitForIdle()
            val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult.intent
            val route = xyz.linplayer.app.PlayerActivity.readRoute(intent)!!
            assertEquals("se2e2", route.itemId)
            assertEquals("se2e2-v0", route.versionId)
            assertNull(route.subtitle)
        } finally { release.complete(Unit) }
    }

    @Test fun cinemaEpisodeWithEmptySeasonDoesNotTrapFocusInParameters() {
        mount { fake ->
            cinemaSeries(fake)
            fake.ret("emby.seriesSeasons", arr())
            fake.ret("emby.seasonEpisodes", buildJsonObject { put("items", arr()); put("total", 0) })
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "se2e2", "type" to "Episode"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail.overview"))).assertIsDisplayed()
    }

    private fun containerPlayWaitsForEpisodes(type: String, id: String, parent: String) {
        val detailReady = CompletableDeferred<Unit>()
        val episodesReady = CompletableDeferred<Unit>()
        mount(intercept = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = fake.callJson(command, args, onPartial)
                if (command == "emby.itemDetail") detailReady.await()
                if (command == "emby.seasonEpisodes") episodesReady.await()
                return result
            }
        } }, configure = ::seriesDetails)
        try {
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to id, "type" to type), false)
            }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.onNodeWithTag("detail.play").assertDoesNotExist()
            rule.runOnIdle { detailReady.complete(Unit) }
            rule.waitForIdle()
            assertEquals(parent, core.calls.last { it.first == "emby.seasonEpisodes" }.second.str("parent_id"))
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.onNode(hasTestTag("detail.play") and isEnabled()).assertDoesNotExist()
            rule.runOnIdle { episodesReady.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsEnabled().assertIsFocused()
            rule.onRoot().captureRoboImage("build/shared-tv/${type.lowercase()}-play-focus.png")
            press(rule, Key.DirectionCenter)
            rule.waitForIdle()
            val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult.intent
            val route = xyz.linplayer.app.PlayerActivity.readRoute(intent)!!
            assertEquals("${parent}e2", route.itemId)
            assertEquals("va", route.versionId)
            assertNull(route.src)
            assertTrue(intent.getBooleanExtra("player.tv", false))
        } finally {
            detailReady.complete(Unit)
            episodesReady.complete(Unit)
        }
    }

    @Test fun seriesPlayWaitsForEpisodesAndRemoteOpensNextEpisode() =
        containerPlayWaitsForEpisodes("Series", "s1", "se1")

    @Test fun seasonPlayWaitsForEpisodesAndRemoteKeepsRequestedSeason() =
        containerPlayWaitsForEpisodes("Season", "se2", "se2")

    @OptIn(ExperimentalTestApi::class)
    @Test fun remoteChangesSeasonOpensEpisodeAndRestoresItsFocus() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val wasTouch = org.robolectric.shadows.ShadowWindowManagerGlobal.getInTouchMode()
        // Popup 使用自己的输入模式；遥控测试须从系统层退出触摸模式。
        instrumentation.setInTouchMode(false)
        try {
            mount(configure = ::seriesDetails)
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to "s1", "type" to "Series"), false)
            }
            rule.waitForIdle()
            rule.onNodeWithTag("detail.play").assertIsFocused()
            repeat(4) {
                if (rule.onAllNodes(hasContentDescription("选择季") and isFocused()).fetchSemanticsNodes().isEmpty())
                    press(rule, Key.DirectionDown)
            }
            rule.onNodeWithContentDescription("选择季").assertIsFocused()
            press(rule, Key.DirectionCenter)
            rule.onNode(hasText("第1季") and hasAnyAncestor(isPopup())).assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            rule.onNode(hasText("第2季") and hasAnyAncestor(isPopup())).assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            rule.waitForIdle()
            rule.onNode(isPopup()).assertDoesNotExist()
            assertEquals("se2", core.calls.last { it.first == "emby.seasonEpisodes" }.second.str("parent_id"))
            rule.onNodeWithContentDescription("选择季").assertIsFocused()
            press(rule, Key.DirectionDown)
            rule.onNodeWithTag("detail.ep.se2e1").assertIsFocused()
            press(rule, Key.DirectionRight)
            val episode = rule.onNodeWithTag("detail.ep.se2e2").assertIsFocused()
            press(rule, Key.DirectionCenter)
            rule.waitForIdle()
            assertEquals("se2e2", core.calls.last { it.first == "emby.itemDetail" }.second.str("item_id"))
            rule.runOnUiThread { back.onBackPressed() }
            rule.waitForIdle()
            episode.assertIsFocused()
        } finally { instrumentation.setInTouchMode(wasTouch) }
    }

    @Test fun detailLoadingAndCollectionReturnWithoutOnScreenBack() {
        val release = CompletableDeferred<Unit>()
        mount(intercept = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = fake.callJson(command, args, onPartial)
                if (command == "emby.itemDetail" && args.str("item_id") == "m1") release.await()
                return result
            }
        } })
        try {
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
            }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.onNodeWithTag("detail.play").assertDoesNotExist()
            rule.runOnUiThread { back.onBackPressed() }
            rule.waitForIdle()
            rule.onNodeWithTag("rail.1").assertIsDisplayed()
            rule.runOnIdle { release.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithTag("rail.1").assertIsDisplayed()
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to "box", "type" to "BoxSet"), false)
            }
            rule.waitForIdle()
            rule.onNodeWithTag("detail.play").assertDoesNotExist()
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.runOnUiThread { back.onBackPressed() }
            rule.waitForIdle()
            rule.onNodeWithTag("rail.1").assertIsDisplayed()
        } finally { release.complete(Unit) }
    }

    private fun pluginPrimaryTakeover(mode: String) {
        mount { fake ->
            fake.ret("plugin.anchors", arr(buildJsonObject {
                put("plugin_id", "test/actions"); put("name", "接管主操作")
                put("anchor", "detail.actions"); put("mode", mode); put("block", "actions")
            }))
            fake.on("plugin.ui.mount") { throw IllegalStateException("合成插件错误") }
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play").assertDoesNotExist()
        if (mode == "replace") rule.onNodeWithText("这一块出错了", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsDisplayed()
    }

    @Test fun pluginHiddenPrimaryStillAllowsRemoteBack() = pluginPrimaryTakeover("hide")
    @Test fun pluginReplacedPrimaryStillAllowsRemoteBack() = pluginPrimaryTakeover("replace")

    @OptIn(ExperimentalTestApi::class)
    @Test fun remoteBackClosesDetailMenuBeforeReturningToSourceCard() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val wasTouch = org.robolectric.shadows.ShadowWindowManagerGlobal.getInTouchMode()
        instrumentation.setInTouchMode(false)
        try {
            mount()
            val card = rule.onNodeWithContentDescription("谎言的代价")
            card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.DirectionCenter)
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.onNodeWithTag("detail.play").assertIsFocused()
            press(rule, Key.DirectionRight, Key.DirectionCenter)
            rule.onNode(hasText("从头开始播放") and hasAnyAncestor(isPopup())).assertIsFocused()
            rule.runOnUiThread { back.onBackPressed() }
            rule.waitForIdle()
            rule.onNode(isPopup()).assertDoesNotExist()
            rule.onNodeWithTag("detail.play.options").assertIsFocused()
            rule.runOnUiThread { back.onBackPressed() }
            rule.waitForIdle()
            card.assertIsDisplayed().assertIsFocused()
        } finally { instrumentation.setInTouchMode(wasTouch) }
    }

    @Test fun directionsMoveToSharedAggregateWithoutConfirmAndHideDownloads() {
        mount()
        rule.onNodeWithTag("rail.1").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag("rail.4").assertIsFocused().assertIsSelected()
        rule.onNodeWithText("排行榜").assertIsDisplayed()
        rule.onNodeWithText("日历").assertIsDisplayed()
        rule.onNodeWithText("下载").assertDoesNotExist()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("rail.4").assertIsNotFocused()
        rule.onRoot().captureRoboImage("build/shared-tv/aggregate.png")
    }

    @Test fun actionEntryStillWaitsForConfirm() {
        mount()
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag("rail.0").assertIsFocused()
        rule.onNodeWithTag("rail.4").assertIsSelected()
        rule.onNodeWithTag("search.field").assertDoesNotExist()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("search.field").assertIsDisplayed()
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
        assertTrue("TV详情大图应承载首屏信息与主操作，而不是一条横幅", hero.height >= screen.height * .65f)
        val play = rule.onNodeWithTag("detail.play").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("主操作必须完整位于首屏", play.bottom <= screen.bottom)
        assertTrue("主操作不能横跨大半屏", play.width < screen.width * .5f)
        rule.onNodeWithContentDescription("下载").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/shared-tv/detail.png")
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused()
    }

    @Test fun directionalHomeCardOpensDetailAndRestoresTheSameCard() {
        mount()
        press(rule, Key.DirectionDown, Key.DirectionDown, Key.DirectionLeft)
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
