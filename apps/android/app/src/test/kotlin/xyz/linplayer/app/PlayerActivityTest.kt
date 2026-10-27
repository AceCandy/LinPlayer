package xyz.linplayer.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.player
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.ui.pages.SourceSwitchTarget

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PlayerActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var host: ActivityController<TestPlayerActivity>? = null

    @After fun cleanup() {
        host?.pause()?.stop()?.destroy()
        PlaybackService.stop(RuntimeEnvironment.getApplication())
        TestPlayerActivity.state?.bg?.cancel()
        TestPlayerActivity.state = null
        scope.cancel()
    }

    @Test fun manifestChoosesDirectionBeforePlayerCreationAndKeepsAllParameters() {
        val context = RuntimeEnvironment.getApplication()
        val route = Route.Player("m1", "标题", "v2", "exo", 16f / 9,
            src = "{\"item\":\"source-item\"}", fromStart = true,
            subtitle = "{\"sourceId\":\"v2\",\"index\":4}")
        for ((ratio, target, direction) in listOf(
            Triple(16f / 9, PlayerActivity::class.java, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
            Triple(9f / 16, PortraitPlayerActivity::class.java, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
            Triple(0f, PlayerActivity::class.java, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        )) {
            val expected = route.copy(ar = ratio)
            val intent = PlayerActivity.intent(context, expected, 42)
            assertEquals(ComponentName(context, target), intent.component)
            assertEquals(expected, PlayerActivity.readRoute(intent))
            val info = context.packageManager.getActivityInfo(intent.component!!, 0)
            assertFalse(info.exported)
            assertEquals(direction, info.screenOrientation)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
            assertTrue(org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(info, "supportsPictureInPicture"))
        }
    }

    @Test fun privatePlaybackIntentCannotReopenAfterProcessUnlockIsLost() {
        val core = FakeCore().loggedIn().player()
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        val intent = PlayerActivity.intent(context, Route.Player("m1", "标题"), privateSpace = true)
            .setClass(context, TestPlayerActivity::class.java)
        host = Robolectric.buildActivity(TestPlayerActivity::class.java, intent).create()
        assertTrue(host!!.get().isFinishing)
        assertFalse(core.calls.any { it.first == "player.play" })
    }

    @Test fun privatePlaybackRejectsPluginSourceBeforeAnySourceRequest() {
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.views", Json.parseToJsonElement("""[{"id":"classified","is_adult":false}]"""))
        }
        val app = AppState(core, scope)
        app.enablePrivacyFiltering()
        runBlocking { app.boot(); app.enterPrivateSpace() }
        assertTrue(app.privateSpace.value)
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        host = Robolectric.buildActivity(TestPlayerActivity::class.java,
            PlayerActivity.intent(context, Route.Player("source-item", "源影片", src = switchSource), privateSpace = true)
                .setClass(context, TestPlayerActivity::class.java)).create()
        assertTrue(host!!.get().isFinishing)
        assertFalse(core.calls.any { it.first == "source.playItem" || it.first == "source.switchCandidates" })
    }

    @Test fun pluginSourceWithoutEmbySessionKeepsPlayerOpenAfterStartupAndEarlyEof() {
        val core = openPluginPlayer()
        rule.onNodeWithTag("player.video").assertIsDisplayed()
        assertFalse(host!!.get().isFinishing)
        assertEquals(1, core.calls.count { it.first == "source.playItem" })
        assertFalse(core.calls.any { it.first == "player.play" })
        rule.runOnIdle {
            core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status",
                kotlinx.serialization.json.buildJsonObject {
                    put("position", kotlinx.serialization.json.JsonPrimitive(0.0))
                    put("duration", kotlinx.serialization.json.JsonPrimitive(0.0))
                    put("eof", kotlinx.serialization.json.JsonPrimitive(true))
                }))
        }
        rule.onNodeWithText("这一片没能播起来").assertIsDisplayed()
        assertFalse(host!!.get().isFinishing)
    }

    @Test fun pluginSourceResolveFailureDoesNotFinishPlayerActivity() {
        openPluginPlayer(fail = true)
        rule.onNode(hasText("换源") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertFalse(host!!.get().isFinishing)
    }

    private val switchSource = """{"server_id":"plugin:test/tvbox/a","item":{"id":"source-item","title":"测试源影片","lines":[{"id":"line","episodes":[{"id":"ep4","index":4}]},{"id":"line2","episodes":[{"id":"alt4","index":4}]}]},"line_id":"line","episode_id":"ep4","resume_secs":125}"""

    private fun switchCandidate(layer: Int) = kotlinx.serialization.json.Json.parseToJsonElement(when (layer) {
        1 -> """{"layer":1,"line_id":"line2","line_name":"备用线路","episode_id":"alt4"}"""
        2 -> """{"layer":2,"server_id":"plugin:test/tvbox/b","server_name":"备用源","item":{"id":"alternate","title":"备用影片"}}"""
        else -> """{"layer":3,"server_id":"http://emby-b.invalid","server_name":"备用服务器","emby_item":{"id":"emby-series","name":"备用剧集","type_":"Series"}}"""
    })

    private fun lineSwitchKeepsResume(tv: Boolean) {
        val core = openPluginPlayer(tv = tv, source = switchSource) { fake ->
            fake.on("source.playItem") { args ->
                if (args.str("line_id") == "line") throw xyz.linplayer.app.core.CoreException("E_UPSTREAM", "测试取流失败", false)
                JsonNull
            }
            fake.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(1)))
        }
        rule.waitForIdle()
        val attempts = core.calls.count { it.first == "source.playItem" }
        rule.onNodeWithText("备用线路").performClick()
        rule.waitForIdle()
        val plays = core.calls.withIndex().filter { it.value.first == "source.playItem" }
        assertEquals(attempts + 1, plays.size)
        assertEquals("line2", plays.last().value.second.str("line_id"))
        assertEquals("alt4", plays.last().value.second.str("episode_id"))
        assertEquals(125.0, plays.last().value.second.dbl("resume_secs")!!, 0.0)
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } in
            (plays[plays.lastIndex - 1].index + 1) until plays.last().index)
        assertFalse(host!!.get().isFinishing)
        assertNull(shadowOf(host!!.get()).nextStartedActivityForResult)
    }

    @Test fun pluginLineSwitchKeepsResumeAndStopsBeforeStartingWithoutAnotherActivity() = lineSwitchKeepsResume(tv = false)
    @Test fun televisionLineSwitchKeepsResumeAndStopsBeforeStartingWithoutAnotherActivity() = lineSwitchKeepsResume(tv = true)

    private fun failedSourceReturnsToBrowser(layer: Int, tv: Boolean = false) {
        val core = openPluginPlayer(fail = true, tv = tv, source = switchSource) { fake ->
            fake.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(layer)))
            fake.ret("account.setActiveServer", JsonNull)
            fake.ret("source.linkSwitch", JsonNull)
        }
        rule.onNodeWithText(if (layer == 2) "备用影片" else "备用剧集", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(host!!.get().isFinishing)
        val returned = shadowOf(host!!.get()).resultIntent.getStringExtra(PlayerActivity.SOURCE_SWITCH)
        assertNotNull(returned)
        val target = Json.decodeFromString<SourceSwitchTarget>(returned!!)
        if (layer == 2) {
            assertEquals(SourceSwitchTarget.Source(Route.SourceDetail("plugin:test/tvbox/b", "alternate",
                autoPos = 125.0, autoIndex = 4), "plugin:test/tvbox/a", "source-item"), target)
        } else assertEquals(SourceSwitchTarget.Emby("http://emby-b.invalid", Route.Detail("emby-series", "Series")), target)
        assertNull(shadowOf(host!!.get()).resultIntent.getStringExtra(PlayerActivity.NAV_ROUTE))
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        assertFalse(core.calls.any { it.first in setOf("source.linkSwitch", "account.setActiveServer") })
    }

    @Test fun pluginCrossSourceReturnsToBrowserInsteadOfUnregisteredDestination() = failedSourceReturnsToBrowser(2)
    @Test fun pluginEmbySwitchReturnsToBrowserBeforeChangingAccount() = failedSourceReturnsToBrowser(3)
    @Test fun televisionPluginFailureOffersTheSameCrossSourceChoice() = failedSourceReturnsToBrowser(2, tv = true)
    @Test fun televisionEmbySwitchReturnsToBrowserBeforeChangingAccount() = failedSourceReturnsToBrowser(3, tv = true)

    private fun sourceExitWaitsForStop(tv: Boolean) {
        val stopped = CompletableDeferred<Unit>()
        val core = openPluginPlayer(fail = true, tv = tv, source = switchSource, intercept = { fake ->
            object : CorePort by fake {
                override suspend fun callJson(command: String, args: JsonObject?,
                    onPartial: ((JsonElement) -> Unit)?): JsonElement {
                    val result = fake.callJson(command, args, onPartial)
                    if (command == "player.stopPlayback") stopped.await()
                    return result
                }
            }
        }) { it.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(2))) }
        try {
            rule.onNodeWithText("备用影片", substring = true).performClick()
            rule.waitForIdle()
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
            assertFalse(host!!.get().isFinishing)
            rule.runOnIdle { stopped.complete(Unit) }
            rule.waitForIdle()
            assertTrue(host!!.get().isFinishing)
            assertNotNull(shadowOf(host!!.get()).resultIntent.getStringExtra(PlayerActivity.SOURCE_SWITCH))
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        } finally { stopped.complete(Unit) }
    }

    @Test fun phoneCrossSourceWaitsForPendingStop() = sourceExitWaitsForStop(tv = false)
    @Test fun televisionCrossSourceWaitsForPendingStop() = sourceExitWaitsForStop(tv = true)

    private fun dismissSourceDialog() {
        rule.runOnIdle {
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        rule.waitForIdle()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun televisionRemoteCanCloseReopenAndChooseFailedSource() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val wasTouch = org.robolectric.shadows.ShadowWindowManagerGlobal.getInTouchMode()
        instrumentation.setInTouchMode(false)
        try {
            openPluginPlayer(fail = true, tv = true, source = switchSource) {
                it.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(1), switchCandidate(2)))
            }
            rule.onNode(hasText("备用线路") and hasAnyAncestor(isDialog())).assertIsFocused()
            rule.onNode(isDialog()).captureRoboImage("build/player-activity/tv-source-switch.png")
            dismissSourceDialog()
            assertFalse(host!!.get().isFinishing)
            rule.onRoot().captureRoboImage("build/player-activity/tv-source-failure.png")
            rule.onNodeWithText("重试").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            rule.onNodeWithText("换源").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            rule.waitForIdle()
            rule.onNode(hasText("备用线路") and hasAnyAncestor(isDialog())).assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            rule.onNode(hasText("备用影片", substring = true) and hasAnyAncestor(isDialog())).assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            rule.waitForIdle()
            assertTrue(host!!.get().isFinishing)
        } finally { instrumentation.setInTouchMode(wasTouch) }
    }

    @Test fun sourceCandidateFailureIsVisibleAndCanRetry() {
        val core = openPluginPlayer(fail = true, source = switchSource) {
            it.on("source.switchCandidates") { throw xyz.linplayer.app.core.CoreException("E_NETWORK", "候选查询测试失败", true) }
        }
        rule.onNodeWithText("候选查询测试失败", substring = true).assertIsDisplayed()
        rule.onNodeWithText("别的源里都没找到这部片。可以给更多源打开「允许聚合」。").assertDoesNotExist()
        core.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(2)))
        rule.onNode(hasText("重试") and hasAnyAncestor(isDialog())).performClick()
        rule.onNodeWithText("备用影片", substring = true).assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "source.switchCandidates" })
    }

    @Test fun dismissedCandidateQueryCannotOverwriteReopenedDialog() {
        val release = CompletableDeferred<Unit>()
        var first = true
        openPluginPlayer(fail = true, source = switchSource, intercept = { fake ->
            object : CorePort by fake {
                override suspend fun callJson(command: String, args: JsonObject?,
                    onPartial: ((JsonElement) -> Unit)?): JsonElement {
                    val result = fake.callJson(command, args, onPartial)
                    if (command == "source.switchCandidates" && first) {
                        first = false
                        withContext(NonCancellable) { release.await() }
                        val old = Json.parseToJsonElement("""[{"layer":2,"server_id":"plugin:test/tvbox/b","item":{"id":"old","title":"过期候选"}}]""")
                        onPartial?.invoke(old)
                        return old
                    }
                    return result
                }
            }
        }) { it.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(2))) }
        try {
            rule.onNodeWithText("找其它源…").assertIsDisplayed()
            dismissSourceDialog()
            rule.onNodeWithText("换源").performClick()
            rule.onNodeWithText("备用影片", substring = true).assertIsDisplayed()
            rule.runOnIdle { release.complete(Unit) }
            rule.waitForIdle()
            rule.onNodeWithText("备用影片", substring = true).assertIsDisplayed()
            rule.onNodeWithText("过期候选").assertDoesNotExist()
        } finally { release.complete(Unit) }
    }

    private fun cancelledLineSwitchKeepsOldTarget(tv: Boolean) {
        val stopped = CompletableDeferred<Unit>()
        val core = openPluginPlayer(fail = true, tv = tv, source = switchSource, intercept = { fake ->
            object : CorePort by fake {
                override suspend fun callJson(command: String, args: JsonObject?,
                    onPartial: ((JsonElement) -> Unit)?): JsonElement {
                    val result = fake.callJson(command, args, onPartial)
                    if (command == "player.stopPlayback") withContext(NonCancellable) { stopped.await() }
                    return result
                }
            }
        }) { it.ret("source.switchCandidates", xyz.linplayer.app.tv.arr(switchCandidate(1))) }
        try {
            rule.onNodeWithText("备用线路").performClick()
            rule.waitForIdle()
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
            dismissSourceDialog()
            val plays = core.calls.count { it.first == "source.playItem" }
            rule.runOnIdle { stopped.complete(Unit) }
            rule.waitForIdle()
            assertEquals(plays, core.calls.count { it.first == "source.playItem" })
            assertFalse(host!!.get().isFinishing)
        } finally { stopped.complete(Unit) }
    }

    @Test fun dismissedPhoneLineSwitchCannotStartAfterLateStop() = cancelledLineSwitchKeepsOldTarget(tv = false)
    @Test fun dismissedTelevisionLineSwitchCannotStartAfterLateStop() = cancelledLineSwitchKeepsOldTarget(tv = true)

    private fun openPluginPlayer(fail: Boolean = false, tv: Boolean = false, source: String? = null,
        intercept: ((FakeCore) -> CorePort)? = null,
        configure: (FakeCore) -> Unit = {}): FakeCore {
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.currentSession", JsonNull)
            ret("account.listAccounts", xyz.linplayer.app.tv.arr(kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive("plugin:test/tvbox/a"))
                put("server", kotlinx.serialization.json.JsonPrimitive("plugin:test/tvbox/a"))
                put("name", kotlinx.serialization.json.JsonPrimitive("测试源"))
                put("active", kotlinx.serialization.json.JsonPrimitive(true))
                put("plugin", kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("test/tvbox"))
                })
            }))
            on("source.playItem") {
                if (fail) throw xyz.linplayer.app.core.CoreException("E_UPSTREAM", "测试取流失败", true)
                JsonNull
            }
            ret("source.switchCandidates", xyz.linplayer.app.tv.arr())
            ret("player.stopPlayback", JsonNull)
        }.also(configure)
        val app = AppState(intercept?.invoke(core) ?: core, scope)
        runBlocking { app.boot() }
        assertNull(app.session.value)
        assertNotNull(app.activeSource.value)
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val route = Route.Player("source-item", "测试源影片", src = source ?:
            """{"server_id":"plugin:test/tvbox/a","item":{"id":"source-item","title":"测试源影片","lines":[]},"line_id":"line","episode_id":"https://video.invalid/play.m3u8?name=%E6%B5%8B%E8%AF%95&part=1","resume_secs":0}""")
        host = Robolectric.buildActivity(TestPlayerActivity::class.java,
            PlayerActivity.intent(context, route, tv = tv).setClass(context, TestPlayerActivity::class.java)).setup()
        rule.waitUntil(5_000) { core.calls.any { it.first == "source.playItem" } }
        return core
    }

    @Test fun televisionIntentKeepsParametersRemoteControlsAndReturnsLastEpisode() {
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            ret("player.setTrack", JsonNull)
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        val route = Route.Player("sh6", "测试", "ea", "mpv", 9f / 16, fromStart = true,
            subtitle = "{\"sourceId\":\"ea\",\"index\":3}")
        val intent = PlayerActivity.intent(context, route, tv = true)
        assertEquals(ComponentName(context, PlayerActivity::class.java), intent.component)
        assertEquals(route, PlayerActivity.readRoute(intent))
        host = Robolectric.buildActivity(TestPlayerActivity::class.java,
            intent.setClass(context, TestPlayerActivity::class.java)).setup()
        rule.waitUntil(5_000) { core.calls.any { it.first == "player.play" } }
        rule.onNodeWithTag("player.root").assertIsDisplayed()
        assertEquals("ea", core.calls.single { it.first == "player.play" }.second.str("media_source_id"))
        assertTrue(core.calls.single { it.first == "player.play" }.second.bool("from_start"))
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertEquals("2", core.calls.single { it.first == "player.setTrack" }.second.str("id"))
        rule.onNodeWithTag("player.root").performKeyInput { pressKey(Key.MediaNext) }
        rule.waitForIdle()
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        rule.onNodeWithTag("player.root").performKeyInput { pressKey(Key.MediaStop) }
        rule.waitUntil { host!!.get().isFinishing }
        assertEquals("sh7", shadowOf(host!!.get()).resultIntent.getStringExtra(PlayerActivity.PLAYED_ITEM))
        assertEquals(2, core.calls.count { it.first == "player.stopPlayback" })
        assertFalse(host!!.get().enterPlayerPip())
    }

    @Test fun televisionLocalFileUsesSourcePlaybackAndDoesNotReturnEmbyItem() {
        val core = FakeCore().loggedIn().player().apply {
            ret("source.play", JsonNull)
            ret("player.stopPlayback", JsonNull)
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        val route = Route.Player("file-entry", "本地影片", localEntry = true)
        val intent = PlayerActivity.intent(context, route, tv = true)
            .setClass(context, TestPlayerActivity::class.java)
        host = Robolectric.buildActivity(TestPlayerActivity::class.java, intent).setup()
        rule.waitUntil(5_000) { core.calls.any { it.first == "source.play" } }
        assertEquals("file-entry", core.calls.single { it.first == "source.play" }.second.str("entry_id"))
        assertFalse(core.calls.any { it.first == "player.play" })
        rule.onNodeWithTag("player.root").performKeyInput { pressKey(Key.MediaStop) }
        rule.waitUntil { host!!.get().isFinishing }
        assertNull(shadowOf(host!!.get()).resultIntent.getStringExtra(PlayerActivity.PLAYED_ITEM))
    }

    @Test fun staleNotificationDoesNotStartAnotherPlayback() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(PlayerActivity::class.java,
            Intent(context, PlayerActivity::class.java)).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun manualPipOpensHomeOnlyAfterSuccessfulEntry() {
        val core = FakeCore().loggedIn().player()
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val intent = PlayerActivity.intent(context, Route.Player("m1", "测试", engine = "mpv", ar = 16f / 9))
            .setClass(context, TestPlayerActivity::class.java)
        host = Robolectric.buildActivity(TestPlayerActivity::class.java, intent).setup()
        rule.waitUntil(5_000) { app.wantsPip }
        val activity = host!!.get()
        rule.runOnIdle {
            activity.pipResult = false
            assertFalse(activity.enterPlayerPip(returnToHome = true))
            assertNull(shadowOf(activity).nextStartedActivity)
            activity.pipResult = true
            assertTrue(activity.enterPlayerPip())
            assertNull("Home 手势不能重复启动桌面", shadowOf(activity).nextStartedActivity)
            assertTrue(activity.enterPlayerPip(returnToHome = true))
            val home = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_MAIN, home.action)
            assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
            assertTrue(home.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertFalse(activity.isFinishing)
            assertFalse(core.calls.any { it.first == "player.stopPlayback" })
        }
    }

    @Test fun standalonePlayerKeepsPlaybackOnNotificationReturnAndFinishesWithoutUnlocking() {
        val core = FakeCore().loggedIn().player().apply { ret("player.stopPlayback", JsonNull) }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        val intent = PlayerActivity.intent(context,
            Route.Player("m1", "测试", "v2", "mpv", 16f / 9, fromStart = true))
            .setClass(context, TestPlayerActivity::class.java)
        host = Robolectric.buildActivity(TestPlayerActivity::class.java, intent).setup()
        rule.waitUntil(5_000) { core.calls.any { it.first == "player.play" } }
        rule.waitForIdle()
        val activity = host!!.get()
        val play = core.calls.single { it.first == "player.play" }.second
        assertEquals("v2", play.str("media_source_id"))
        assertEquals("mpv", play.str("engine"))
        assertTrue(play.bool("from_start"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)

        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            service.get().onStartCommand(Intent(context, PlaybackService::class.java), 0, 1)
            val notification = shadowOf(service.get()).lastForegroundNotification
            val resume = shadowOf(notification.contentIntent).savedIntent
            assertEquals(activity.componentName, resume.component)
            assertNull("通知只能唤回现有窗口，不能用旧路由重新起播", PlayerActivity.readRoute(resume))
            assertTrue(resume.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
            host!!.newIntent(resume)
            rule.waitForIdle()
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertFalse(core.calls.any { it.first == "player.stopPlayback" })
            rule.onRoot().captureRoboImage("build/player-activity/landscape.png")
            rule.onNodeWithContentDescription("切换方向").performClick()
            rule.runOnIdle {
                RuntimeEnvironment.setQualifiers("w440dp-h960dp-port-mdpi")
                val config = android.content.res.Configuration(activity.resources.configuration)
                activity.onConfigurationChanged(config)
                activity.window.setLayout(440, 960)
                val decor = activity.window.decorView
                decor.dispatchConfigurationChanged(config)
                decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(440, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(960, android.view.View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 440, 960)
            }
            rule.waitForIdle()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertFalse(core.calls.any { it.first == "player.stopPlayback" })
            val portraitBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("配置与窗口必须同时变成竖屏", portraitBounds.height > portraitBounds.width)
            rule.onRoot().captureRoboImage("build/player-activity/portrait.png")

            rule.runOnIdle {
                core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("plugin.shellRequest",
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(72))
                        put("op", kotlinx.serialization.json.JsonPrimitive("ui.confirm"))
                        put("args", kotlinx.serialization.json.buildJsonObject {
                            put("title", kotlinx.serialization.json.JsonPrimitive("播放中确认"))
                        })
                    }))
            }
            rule.onNodeWithText("播放中确认").assertIsDisplayed()
            rule.onNodeWithText("确定").performClick()
            rule.waitUntil { core.calls.any { it.first == "plugin.shellResult" } }
            assertTrue(core.calls.single { it.first == "plugin.shellResult" }.second.bool("data"))
            rule.onNodeWithContentDescription("返回").performClick()
            rule.waitUntil { activity.isFinishing }
            assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertEquals("m1", shadowOf(activity).resultIntent.getStringExtra(PlayerActivity.PLAYED_ITEM))
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
            host!!.pause().stop().destroy()
            host = null
            rule.waitUntil { core.calls.any { it.first == "player.stopPlayback" } }
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        } finally { service.destroy() }
    }
}

class TestPlayerActivity : PlayerActivity() {
    var pipResult = true
    override fun enterPictureInPictureMode(params: android.app.PictureInPictureParams): Boolean = pipResult

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(R.style.Theme_LinPlayer_Player)
        super.onCreate(savedInstanceState)
    }
    override fun playerApp(): AppState = requireNotNull(state)
    companion object { var state: AppState? = null }
}
