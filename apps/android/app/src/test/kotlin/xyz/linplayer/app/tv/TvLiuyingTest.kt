package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.pages.LiuyingContent

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvLiuyingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    @After fun clean() { scope.cancel(); if (::app.isInitialized) app.bg.cancel(); PageCache.clear() }

    @Test fun remoteSwitchesVideoAndReturnsWithoutChangingFilter() {
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", true) }) })
            ret("emby.extLiuYingLabels", arr())
            ret("emby.extLiuYingItems", buildJsonObject {
                put("items", arr(*listOf("ly-work-1", "ly-work-2").map { id ->
                    buildJsonObject { put("item_id", id); put("title", id) }
                }.toTypedArray()))
                put("has_more", false)
            })
            on("emby.itemDetail") { a -> buildJsonObject { put("id", a.str("item_id")); put("type_", "Movie") } }
            ret("player.stopPlayback", JsonNull)
            ret("player.setVolume", JsonNull)
            ret("prefs.getPrefs", buildJsonObject { put("default_speed", 3.0); put("liuying_speed", 1.5) })
            ret("player.setSpeed", JsonNull)
            ret("player.setPlaybackPrefs", JsonNull)
        }
        app = AppState(core, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalTvMode provides true) { TvFrame(app) { LiuyingContent({}, tv = true) } }
        }
        advance(rule, 1000)
        rule.runOnIdle { core.tick(1.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(1.25) }; advance(rule, 300)
        assertEquals("liuying", core.calls.single { it.first == "player.play" }.second.str("speed_scope"))
        rule.onNodeWithTag("liuying.speed").performClick(); advance(rule, 300)
        assertEquals(2.0, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.dbl("liuying_speed")!!, .001)
        assertNull(core.calls.last { it.first == "player.setPlaybackPrefs" }.second?.get("default_speed"))
        rule.onRoot().captureRoboImage("build/liuying/tv-feed.png")
        rule.onNodeWithText("静音").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.DirectionCenter); advance(rule, 300)
        rule.onNodeWithTag("liuying.next").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.DirectionCenter); advance(rule, 800)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        rule.onNodeWithText("开声音").assertExists()
        assertEquals(0, core.calls.last { it.first == "player.setVolume" }.second.long("volume")!!.toInt())
        val start = core.calls.indexOfLast { it.first == "player.play" }
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } in 0 until start)
        rule.onNodeWithTag("liuying.previous").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.DirectionCenter); advance(rule, 800)
        assertEquals("ly-work-1", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(1, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun 收藏列表分页后点播指定条目且下一条回随机() {
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", true) }) })
            ret("emby.extLiuYingLabels", arr())
            on("emby.extLiuYingItems") { a ->
                val favorite = a.bool("favorite")
                val offset = a.long("offset") ?: 0L
                buildJsonObject {
                    put("items", arr(buildJsonObject {
                        val id = if (favorite) "ly-work-f$offset" else "ly-work-random"
                        put("item_id", id); put("title", id); put("favorite", favorite)
                    }))
                    put("has_more", favorite && offset == 0L)
                }
            }
            on("emby.itemDetail") { a -> buildJsonObject { put("id", a.str("item_id")); put("type_", "Movie") } }
            ret("player.stopPlayback", JsonNull)
        }
        app = AppState(core, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalTvMode provides true) { TvFrame(app) { LiuyingContent({}, tv = true) } }
        }
        advance(rule, 1000)
        rule.onNodeWithTag("liuying.favorites").performSemanticsAction(SemanticsActions.OnClick) { it() }; advance(rule, 400)
        rule.onNodeWithTag("liuying.favoriteList").performScrollToIndex(1); advance(rule, 300)
        rule.onNodeWithTag("liuying.favoriteMore").performSemanticsAction(SemanticsActions.OnClick) { it() }; advance(rule, 400)
        rule.onNodeWithTag("liuying.favoriteList").performScrollToIndex(1); advance(rule, 300)
        rule.onNodeWithTag("liuying.favoriteSheet").captureRoboImage("build/liuying/tv-favorites.png")
        rule.onNode(hasAnyAncestor(hasTestTag("liuying.favoriteItem.ly-work-f1")) and hasClickAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }; advance(rule, 800)
        assertEquals("ly-work-f1", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        rule.onNodeWithText("循环当前").assertExists()
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        rule.onNodeWithTag("liuying.next").performSemanticsAction(SemanticsActions.OnClick) { it() }; advance(rule, 800)
        assertEquals("ly-work-random", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertFalse(core.calls.last { it.first == "emby.extLiuYingItems" }.second.bool("favorite"))
        assertEquals("", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("channel"))
    }

    @Test fun nextVideoWaitsForPendingFeedSpeedSave() {
        val save = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var saved = 1.5
        val reads = mutableListOf<Double>()
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", true) }) })
            ret("emby.extLiuYingLabels", arr())
            ret("emby.extLiuYingItems", buildJsonObject {
                put("items", arr(*listOf("ly-work-1", "ly-work-2").map { id ->
                    buildJsonObject { put("item_id", id); put("title", id) }
                }.toTypedArray()))
                put("has_more", false)
            })
            on("emby.itemDetail") { a -> buildJsonObject { put("id", a.str("item_id")); put("type_", "Movie") } }
            ret("player.stopPlayback", JsonNull)
            ret("player.setSpeed", JsonNull)
            on("prefs.getPrefs") {
                reads += saved
                buildJsonObject { put("liuying_speed", saved) }
            }
            on("player.setPlaybackPrefs") { a -> saved = a.dbl("liuying_speed")!!; JsonNull }
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.setPlaybackPrefs") { started.complete(Unit); save.await() }
                return core.callJson(command, args, onPartial)
            }
        }
        app = AppState(port, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalTvMode provides true) { TvFrame(app) { LiuyingContent({}, tv = true) } }
        }
        try {
            advance(rule, 1000)
            rule.runOnIdle { core.tick(1.0) }; advance(rule, 300)
            rule.runOnIdle { core.tick(1.25) }; advance(rule, 300)
            rule.onNodeWithTag("liuying.speed").performSemanticsAction(SemanticsActions.OnClick) { it() }; advance(rule, 300)
            assertTrue(started.isCompleted)
            val readCount = reads.size
            rule.onNodeWithTag("liuying.next").performSemanticsAction(SemanticsActions.OnClick) { it() }; advance(rule, 800)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertEquals(readCount, reads.size)
            rule.runOnIdle { save.complete(Unit) }; advance(rule, 800)
            assertEquals("ly-work-2", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals(2.0, reads.last(), .001)
            rule.onNodeWithTag("liuying.speed").assertTextContains("2.0x")
        } finally { save.complete(Unit) }
    }

    @Test fun emptyLibraryRemainsNativeAndSwitchesFeedWithoutCapabilityRefresh() {
        val core = FakeCore().loggedIn().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", true) }) })
            ret("emby.extLiuYingLabels", arr())
            ret("emby.extLiuYingItems", buildJsonObject { put("items", arr()); put("has_more", false) })
        }
        app = AppState(core, scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        var exited = false
        rule.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalTvMode provides true) {
                TvFrame(app) { LiuyingContent({ exited = true }, tv = true) }
            }
        }
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        rule.onNodeWithText("返回").performSemanticsAction(SemanticsActions.RequestFocus)
        press(rule, Key.DirectionRight)
        rule.onNodeWithTag("liuying.bankai").assertIsFocused()
        press(rule, Key.DirectionCenter)
        rule.waitForIdle()
        assertEquals("adult", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("feed"))
        assertEquals(1, core.calls.count { it.first == "emby.extCapabilities" })
        assertNull(core.calls.single { it.first == "emby.extCapabilities" }.second?.get("refresh"))
        assertFalse(core.calls.any { it.first == "player.play" })
        press(rule, Key.DirectionLeft, Key.DirectionCenter)
        assertTrue(exited)
    }
}
