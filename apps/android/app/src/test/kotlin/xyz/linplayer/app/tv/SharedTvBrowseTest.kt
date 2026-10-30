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
        rule.onNodeWithTag("settings.group.内容与数据").performClick()
        rule.onNodeWithTag("settings.entries").performScrollToNode(hasText("文件浏览"))
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
        // TV停靠后首屏可能被懒列表回收；焦点已离开，向上仍须完整恢复首屏。
        rule.onNode(hasTestTag("detail.play") and isFocused()).assertDoesNotExist()
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

    @Test fun rootBackFromContentReturnsToCurrentNavigationTab() {
        mount { it.ret("emby.cachedItems", arr(item("hero", "首页轮播", year = 2026))) }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("home.banners").fetchSemanticsNodes().isNotEmpty() }
        val initial = rule.onNodeWithTag("home.banners").fetchSemanticsNode().positionInRoot.y
        repeat(4) { press(rule, Key.DirectionDown) }
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsFocused()
        assertFalse(activity.isFinishing)
        assertEquals(initial, rule.onNodeWithTag("home.banners").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test fun focusedNavigationTabShowsPillAndKeepsCurrentLineInDarkTheme() = checkNavigationTab("dark")
    @Test fun focusedNavigationTabShowsPillAndKeepsCurrentLineInLightTheme() = checkNavigationTab("light")

    private fun checkNavigationTab(theme: String) {
        UiPrefs.setTheme(ApplicationProvider.getApplicationContext(), theme)
        mount()
        press(rule, Key.DirectionRight, Key.DirectionLeft)
        rule.onNodeWithTag("rail.1").assertIsFocused().assertIsSelected()
        val bounds = rule.onNodeWithTag("rail.1").fetchSemanticsNode().boundsInRoot
        val focused = rule.onRoot().captureToImage().toPixelMap()
        rule.onRoot().captureRoboImage("build/shared-tv/focus-navigation-$theme.png")
        press(rule, Key.DirectionLeft)
        rule.onNodeWithTag("nav.server").assertIsFocused()
        rule.onNodeWithTag("rail.1").assertIsNotFocused().assertIsSelected()
        val idle = rule.onRoot().captureToImage().toPixelMap()
        val x = bounds.left.toInt() + 10
        val y = bounds.center.y.toInt()
        assertTrue("聚焦时胶囊底色清晰可见", kotlin.math.abs(focused[x, y].luminance() - idle[x, y].luminance()) > .04f)
        assertEquals("胶囊圆角外不铺矩形底", idle[bounds.left.toInt() + 2, bounds.top.toInt() + 2].luminance(),
            focused[bounds.left.toInt() + 2, bounds.top.toInt() + 2].luminance(), .02f)
        val lineX = bounds.center.x.toInt()
        val lineY = bounds.bottom.toInt() - 8
        val line = idle[lineX, lineY]
        val beside = idle[x, lineY]
        assertTrue("失焦后当前页短线仍可见", maxOf(kotlin.math.abs(line.red - beside.red),
            kotlin.math.abs(line.green - beside.green), kotlin.math.abs(line.blue - beside.blue)) > .1f)
        assertEquals("短线仅标当前页，不随焦点消失", focused[lineX, lineY].luminance(), idle[lineX, lineY].luminance(), .02f)
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

    @Test fun unifiedPodiumEdgesAndOpen() {
        mount {
            it.ret("emby.extRankings", arr(buildJsonObject { put("id", "hot"); put("label", "热门榜") }))
            it.ret("emby.extRankingItems", arr(*(1..4).map { n -> buildJsonObject {
                put("id", "rank-$n"); put("rank", n); put("title", "榜单$n")
                put("source", "tmdb"); put("external_id", "$n"); put("media_type", "movie")
            } }.toTypedArray()))
            it.ret("emby.extResolve", buildJsonObject { put("item_id", "movie-4"); put("item_type", "Movie") })
        }
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginNav.host!!.push("ranking", JsonObject(emptyMap()), false) }
        rule.onNodeWithText("榜单2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionLeft)
        rule.onNodeWithText("榜单2").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithText("榜单3").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithText("榜单4").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNode(isFocused() and (hasText("榜单1") or hasText("榜单2") or hasText("榜单3"))).assertExists()
        press(rule, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionCenter)
        assertEquals("2", core.calls.last { it.first == "emby.extResolve" }.second.str("external_id"))
        rule.onNodeWithTag("detail.play").assertIsDisplayed()
    }

    @Test fun unifiedPluginTabsKeepEdgesAndSelect() {
        mount {
            it.ret("plugin.list", buildJsonObject { put("plugins", arr()) })
            it.ret("plugin.pendingRestart", JsonPrimitive(false))
            it.ret("plugin.market", buildJsonObject { put("entries", arr()) })
        }
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginNav.host!!.push("plugins", JsonObject(emptyMap()), false) }
        rule.onNodeWithText("已安装").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionLeft)
        rule.onNodeWithText("已安装").assertIsFocused()
        repeat(4) { press(rule, Key.DirectionRight) }
        rule.onNodeWithText("仓库").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithText("从文件安装").assertIsFocused()
        press(rule, Key.DirectionUp, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft)
        rule.onNodeWithText("已安装").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionCenter)
        rule.onNodeWithText("市场").assertIsSelected().assertIsFocused()
        assertTrue(core.calls.any { it.first == "plugin.market" })
    }

    @Test fun unifiedSettingsSegmentsKeepEdgesAndSelect() {
        mount()
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginNav.host!!.push("settings.appearance", JsonObject(emptyMap()), false) }
        rule.onNodeWithText("跟随系统").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionLeft)
        rule.onNodeWithText("跟随系统").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithText("浅色").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("浅色").assertIsSelected()
        assertEquals("light", UiPrefs.theme.value)
        press(rule, Key.DirectionDown)
        rule.onNodeWithText("色系").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithText("色系").assertIsNotFocused()
        rule.onNode(isFocused() and (hasText("跟随系统") or hasText("深色") or hasText("浅色"))).assertExists()
    }

    @Test fun unifiedProxyPresetsKeepEdgesAndSave() {
        mount {
            it.ret("prefs.getUpdateSettings", buildJsonObject {
                put("channel", "stable"); put("auto_check", false); put("proxy", "")
                put("proxies", buildJsonArray { add("https://one.invalid/"); add("https://two.invalid/") })
            })
            it.ret("prefs.setUpdateSettings", JsonNull)
        }
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginNav.host!!.push("settings", JsonObject(emptyMap()), false) }
        rule.onNodeWithTag("settings.group.应用").performClick()
        rule.onNodeWithTag("settings.entries").performScrollToNode(hasText("更新"))
        rule.onNodeWithText("更新").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("GitHub 代理").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        rule.waitForIdle()
        rule.runOnUiThread {
            // Dialog有独立窗口；按真实遥控输入离开触摸模式。
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            view.requestFocusFromTouch()
            assertFalse(view.isInTouchMode)
        }
        val direct = hasText("直连") and hasAnyAncestor(isDialog())
        fun dialogPress(vararg keys: Any) {
            keys.forEach { key ->
                rule.onNode(isDialog()).performKeyInput { pressKey(key as Key) }
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                advance(rule, 250)
            }
        }
        rule.onNode(hasSetTextAction()).assertIsFocused()
        dialogPress(Key.DirectionDown)
        rule.onNode(isFocused() and hasAnyAncestor(isDialog())).assert(direct or hasText("one.invalid") or hasText("two.invalid"))
        dialogPress(Key.DirectionLeft, Key.DirectionLeft)
        rule.onNode(direct).assertIsFocused()
        dialogPress(Key.DirectionLeft)
        rule.onNode(direct).assertIsFocused()
        dialogPress(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithText("two.invalid").assertIsFocused()
        dialogPress(Key.DirectionCenter)
        rule.onNodeWithText("two.invalid").assertIsSelected()
        dialogPress(Key.DirectionUp)
        rule.onNode(hasSetTextAction()).assertIsFocused().assertTextContains("https://two.invalid/")
        dialogPress(Key.DirectionDown, Key.DirectionDown)
        rule.onNode(isFocused() and (hasText("保存") or hasText("取消"))).assertExists()
        dialogPress(Key.DirectionRight, Key.DirectionCenter)
        assertEquals("https://two.invalid/", core.calls.last { it.first == "prefs.setUpdateSettings" }.second.str("proxy"))
    }

    @Test fun rowHeaderQuickDownThenUpDoesNotStealFocus() = checkRowHeaderReverse(Key.DirectionUp)
    @Test fun rowHeaderQuickDownThenLeftDoesNotStealFocus() = checkRowHeaderReverse(Key.DirectionLeft)

    @Test fun firstRowHeaderQuickDownThenUpReturnsNavigation() {
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), true)
        mount { it.ret("emby.listResume", page()) }
        val header = hasText("电影") and hasClickAction()
        rule.onNode(header).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(isFocused() and hasContentDescription("沙丘 2")).assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsFocused().assertIsDisplayed()
    }

    private fun checkRowHeaderReverse(direction: Key) {
        mount()
        val header = hasText("电影") and hasClickAction()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(header)
        rule.onNode(header).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onRoot().performKeyInput { keyDown(direction); keyUp(direction) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val target = rule.onNode(isFocused()).fetchSemanticsNode().id
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals("反向后不能被旧Down请求抢焦点", target, rule.onNode(isFocused()).fetchSemanticsNode().id)
        rule.onNodeWithContentDescription("沙丘 2").assertIsNotFocused()
    }

    @Test fun compactLibraryHeaderMovesDownToFirstPoster() {
        mount { fake -> fake.on("emby.listLatest") { args ->
            if (args.str("parent_id") == "lib-movie")
                JsonArray(movieItems + (9..12).map { item("m$it", "电影$it") })
            else showItems
        } }
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("电影") and hasClickAction())
        rule.onNode(hasText("电影") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val header = rule.onNode(hasText("电影") and isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("栏目焦点只覆盖左侧标题", header.width < 240f)
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("沙丘 2").assertIsFocused()
        repeat(11) { press(rule, Key.DirectionRight) }
        rule.onNodeWithContentDescription("电影12").assertIsFocused()
        rule.onNodeWithContentDescription("沙丘 2").assertIsNotDisplayed()
        rule.onNode(hasText("电影") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { repeat(6) { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) } }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("沙丘 2").assertIsFocused()
    }

    @Test fun rowHeaderSidewaysNeverEntersNavigation() {
        mount()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("电影") and hasClickAction())
        rule.onNode(hasText("电影") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft)
        rule.onNode(hasText("电影") and hasClickAction()).assertIsFocused()
        rule.onNodeWithTag("rail.1").assertIsSelected()
    }

    @Test fun unifiedFavoritesPivotWithoutLeanback() = checkFavoritesPivot(false)
    @Test fun unifiedFavoritesPivotWithLeanback() = checkFavoritesPivot(true)

    private fun checkFavoritesPivot(leanback: Boolean) {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, leanback)
        mount { it.ret("emby.listFavorites", page(*(0..19).map { n -> item("fav-$n", "收藏$n") }.toTypedArray())) }
        press(rule, Key.DirectionRight, Key.DirectionDown)
        rule.onNode(hasText("收藏的电影") and hasClickAction()).assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("收藏0").assertIsFocused()
        repeat(8) { press(rule, Key.DirectionRight) }
        val card = rule.onNodeWithContentDescription("收藏8").assertIsFocused().fetchSemanticsNode()
        val row = rule.onNode(hasAnyDescendant(hasContentDescription("收藏8")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode()
        assertEquals("其它浏览页与首页使用同一停靠", row.positionInRoot.x + row.size.width * .3f, card.positionInRoot.x, 2f)
        val before = card.positionInRoot
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.runOnUiThread { back.onBackPressed() }
        val after = rule.onNodeWithContentDescription("收藏8").assertIsFocused().fetchSemanticsNode().positionInRoot
        assertEquals(before.x, after.x, 2f)
        assertEquals(before.y, after.y, 2f)
    }

    @Test fun unifiedLibraryGridCanMoveVerticallyAndReturn() {
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), false)
        mount { it.ret("emby.listItemsPage", page(*(0..69).map { n -> item("grid-$n", "网格$n") }.toTypedArray())) }
        press(rule, Key.DirectionDown, Key.DirectionCenter)
        rule.onNodeWithContentDescription("网格0").assertIsFocused()
        repeat(4) { press(rule, Key.DirectionDown) }
        val card = rule.onNodeWithContentDescription("网格28").assertIsFocused().fetchSemanticsNode()
        val grid = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode()
        assertEquals("网格纵向沿用TV停靠", grid.positionInRoot.y + grid.size.height * .3f, card.positionInRoot.y, 2f)
        press(rule, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown)
        rule.onNodeWithContentDescription("网格28").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.runOnUiThread { back.onBackPressed() }
        rule.onNodeWithContentDescription("网格28").assertIsFocused().assertIsDisplayed()
    }

    private fun openMovieCast() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        mount()
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.person.p0").assertIsFocused()
    }

    @Test fun unifiedCastEdgesKeepFocusAndUpReturnsToPlay() {
        openMovieCast()
        press(rule, Key.DirectionLeft)
        rule.onNodeWithTag("detail.person.p0").assertIsFocused()
        val count = core.handlers.getValue("emby.itemDetail")(null).jsonObject.getValue("people").jsonArray.size
        repeat(count + 1) { press(rule, Key.DirectionRight) }
        rule.onNodeWithTag("detail.person.p${count - 1}").assertIsFocused().assertIsDisplayed()
        repeat(count + 1) { press(rule, Key.DirectionLeft) }
        rule.onNodeWithTag("detail.person.p0").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("detail.play").assertIsFocused().assertIsDisplayed()
    }

    @Test fun unifiedDetailRepeatedUpKeepsReturnProgress() {
        openMovieCast()
        val column = rule.onNode(hasAnyDescendant(hasTestTag("detail.hero")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        fun scroll() = column.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        fun up() {
            rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        val start = scroll()
        rule.mainClock.autoAdvance = false
        up()
        repeat(3) { rule.mainClock.advanceTimeBy(32) }
        val single = scroll()
        assertTrue("详情回顶采样应在途中", single > 0f && single < start)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionDown)
        assertEquals(start, scroll(), 1f)
        rule.mainClock.autoAdvance = false
        up()
        repeat(3) { rule.mainClock.advanceTimeBy(32); up() }
        assertEquals("详情与首页一样合并在途回顶", single, scroll(), 2f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play").assertIsFocused()
    }

    @Test fun unifiedDetailReturnStopsAfterNewDirection() {
        openMovieCast()
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("detail.person.p0").assertIsFocused()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithTag("detail.person.p1").assertIsFocused()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("detail.person.p1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun unifiedSourceFiltersKeepEdgesAndAllowGridNavigation() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        val category = buildJsonObject {
            put("id", "category"); put("name", "分类0")
            put("filters", arr(buildJsonObject {
                put("key", "region"); put("name", "地区")
                put("options", arr(*(0..19).map { n -> buildJsonObject {
                    put("value", "region-$n"); put("name", "地区$n")
                } }.toTypedArray()))
            }))
        }
        mount { fake ->
            fake.ret("emby.currentSession", JsonNull)
            fake.ret("account.listAccounts", arr(buildJsonObject {
                put("id", "plugin:test/source"); put("server", "plugin:test/source")
                put("name", "测试数据源"); put("active", true)
                put("plugin", buildJsonObject { put("id", "test/source") })
            }))
            fake.ret("source.caps", buildJsonObject { put("home", true) })
            fake.ret("source.continueWatching", arr())
            fake.ret("source.home", buildJsonObject {
                put("categories", arr(category, *(1..19).map { n -> buildJsonObject {
                    put("id", "category-$n"); put("name", "分类$n")
                } }.toTypedArray())); put("recommended", arr())
            })
            fake.ret("source.category", buildJsonObject {
                put("items", arr(*(0..23).map { n -> buildJsonObject {
                    put("id", "source-$n"); put("title", "源片$n")
                } }.toTypedArray())); put("next", "")
            })
            fake.ret("source.detail", buildJsonObject {
                put("id", "source-detail"); put("title", "源片详情"); put("lines", arr())
            })
        }
        press(rule, Key.DirectionDown)
        rule.onNode(hasSetTextAction()).assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNode(hasText("分类", substring = true) and isFocused()).assertIsDisplayed()
        repeat(21) { press(rule, Key.DirectionLeft) }
        press(rule, Key.DirectionLeft)
        rule.onNodeWithText("分类0").assertIsFocused()
        repeat(21) { press(rule, Key.DirectionRight) }
        rule.onNodeWithText("分类19").assertIsFocused().assertIsDisplayed()
        repeat(21) { press(rule, Key.DirectionLeft) }
        rule.onNodeWithText("分类0").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("地区0").assertIsFocused()
        press(rule, Key.DirectionLeft)
        rule.onNodeWithText("地区0").assertIsFocused()
        repeat(21) { press(rule, Key.DirectionRight) }
        rule.onNodeWithText("地区19").assertIsFocused().assertIsDisplayed()
        press(rule, Key.DirectionDown)
        val selected = hasText("源片", substring = true) and isFocused()
        fun title() = rule.onNode(selected).assertIsDisplayed().fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val first = title()
        press(rule, Key.DirectionDown)
        assertNotEquals("数据源网格仍能跨行", first, title())
        press(rule, Key.DirectionUp)
        assertEquals(first, title())
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("源片详情").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        assertEquals("数据源详情返回原卡片", first, title())
        press(rule, Key.DirectionUp)
        rule.onNode(hasText("地区", substring = true) and isFocused()).assertIsDisplayed()
    }

    @Test fun detailFirstScreenStaysAtTopUnderTvPivotScrolling() {
        // 真电视默认把焦点项滚到 30% 处；Robolectric 默认不是电视，这里显式打开
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        mount()
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        val list = rule.onNode(hasAnyDescendant(hasTestTag("detail.hero")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        assertEquals("首屏聚焦播放不应把详情往上推", 0f, list.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), .5f)
    }

    @Test fun hiddenLibrariesMakeResumeTheFirstContentRow() {
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), true)
        mount()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("谎言的代价").assertIsFocused()
    }

    private fun scrollableHome(leanback: Boolean, banner: Boolean = false, onlyResume: Boolean = false) {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, leanback)
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), true)
        mount {
            it.ret("emby.listResume", arr(*(0..19).map { n ->
                item("scroll-$n", "续播$n", runtime = 2700.0, resume = 840.0)
            }.toTypedArray()))
            if (banner) it.ret("emby.cachedItems", arr(item("hero", "首页轮播", year = 2026)))
            if (onlyResume) it.ret("emby.views", arr())
        }
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("续播0").assertIsFocused()
    }

    @Test fun homeHorizontalEdgesStayInTheirRow() = checkHomeRowEdges(false)
    @Test fun nativeTvHomeHorizontalEdgesStayInTheirRow() = checkHomeRowEdges(true)

    @Test fun homeTvPivotDoesNotDependOnDeviceFeature() = checkHomeTvPivot(false)
    @Test fun homeTvPivotMatchesNativeTvDevice() = checkHomeTvPivot(true)

    private fun checkHomeTvPivot(leanback: Boolean) {
        scrollableHome(leanback, banner = true)
        repeat(5) { press(rule, Key.DirectionRight) }
        val card = rule.onNodeWithContentDescription("续播5").assertIsFocused().fetchSemanticsNode()
        val row = rule.onNode(hasAnyDescendant(hasContentDescription("续播5")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode()
        val column = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode()
        assertEquals("横向停靠使用TV规则", row.positionInRoot.x + row.size.width * .3f, card.positionInRoot.x, 2f)
        assertEquals("纵向停靠使用TV规则", column.positionInRoot.y + column.size.height * .3f, card.positionInRoot.y, 2f)
    }

    private fun checkHomeRowEdges(leanback: Boolean) {
        scrollableHome(leanback)
        press(rule, Key.DirectionLeft)
        rule.onNodeWithContentDescription("续播0").assertIsFocused()
        repeat(21) { press(rule, Key.DirectionRight) }
        rule.onNodeWithContentDescription("续播19").assertIsFocused().assertIsDisplayed()
        val last = rule.onNodeWithContentDescription("续播19").fetchSemanticsNode()
        val track = rule.onNode(hasAnyDescendant(hasContentDescription("续播19")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode()
        assertTrue("末卡两侧完整可见", last.positionInRoot.x >= track.positionInRoot.x &&
            last.positionInRoot.x + last.size.width <= track.positionInRoot.x + track.size.width)
        repeat(21) { press(rule, Key.DirectionLeft) }
        rule.onNodeWithContentDescription("续播0").assertIsFocused().assertIsDisplayed()
        val row = rule.onNode(hasAnyDescendant(hasContentDescription("续播0")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode()
        val card = rule.onNodeWithContentDescription("续播0").fetchSemanticsNode()
        assertTrue("首卡两侧完整可见", card.positionInRoot.x >= row.positionInRoot.x &&
            card.positionInRoot.x + card.size.width <= row.positionInRoot.x + row.size.width)
        press(rule, Key.DirectionDown)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("home.resume"))).assertDoesNotExist()
        press(rule, Key.DirectionUp)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("home.resume"))).assertExists()
    }

    @Test fun nativeTvDirectDetailReturnsToNavigation() = checkDirectDetailNavigationReturn(false)
    @Test fun nativeTvTabThenDirectDetailReturnsToNavigation() = checkDirectDetailNavigationReturn(true)

    private fun checkDirectDetailNavigationReturn(switchTab: Boolean) {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        mount()
        rule.onNodeWithTag("rail.1").assertIsFocused()
        val tab = if (switchTab) "rail.3" else "rail.1"
        if (switchTab) press(rule, Key.DirectionRight)
        rule.onNodeWithTag(tab).assertIsFocused()
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("detail.hero").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(tab).assertIsFocused().assertIsDisplayed()
    }

    @Test fun nativeTvCardDetailReturnsToSameCardAndPosition() {
        scrollableHome(true, banner = true)
        repeat(6) { press(rule, Key.DirectionRight) }
        val card = rule.onNodeWithContentDescription("续播6").assertIsFocused()
        val before = card.fetchSemanticsNode().positionInRoot
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.hero").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        card.assertIsFocused().assertIsDisplayed()
        val after = card.fetchSemanticsNode().positionInRoot
        assertEquals(before.x, after.x, 2f)
        assertEquals(before.y, after.y, 2f)
    }

    @Test fun nativeTvRepeatedKeysReachBothEdgesAndReverse() {
        scrollableHome(true)
        rule.mainClock.autoAdvance = false
        for (code in listOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_LEFT)) {
            val start = android.os.SystemClock.uptimeMillis()
            repeat(24) { count ->
                rule.runOnUiThread {
                    activity.dispatchKeyEvent(android.view.KeyEvent(start, start + count * 32,
                        android.view.KeyEvent.ACTION_DOWN, code, count))
                }
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                rule.mainClock.advanceTimeBy(32)
            }
            rule.runOnUiThread { activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)) }
            rule.onNodeWithContentDescription(if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) "续播19" else "续播0")
                .assertIsFocused()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("续播0").assertIsFocused().assertIsDisplayed()
    }

    @Test fun nativeTvQuickVerticalReversalReturnsToTheSameCard() {
        scrollableHome(true)
        repeat(6) { press(rule, Key.DirectionRight) }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("续播6").assertIsNotFocused()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("续播6").assertIsFocused().assertIsDisplayed()
    }

    @Test fun nativeTvLibrariesStayInTheirRowAtBothEdges() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), false)
        mount()
        press(rule, Key.DirectionDown, Key.DirectionLeft)
        rule.onNode(hasContentDescription("电影") and hasAnyAncestor(hasTestTag("home.libraries"))).assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight)
        rule.onNode(hasContentDescription("剧集") and hasAnyAncestor(hasTestTag("home.libraries"))).assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("home.resume"))).assertExists()
    }

    @Test fun nativeTvPluginRowsKeepHorizontalFocus() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        UiPrefs.setHideHomeLibraries(ApplicationProvider.getApplicationContext(), true)
        mount {
            it.ret("emby.views", arr()); it.ret("emby.listResume", arr())
            it.ret("plugin.homeSections", arr(buildJsonObject {
                put("plugin_id", "test/rows"); put("id", "one"); put("title", "插件影片")
            }))
            it.ret("plugin.homeItems", arr(*(0..11).map { n -> buildJsonObject {
                put("id", "plugin-$n"); put("title", "插件$n"); put("source", "test/source")
            } }.toTypedArray()))
        }
        press(rule, Key.DirectionDown, Key.DirectionLeft)
        rule.onNodeWithText("插件0").assertIsFocused()
        repeat(13) { press(rule, Key.DirectionRight) }
        rule.onNodeWithText("插件11").assertIsFocused().assertIsDisplayed()
    }

    @Test fun homeFocusScrollRetargetsDuringAnimation() {
        scrollableHome(true)
        repeat(2) { press(rule, Key.DirectionRight) }
        val start = rule.onNodeWithContentDescription("续播3").fetchSemanticsNode().positionInRoot.x
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(48)
        val moving = rule.onNodeWithContentDescription("续播3").fetchSemanticsNode().positionInRoot.x
        assertTrue("焦点滚动必须经过中间位置", moving < start - 1f)
        for (key in listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft)) {
            rule.onRoot().performKeyInput { keyDown(key); keyUp(key) }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeBy(16)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("续播3").assertIsFocused().assertIsDisplayed()
        val end = rule.onNodeWithContentDescription("续播3").fetchSemanticsNode().positionInRoot.x
        assertTrue("不能只检查终态，采样帧应尚未抵达停靠位置", moving > end + 1f)
    }

    @Test fun homeReturnDoesNotStealFocusAfterNewDirection() {
        scrollableHome(true, banner = true)
        val vertical = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        fun scroll() = vertical.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("先让首页离顶", scroll() > 0f)
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var frames = 0
        while (scroll() > 0f && frames++ < 120) rule.mainClock.advanceTimeByFrame()
        assertEquals("列表已回顶且导航尚未获焦", 0f, scroll(), .01f)
        rule.onNodeWithTag("rail.1").assertIsNotFocused()
        rule.onNodeWithContentDescription("续播0").assertIsFocused()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithContentDescription("续播1").assertIsFocused()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("续播1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun homeReturnStopsWhenNewDirectionCannotMoveFocus() {
        scrollableHome(true, banner = true, onlyResume = true)
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("rail.1").assertIsNotFocused()
        rule.onNodeWithContentDescription("续播0").assertIsFocused()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithContentDescription("续播0").assertIsFocused()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("续播0").assertIsFocused().assertIsDisplayed()
    }

    @Test fun repeatedUpKeepsTheSameHomeReturnProgress() = checkRepeatedHomeReturn(true)
    @Test fun repeatedUpKeepsTheSameHomeReturnProgressWithoutLeanback() = checkRepeatedHomeReturn(false)

    private fun checkRepeatedHomeReturn(leanback: Boolean) {
        scrollableHome(leanback, banner = true)
        val vertical = rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
        fun scroll() = vertical.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        fun up() {
            // pressKey 默认额外推进50ms；轨迹比较只由主时钟推进时间。
            rule.onRoot().performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        val start = scroll()
        rule.mainClock.autoAdvance = false
        up()
        repeat(5) { rule.mainClock.advanceTimeBy(32) }
        val single = scroll()
        assertTrue("采样时回顶必须处于起终点之间", single > 0f && single < start)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("续播0").assertIsFocused()
        assertEquals("两轮从同一位置出发", start, scroll(), 1f)
        rule.mainClock.autoAdvance = false
        up()
        repeat(5) {
            rule.mainClock.advanceTimeBy(32)
            up()
        }
        assertEquals("持续按上不应反复重启回顶动画", single, scroll(), 2f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsFocused()
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
        rule.onNodeWithTag("detail.tools").performScrollTo().assertIsDisplayed()
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
                // 次要操作在播放上方一排，向上进入后横移
                press(rule, Key.DirectionUp)
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
        // 次要操作在播放上方：向上进图标行首位，横移到收藏和版本，向下回播放
        press(rule, Key.DirectionUp)
        rule.onNodeWithContentDescription("标已看").assertIsFocused()
        press(rule, Key.DirectionRight)
        rule.onNodeWithContentDescription("收藏").assertIsFocused()
        press(rule, Key.DirectionCenter)
        assertTrue(core.calls.any { it.first == "emby.setFavorite" && it.second?.get("fav") == JsonPrimitive(true) })
        press(rule, Key.DirectionRight)
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        rule.onNodeWithText("版本 · 1080p", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/shared-tv/detail-tools-focus.png")
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        // 播放向下直达演职人员首位，向上直回播放
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.person.p0").assertIsFocused().assertIsDisplayed()
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        val actor = SemanticsMatcher("演职员卡片") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("detail.person.") == true
        } and isFocused()
        rule.onNodeWithTag("detail.person.p0").assertIsFocused()
        rule.onNode(actor).assertIsDisplayed()
        for (step in 0 until 16) {
            if (rule.onAllNodes(hasTestTag("detail.play") and isFocused()).fetchSemanticsNodes().isNotEmpty()) break
            press(rule, Key.DirectionUp)
        }
        rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsFocused()
        press(rule, Key.DirectionUp, Key.DirectionUp)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail.tags"))).assertIsDisplayed()
        press(rule, Key.DirectionDown, Key.DirectionDown)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsDisplayed()
    }

    @Test fun detailUpEntersFirstAvailableToolWhilePlayedIsSaving() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        val saved = CompletableDeferred<Unit>()
        mount(intercept = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?,
                onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.setPlayed") saved.await()
                return fake.callJson(command, args, onPartial)
            }
        } })
        try {
            rule.runOnIdle {
                xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                    xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
            }
            rule.onNodeWithTag("detail.play").assertIsFocused()
            press(rule, Key.DirectionUp)
            rule.onNodeWithContentDescription("标已看").assertIsFocused()
            press(rule, Key.DirectionCenter)
            rule.onNodeWithContentDescription("标已看").assertIsNotEnabled()
            // 禁用项会释放焦点；建立播放起点，单独核验上返对可用项的选择。
            rule.onNodeWithTag("detail.play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            rule.onNodeWithTag("detail.play").assertIsFocused()
            press(rule, Key.DirectionUp)
            rule.onNodeWithContentDescription("收藏").assertIsFocused()
            rule.runOnIdle { saved.complete(Unit) }
            rule.waitForIdle()
            press(rule, Key.DirectionDown, Key.DirectionUp)
            rule.onNodeWithContentDescription("标已看").assertIsEnabled().assertIsFocused()
        } finally { saved.complete(Unit) }
    }

    @Test fun conciseDetailKeepsOneShortOverviewAndIconOnlyOptions() {
        val overview = "一封迟到的信，让故人重新相遇。"
        mount { fake ->
            val detail = fake.handlers.getValue("emby.itemDetail")
            fake.on("emby.itemDetail") { request ->
                JsonObject(detail(request).jsonObject + ("overview" to JsonPrimitive(overview)))
            }
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.onAllNodesWithText(overview).assertCountEquals(1)
        rule.onNodeWithText(overview).assertHasNoClickAction()
        rule.onNodeWithText("剧情简介").assertDoesNotExist()
        rule.onNodeWithText("播放选项").assertDoesNotExist()
        rule.onNodeWithTag("detail.play.options").assertHasClickAction()
        // 只有一个版本、一条音轨时不出对应按钮；视频规格并进数据行，不再是按钮
        for (label in listOf("版本", "视频", "音轨")) rule.onNodeWithContentDescription(label).assertDoesNotExist()
        rule.onNodeWithText("4K HDR10 HEVC", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("字幕") and hasAnyAncestor(hasTestTag("detail.tools"))).assertExists()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("detail.tools")) and hasText("字幕", substring = true))
            .assertCountEquals(0)
        press(rule, Key.DirectionRight)
        rule.onNodeWithTag("detail.play.options").assertIsFocused()
        rule.onNodeWithText("播放选项").assertDoesNotExist()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("从头开始播放").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("detail.play.options").assertIsFocused()
    }

    @Test fun longHeroOverviewOpensScrollableTextAndBackRestoresFocus() {
        val overview = "一封迟到的来信，让远行的人重新踏上归途。穿越山海，也重新认识彼此。".repeat(40)
        mount { fake ->
            val detail = fake.handlers.getValue("emby.itemDetail")
            fake.on("emby.itemDetail") { request ->
                JsonObject(detail(request).jsonObject + ("overview" to JsonPrimitive(overview)))
            }
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        val summary = rule.onNode(hasTestTag("detail.overview") and hasAnyAncestor(hasTestTag("detail.hero")))
        summary.assertIsDisplayed()
        rule.onAllNodesWithText(overview).assertCountEquals(1)
        press(rule, Key.DirectionUp, Key.DirectionUp)
        summary.assertIsFocused()
        press(rule, Key.DirectionCenter)
        val full = rule.onNodeWithTag("detail.overview.full", useUnmergedTree = true)
        full.assertIsDisplayed().assertIsFocused()
        val scroll = full.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, scroll.value())
        full.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertTrue("长简介应能用遥控器继续向下阅读", scroll.value() > 0f)
        rule.onNode(isDialog()).captureRoboImage("build/shared-tv/detail-overview.png")
        rule.runOnUiThread {
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        rule.waitForIdle()
        full.assertDoesNotExist()
        summary.assertIsFocused()
        press(rule, Key.DirectionDown, Key.DirectionDown)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.1").assertIsDisplayed()
    }

    @Test fun singleSeasonHeadingOnlyShowsItsNameWithoutFocus() {
        mount { fake ->
            cinemaSeries(fake)
            fake.ret("emby.seriesSeasons", arr(buildJsonObject {
                put("id", "se1"); put("name", "归途"); put("index_no", 1)
            }))
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "s1", "type" to "Series"), false)
        }
        rule.onNodeWithText("分集").assertDoesNotExist()
        rule.onNodeWithContentDescription("选择季").assertDoesNotExist()
        rule.onNode(hasText("第1季：归途") and hasAnyAncestor(hasTestTag("detail.episodes")))
            .assertIsDisplayed().assertHasNoClickAction()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.ep.se1e1").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("detail.play").assertIsFocused()
    }

    private fun overviewAnchor(mode: String) {
        mount { fake ->
            fake.ret("plugin.anchors", arr(buildJsonObject {
                put("plugin_id", "test/overview"); put("name", "简介测试")
                put("anchor", "detail.overview"); put("mode", mode); put("block", "overview")
            }))
            fake.on("plugin.ui.mount") { throw IllegalStateException("合成插件错误") }
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "m1", "type" to "Movie"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        rule.onAllNodesWithTag("detail.overview").assertCountEquals(if (mode in listOf("hide", "replace")) 0 else 1)
        if (mode != "hide") {
            rule.onAllNodesWithText("这一块出错了", substring = true).assertCountEquals(1)
            rule.onNode(hasText("这一块出错了", substring = true) and hasAnyAncestor(hasTestTag("detail.hero")))
                .assertIsDisplayed()
        }
    }

    @Test fun overviewBeforeAnchorAppearsOnlyOnceInHero() = overviewAnchor("before")
    @Test fun overviewAfterAnchorAppearsOnlyOnceInHero() = overviewAnchor("after")
    @Test fun overviewReplacementSuppressesOfficialSummary() = overviewAnchor("replace")
    @Test fun overviewHiddenAnchorSuppressesOfficialSummary() = overviewAnchor("hide")

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
        rule.onNode(hasContentDescription("版本") and hasAnyAncestor(hasTestTag("detail.hero")))
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithText("se1e2 标准版", substring = true).assertIsDisplayed()
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
        // 播放向下直达分集；版本等参数在播放上方的图标行
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("选择季").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNodeWithContentDescription("标已看").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.onNodeWithText("se1e2 高码率").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("版本").assertIsFocused()
        press(rule, Key.DirectionRight)
        rule.onNodeWithContentDescription("音轨").assertIsFocused()
        rule.onNodeWithContentDescription("视频").assertDoesNotExist()
        press(rule, Key.DirectionRight, Key.DirectionCenter)
        rule.onNodeWithText("英文字幕").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("字幕").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithContentDescription("选择季").assertIsFocused()
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
            rule.onNodeWithContentDescription("版本").assertExists()
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
            val detail = fake.handlers.getValue("emby.itemDetail")
            fake.on("emby.itemDetail") { request ->
                JsonObject(detail(request).jsonObject + ("people" to arr(buildJsonObject {
                    put("id", "actor"); put("name", "测试演员"); put("type_", "Actor")
                })))
            }
            fake.ret("emby.seriesSeasons", arr())
            fake.ret("emby.seasonEpisodes", buildJsonObject { put("items", arr()); put("total", 0) })
        }
        rule.runOnIdle {
            xyz.linplayer.app.plugin.PluginNav.host!!.push("detail",
                xyz.linplayer.app.ui.pages.args("id" to "se2e2", "type" to "Episode"), false)
        }
        rule.onNodeWithTag("detail.play").assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNodeWithTag("detail.person.actor").assertIsDisplayed().assertIsFocused()
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
            rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsNotEnabled()
            press(rule, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Menu)
            rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail.hero"))).assertDoesNotExist()
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult)
            rule.runOnIdle { detailReady.complete(Unit) }
            rule.waitForIdle()
            assertEquals(parent, core.calls.last { it.first == "emby.seasonEpisodes" }.second.str("parent_id"))
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsNotEnabled()
            press(rule, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Menu)
            rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("detail.hero"))).assertDoesNotExist()
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult)
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
            rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsNotEnabled()
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
