package xyz.linplayer.app

import android.app.Application
import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PhoneEnginePlaybackTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playbackService: ServiceController<PlaybackService>? = null
    private lateinit var activity: Activity

    @After fun cleanup() {
        playbackService?.destroy()
        PlaybackService.stop(ApplicationProvider.getApplicationContext())
        scope.cancel()
    }

    @Test fun 全屏倍率菜单沿用实际调速与全局偏好提交() {
        val core = episodeCore()
        showPlayer(core, "mpv")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("倍速").performClick(); advance(rule, 300)
        rule.onNodeWithText("1.5×").performClick(); advance(rule, 300)
        rule.onNodeWithTag("player.menu").assertDoesNotExist()
        assertEquals(1.5, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        assertEquals(1.5, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.dbl("default_speed")!!, .001)
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 手机完播连续切两集且旧集停止先于新集起播() {
        val core = episodeCore()
        showPlayer(core, "mpv")
        for (next in listOf("sh7", "sh8")) {
            rule.runOnIdle { core.tick(3658.0) }
            advance(rule, 300)
            rule.runOnIdle { core.tick(3658.25) }
            advance(rule, 300)
            rule.runOnIdle { core.tick(3660.0, eof = true) }
            advance(rule, 600)
            assertEquals(next, core.calls.last { it.first == "player.play" }.second.str("item_id"))
            val play = core.calls.indexOfLast { it.first == "player.play" }
            val stop = core.calls.indexOfLast { it.first == "player.stopPlayback" }
            assertTrue("旧集必须先停播", stop in 0 until play)
            if (next == "sh7") {
                rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status", buildJsonObject {
                    put("item_id", "sh6"); put("position", 3660.0); put("duration", 3660.0); put("eof", true)
                })) }
                advance(rule, 600)
                assertEquals(2, core.calls.count { it.first == "player.play" })
                rule.onNode(hasText("这一片没能播起来")).assertDoesNotExist()
            }
        }
        assertEquals(3, core.calls.count { it.first == "player.play" })
    }

    @Test fun 手机中途断流不得自动切到下一集() {
        val core = episodeCore()
        showPlayer(core, "mpv")
        rule.runOnIdle { core.tick(40.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(40.25) }; advance(rule, 300)
        rule.runOnIdle { core.tick(41.0, eof = true) }; advance(rule, 600)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
    }

    @Test fun 手机完播等待分集加载且迟到后只切一次() {
        val core = episodeCore()
        val release = CompletableDeferred<Unit>()
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "emby.seasonEpisodes") release.await()
                return result
            }
        }
        try {
            showPlayer(port, "mpv")
            rule.runOnIdle { core.tick(3658.0) }; advance(rule, 300)
            rule.runOnIdle { core.tick(3658.25) }; advance(rule, 300)
            rule.runOnIdle { core.tick(3660.0, eof = true) }; advance(rule, 600)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertFalse(core.calls.any { it.first == "player.stopPlayback" })
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals(2, core.calls.count { it.first == "player.play" })
        } finally { release.complete(Unit) }
    }

    @Test fun 手机Media3完播复用停止屏障切下一集() {
        val core = episodeCore()
        showPlayer(core, "exo")
        fun state(position: Long, ended: Boolean = false) {
            rule.runOnIdle {
                val info = ReflectionHelpers.getField<Any>(currentExo(), "playbackInfo")
                val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(3_660_000_000L, true, false, false, null,
                    androidx.media3.common.MediaItem.fromUri("asset:///episode-test.mp4"))
                val period = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
                ReflectionHelpers.setField(info, "timeline", timeline)
                ReflectionHelpers.setField(info, "periodId", period)
                ReflectionHelpers.setField(info, "loadingMediaPeriodId", period)
                ReflectionHelpers.setField(info, "positionUs", position * 1000)
                ReflectionHelpers.setField(info, "bufferedPositionUs", position * 1000)
                ReflectionHelpers.setField(info, "playbackState", if (ended) androidx.media3.common.Player.STATE_ENDED else androidx.media3.common.Player.STATE_READY)
            }
            advance(rule, 300)
        }
        state(3_658_000); state(3_658_250); state(3_660_000, true)
        advance(rule, 600)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "player.play" })
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })
    }

    private fun episodeCore() = FakeCore().loggedIn().player().apply {
        ret("player.stopPlayback", JsonNull)
        on("emby.itemDetail") { a -> buildJsonObject {
            val id = a.str("item_id")!!
            put("id", id); put("name", id); put("type_", "Episode")
            put("season_id", "sh"); put("series_id", "s2"); put("season_no", 1)
            put("episode_no", id.removePrefix("sh").toInt())
        } }
    }

    @Test fun MPV切集等待新尺寸期间保持方向锁() = verifySwitchDirection("mpv")

    @Test fun Media3切集等待新尺寸期间保持方向锁() = verifySwitchDirection("exo")
    @Test fun MPV自动换集保持方向锁() = verifySwitchDirection("mpv", automatic = true)
    @Test fun Media3自动换集保持方向锁() = verifySwitchDirection("exo", automatic = true)
    @Test fun MPV换版本保持方向锁() = verifySwitchDirection("mpv", version = true)
    @Test fun Media3换版本保持方向锁() = verifySwitchDirection("exo", version = true)

    @Test fun 手机接下来独立于控制栏且点击复用停止屏障() {
        val core = episodeCore()
        showPlayer(core, "mpv")
        rule.runOnIdle { core.tick(3628.75) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3629.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        rule.runOnIdle { core.tick(3630.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.setOsdVisible(false) }
        advance(rule, 500)
        rule.onNodeWithContentDescription("更多").assertDoesNotExist()
        rule.onNodeWithText("接下来").assertIsDisplayed()
        rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status", buildJsonObject {
            put("item_id", "sh6"); put("position", 3635.0); put("duration", 3660.0)
            put("paused", true); put("buffering", false)
        })) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertIsDisplayed()
        rule.runOnIdle { core.tick(3629.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        rule.runOnIdle { core.tick(3635.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").performClick()
        advance(rule, 500)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })
        rule.onNodeWithText("接下来").assertDoesNotExist()
    }

    @Test fun 短剧接下来只在最后十秒显示并可回退撤回() {
        val core = episodeCore()
        showPlayer(core, "mpv")
        fun tickAt(position: Double) {
            rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status", buildJsonObject {
                put("item_id", "sh6"); put("position", position); put("duration", 35.0)
                put("paused", false); put("buffering", false)
            })) }
            advance(rule, 300)
        }
        tickAt(.25); tickAt(.5)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        tickAt(5.0)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        tickAt(24.99)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        tickAt(25.0)
        rule.onNodeWithText("接下来").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/next-up-short-start.png")
        tickAt(30.0)
        rule.onNodeWithText("接下来").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/next-up-short-half.png")
        tickAt(24.99)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
    }

    private fun verifySwitchDirection(mode: String, automatic: Boolean = false, version: Boolean = false) {
        val release = CompletableDeferred<Unit>()
        var waiting = false
        val core = episodeCore().apply {
            on("emby.itemMedia") { arr(*listOf("ea", "eb").map { id -> buildJsonObject {
                put("id", id); put("name", if (id == "ea") "2160p" else "1080p")
                put("streams", arr(buildJsonObject {
                    put("type_", "Video"); put("width", 1920); put("height", 1080)
                }))
            } }.toTypedArray()) }
            on("player.play") { a -> buildJsonObject { put("media_source_id", if (a.str("media_source_id") == "eb") "eb" else "ea"); put("resume_secs", 0.0) } }
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "emby.itemMedia" && (args.str("item_id") == "sh7" ||
                    version && core.calls.count { it.first == "player.play" } > 1)) {
                    waiting = true
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                return result
            }
        }
        try {
            showPlayer(port, mode, ar = 0f)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            if (automatic) {
                if (mode == "mpv") {
                    rule.runOnIdle { core.tick(3658.0) }; advance(rule, 300)
                    rule.runOnIdle { core.tick(3658.25) }; advance(rule, 300)
                    rule.runOnIdle { core.tick(3660.0, eof = true) }
                } else {
                    exoState(3_658_000); advance(rule, 300)
                    exoState(3_658_250); advance(rule, 300)
                    exoState(3_660_000, ended = true)
                }
            } else {
                rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel(if (version) "lines" else "episodes") }
                advance(rule, 300)
                rule.onNode(hasText(if (version) "1080p" else "S1E7", substring = true)).performClick()
            }
            advance(rule, 500)
            assertTrue("新集尺寸请求必须实际挂起", waiting)
            assertEquals("切集不能临时释放方向锁", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            assertEquals(if (version) "sh6" else "sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            rule.onNodeWithContentDescription("返回").performClick()
            advance(rule, 300)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 500)
            assertEquals("离页后迟到尺寸不能重新锁定", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        } finally { release.complete(Unit) }
    }

    @Test fun 旧目标尺寸迟到不能覆盖新集方向() = verifyLateOldDirection(exitFirst = false)
    @Test fun 旧目标尺寸在离页后迟到不能重锁() = verifyLateOldDirection(exitFirst = true)

    private fun verifyLateOldDirection(exitFirst: Boolean) {
        val release = CompletableDeferred<Unit>()
        var waiting = false
        val core = episodeCore()
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "emby.itemMedia") return core.callJson(command, args, onPartial)
                val old = args.str("item_id") == "sh6"
                if (old) {
                    waiting = true
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                return arr(buildJsonObject {
                    put("id", "ea"); put("streams", arr(buildJsonObject {
                        put("type_", "Video"); put("width", if (old) 1080 else 1920); put("height", if (old) 1920 else 1080)
                    }))
                })
            }
        }
        try {
            showPlayer(port, "mpv", ar = 0f)
            assertTrue(waiting)
            rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel("episodes") }
            advance(rule, 300)
            rule.onNode(hasText("S1E7", substring = true)).performClick()
            advance(rule, 500)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            if (exitFirst) {
                rule.onNodeWithContentDescription("返回").performClick(); advance(rule, 300)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            }
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 300)
            assertEquals("旧集竖屏尺寸不能覆盖当前方向", if (exitFirst) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        } finally { release.complete(Unit) }
    }

    @Test fun 手动方向不被迟到媒体尺寸覆盖() {
        val release = CompletableDeferred<Unit>()
        val core = episodeCore()
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "emby.itemMedia") release.await()
                return result
            }
        }
        try {
            showPlayer(port, "mpv", ar = 0f)
            rule.onNodeWithContentDescription("切换方向").performClick(); advance(rule, 300)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 300)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
        } finally { release.complete(Unit) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun 竖屏片源按实际媒体尺寸全屏显示且保留切换入口MPV() = verifyPortraitSource("mpv")

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun 竖屏片源按实际媒体尺寸全屏显示且保留切换入口Media3() = verifyPortraitSource("exo")

    private fun verifyPortraitSource(mode: String) {
        val core = portraitCore()
        showPlayer(core, mode, ar = 0f, dark = true)
        if (mode == "exo") {
            rule.runOnIdle { currentExo().playWhenReady = true }
            exoState(0); advance(rule, 300)
        }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
        val video = rule.onNodeWithTag("player.video").fetchSemanticsNode().boundsInRoot
        val page = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("竖屏片源不能缩进顶部16:9窗口", page.height, video.height, .1f)
        assertEquals(page.width, video.width, .1f)
        rule.onNodeWithTag("player.current").assertDoesNotExist()
        for (label in listOf("返回", "比例", "切换方向", "章节", "更多", "暂停", "后退 10 秒", "前进 10 秒",
            "上一集", "下一集", "锁屏", "加速", "减速", "音轨", "字幕")) rule.onNodeWithContentDescription(label).assertIsDisplayed()
        val pause = rule.onNodeWithContentDescription("暂停").fetchSemanticsNode().boundsInRoot
        assertEquals(video.center.y, pause.center.y, .1f)
        val previous = rule.onNodeWithContentDescription("上一集").fetchSemanticsNode().boundsInRoot
        val next = rule.onNodeWithContentDescription("下一集").fetchSemanticsNode().boundsInRoot
        val title = rule.onNode(hasText("S1E6", substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue(previous.right <= next.left && next.right <= title.left)
        val lock = rule.onNodeWithContentDescription("锁屏").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("锁屏").performClick(); advance(rule, 300)
        val unlock = rule.onNodeWithContentDescription("解锁").fetchSemanticsNode().boundsInRoot
        assertEquals(lock.left, unlock.left, 1f); assertEquals(lock.top, unlock.top, 1f)
        rule.onNodeWithContentDescription("解锁").performClick(); advance(rule, 300)
        rule.runOnIdle {
            portraitVideoBackground(360, 640)
        }
        if (mode == "mpv") {
            rule.runOnIdle { core.tick(2172.25) }; advance(rule, 300)
            rule.runOnIdle { core.tick(2172.75) }; advance(rule, 300)
        } else {
            exoState(2_172_250); advance(rule, 300)
            exoState(2_172_750); advance(rule, 300)
        }
        if (mode == "mpv") rule.onRoot().captureRoboImage("build/player-osd/portrait-source-portrait.png")
        else assertPortraitSurface(360, 640)
        val host = activity
        rule.onNodeWithContentDescription("切换方向").performClick()
        rotateViewport(640, 360)
        assertSame(host, activity)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        val landscapeVideo = rule.onNodeWithTag("player.video").fetchSemanticsNode().boundsInRoot
        assertEquals(640f, landscapeVideo.width, .1f); assertEquals(360f, landscapeVideo.height, .1f)
        rule.onNodeWithContentDescription("切换方向").assertIsDisplayed()
        rule.runOnIdle { portraitVideoBackground(640, 360) }
        if (mode == "mpv") rule.onRoot().captureRoboImage("build/player-osd/portrait-source-landscape.png")
        else assertPortraitSurface(203, 360)
        rule.onNodeWithContentDescription("切换方向").performClick()
        rotateViewport(360, 640)
        assertSame(host, activity)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
        assertEquals(video, rule.onNodeWithTag("player.video").fetchSemanticsNode().boundsInRoot)
        if (mode == "exo") assertPortraitSurface(360, 640)
        rule.onNodeWithContentDescription("切换方向").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals(0, core.calls.count { it.first == "player.stopPlayback" })
    }

    private fun assertPortraitSurface(width: Int, height: Int) {
        fun surface(view: android.view.View): android.view.SurfaceView? {
            if (view is android.view.SurfaceView) return view
            val group = view as? android.view.ViewGroup ?: return null
            return (0 until group.childCount).firstNotNullOfOrNull { surface(group.getChildAt(it)) }
        }
        rule.runOnIdle {
            val view = surface(activity.window.decorView)!!
            assertEquals("实际Surface宽度", width, view.width)
            assertEquals("实际Surface高度", height, view.height)
        }
    }

    /** 合成窗口背景用于核对原始比例和控件对比，不能作为实际视频解码证据。 */
    private fun portraitVideoBackground(width: Int, height: Int) {
        val frame = xyz.linplayer.app.ui.player.videoRect(width, height, 9f / 16, xyz.linplayer.app.ui.player.VideoFit.Source)
        val picture = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF455F48.toInt(), 0xFF244C62.toInt()))
        val background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK), picture))
        background.setLayerSize(1, frame.width, frame.height)
        background.setLayerGravity(1, android.view.Gravity.CENTER)
        activity.window.setBackgroundDrawable(background)
    }

    private fun portraitCore() = episodeCore().apply {
        ret("emby.itemMedia", arr(buildJsonObject {
            put("id", "ea")
            put("streams", arr(buildJsonObject {
                put("type_", "Video"); put("width", 1080); put("height", 1920)
            }))
        }))
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun 竖屏片源窄屏大字接下来及面板不遮挡控制() {
        val core = portraitCore()
        showPlayer(core, "mpv", ar = 0f, fontScale = 1.3f, dark = false)
        rule.runOnIdle { core.tick(3634.5) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3635.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertIsDisplayed()
        val targets = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress)).fetchSemanticsNodes()
        targets.forEachIndexed { i, a -> targets.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("竖屏全屏片尾卡片与控制命中不能重叠：${a.config} / ${b.config}", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onRoot().captureRoboImage("build/player-osd/portrait-source-next-up-compact.png")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("弹幕").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("截屏").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("字幕").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        rule.onNodeWithTag("player.menu").assertIsDisplayed()
        rule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        rule.onNodeWithContentDescription("切换方向").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun 竖屏片源完播错误仍可重试和返回() {
        val core = portraitCore().apply {
            on("emby.seasonEpisodes") {
                throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分集读取失败，请检查网络连接与服务器状态。".repeat(8), true)
            }
        }
        showPlayer(core, "mpv", ar = 0f, fontScale = 1.3f)
        rule.runOnIdle { core.tick(3658.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3658.25) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3660.0, eof = true) }; advance(rule, 600)
        rule.onNodeWithText("重试").assertIsDisplayed()
        rule.onNodeWithText("返回").assertIsDisplayed().performClick(); advance(rule, 300)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    /** 在同一宿主派发配置并重测窗口；只证明配置变化后的布局，不等同设备转屏。 */
    private fun rotateViewport(width: Int, height: Int) {
        rule.runOnIdle {
            // Compose测试宿主沿用生产MainActivity的配置处理声明，保持同一组合。
            val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
            info.configChanges = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_NAVIGATION
            Shadows.shadowOf(activity.packageManager).addOrUpdateActivity(info)
            org.robolectric.RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-mdpi")
            val config = android.content.res.Configuration(activity.resources.configuration)
            activity.onConfigurationChanged(config)
            val decor = activity.window.decorView
            decor.dispatchConfigurationChanged(config)
            decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
        }
        advance(rule, 300)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi", sdk = [36], application = Application::class)
    fun 竖屏方向按钮可请求横屏且不重启播放() {
        val core = episodeCore()
        showPlayer(core, "mpv", ar = 9f / 16)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
        rule.onNodeWithContentDescription("切换方向").performClick()
        advance(rule, 1300)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals(0, core.calls.count { it.first == "player.stopPlayback" })
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi", sdk = [36], application = Application::class)
    fun 横屏片源在竖屏小窗保持十六比九并可通过分集卡切集() {
        val core = episodeCore()
        showPlayer(core, "mpv", ar = 16f / 9, fontScale = 1.3f)
        val video = rule.onNodeWithTag("player.video").fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9, video.width / video.height, .01f)
        val waiting = rule.onNode(hasContentDescription("正在打开…") or hasContentDescription("正在缓冲…"))
            .fetchSemanticsNode().boundsInRoot
        assertEquals("等待动画应居中于视频，不能算入视频下方的进度行", video.center.y, waiting.center.y, 1f)
        rule.onNodeWithTag("detail.ep.sh6").assertIsDisplayed()
        rule.onNodeWithText("当前集").assertIsDisplayed()
        rule.onNodeWithText("正在缓冲…").assertDoesNotExist()
        rule.onNodeWithText("正在打开…").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/player-osd/portrait-page.png")
        rule.onNodeWithTag("detail.ep.sh7").performClick()
        advance(rule, 400)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "player.play" })
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-mdpi", sdk = [36], application = Application::class)
    fun 竖屏正常播放展示当前集面板并保留折叠滚动位置() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = episodeCore().apply {
            on("emby.itemDetail") { a -> buildJsonObject {
                val id = a.str("item_id")!!
                put("id", id); put("name", "妾之王国"); put("series_name", "幕府将军"); put("type_", "Episode")
                put("season_id", "sh"); put("series_id", "s2"); put("season_no", 1)
                put("episode_no", id.removePrefix("sh").toInt())
            } }
        }
        showPlayer(core, "mpv", ar = 16f / 9, dark = true)
        rule.runOnIdle {
            activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            core.tick(2172.25)
        }; advance(rule, 300)
        rule.runOnIdle { core.tick(2172.75) }; advance(rule, 300)
        rule.onNode(hasContentDescription("正在打开…") or hasContentDescription("正在缓冲…")).assertDoesNotExist()
        val header = rule.onNodeWithTag("player.current").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val thumb = rule.onNodeWithContentDescription("当前集缩略图").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(thumb.width * 9f / 16f, thumb.height, 1f)
        assertTrue(thumb.left >= header.left && thumb.bottom <= header.bottom)
        rule.onNodeWithText("分集 10").assertIsDisplayed()
        val card = rule.onNodeWithTag("detail.ep.sh6").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("播放器分集采用参考卡宽", 190f, card.width, .1f)
        rule.onRoot().captureRoboImage("build/player-osd/portrait-page-normal.png")
        val lock = rule.onNodeWithContentDescription("锁屏").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("锁屏").performClick(); advance(rule, 300)
        val unlock = rule.onNodeWithContentDescription("解锁").fetchSemanticsNode().boundsInRoot
        assertEquals(lock.left, unlock.left, 1f)
        assertEquals(lock.top, unlock.top, 1f)
        rule.onNodeWithContentDescription("解锁").performClick(); advance(rule, 300)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
        rule.onNodeWithTag("detail.ep.sh8").assertIsDisplayed()
        val before = rule.onNodeWithTag("detail.ep.sh8").fetchSemanticsNode().boundsInRoot.left
        val requests = core.calls.count { it.first == "emby.seasonEpisodes" }
        rule.onNodeWithContentDescription("收起分集").performClick(); advance(rule, 300)
        rule.onNodeWithTag("detail.ep.sh8").assertIsNotDisplayed()
        rule.onNodeWithTag("player.current").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/portrait-page-collapsed.png")
        rule.onNodeWithContentDescription("展开分集").performClick(); advance(rule, 300)
        assertEquals(before, rule.onNodeWithTag("detail.ep.sh8").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(requests, core.calls.count { it.first == "emby.seasonEpisodes" })
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi", sdk = [36], application = Application::class)
    fun 窄屏大字竖屏面板可恢复且方向仍可用() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val core = episodeCore()
        showPlayer(core, "mpv", ar = 16f / 9, fontScale = 1.3f, dark = false)
        rule.runOnIdle {
            activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            core.tick(2172.25)
        }; advance(rule, 300)
        rule.runOnIdle { core.tick(2172.75) }; advance(rule, 300)
        rule.onNodeWithTag("player.current").assertIsDisplayed()
        rule.onNodeWithTag("detail.ep.sh6").assertIsDisplayed()
        for (label in listOf("上一集", "下一集", "锁屏", "倍速", "音轨", "字幕", "切换方向"))
            rule.onNodeWithContentDescription(label).assertIsDisplayed()
        val targets = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress)).fetchSemanticsNodes()
        targets.forEachIndexed { i, a -> targets.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("小屏大字整页命中不能重叠", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onRoot().captureRoboImage("build/player-osd/portrait-page-compact-normal.png")
        rule.onNodeWithContentDescription("收起分集").performClick(); advance(rule, 300)
        rule.onNodeWithTag("detail.ep.sh6").assertIsNotDisplayed()
        rule.onNodeWithContentDescription("展开分集").assertIsDisplayed().performClick(); advance(rule, 300)
        rule.onNodeWithTag("detail.ep.sh6").assertIsDisplayed()
        rule.onNodeWithContentDescription("切换方向").performClick(); advance(rule, 1300)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
    fun 小屏大字接下来卡片不遮挡控制和进度() {
        val core = episodeCore()
        showPlayer(core, "mpv", fontScale = 1.3f)
        rule.runOnIdle { core.tick(3634.5) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3635.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertIsDisplayed()
        val targets = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress)).fetchSemanticsNodes()
        targets.forEachIndexed { i, a -> targets.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("卡片与控制命中区不能重叠：${a.config} / ${b.config}", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onRoot().captureRoboImage("build/player-osd/next-up-compact.png")
        rule.onNodeWithContentDescription("字幕").performClick(); advance(rule, 300)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        rule.onNodeWithTag("player.menu").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi", sdk = [36], application = Application::class)
    fun 竖屏长错误仍可重试和请求退出() {
        val core = episodeCore().apply {
            on("emby.seasonEpisodes") {
                throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分集读取失败，请检查网络连接与服务器状态。".repeat(8), true)
            }
        }
        showPlayer(core, "mpv", ar = 16f / 9, fontScale = 1.3f)
        rule.runOnIdle { core.tick(3658.0) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3658.25) }; advance(rule, 300)
        rule.runOnIdle { core.tick(3660.0, eof = true) }; advance(rule, 600)
        rule.onNodeWithText("重试").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/portrait-error.png")
        rule.onNodeWithText("返回").assertIsDisplayed().performClick()
        advance(rule, 300)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test fun 季末没有下一集不显示接下来() {
        val core = episodeCore().apply {
            ret("emby.seasonEpisodes", buildJsonObject { put("items", arr(item("sh6", "最后一集", "Episode"))); put("total", 1) })
        }
        showPlayer(core, "mpv")
        rule.runOnIdle { core.tick(3635.0) }; advance(rule, 300)
        rule.onNodeWithText("接下来").assertDoesNotExist()
        rule.onNodeWithContentDescription("下一集").assertIsNotEnabled()
    }

    @Test fun 分集未就绪和读取失败不显示接下来() {
        val release = CompletableDeferred<Unit>()
        val core = episodeCore()
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.seasonEpisodes") {
                    release.await()
                    throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分集读取失败", true)
                }
                return core.callJson(command, args, onPartial)
            }
        }
        try {
            showPlayer(port, "mpv")
            rule.runOnIdle { core.tick(3635.0) }; advance(rule, 300)
            rule.onNodeWithText("接下来").assertDoesNotExist()
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 300)
            rule.onNodeWithText("接下来").assertDoesNotExist()
            assertEquals(1, core.calls.count { it.first == "player.play" })
        } finally { release.complete(Unit) }
    }

    @Test fun Media3接下来及上一集均复用当前内核和停止屏障() {
        val core = episodeCore()
        showPlayer(core, "exo")
        exoState(3_635_000); advance(rule, 300)
        rule.onNodeWithText("接下来").assertIsDisplayed().performClick()
        advance(rule, 500)
        exoState(0); advance(rule, 300)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        rule.onNodeWithText("接下来").assertDoesNotExist()
        rule.onNodeWithContentDescription("上一集").performClick(); advance(rule, 500)
        assertEquals("sh6", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(listOf("player.play", "player.stopPlayback", "player.play", "player.stopPlayback", "player.play"),
            core.calls.map { it.first }.filter { it in listOf("player.play", "player.stopPlayback") })
        assertTrue(core.calls.filter { it.first == "player.play" }.all { it.second.str("engine") == "exo" })
    }

    @Test fun MPV章节入口读取服务端时间并执行真实跳转() {
        val core = episodeCore().apply { ret("player.seek", JsonNull) }
        showPlayer(core, "mpv")
        rule.onNodeWithContentDescription("章节").performClick(); advance(rule, 300)
        assertEquals("sh6", core.calls.last { it.first == "player.chapterInfo" }.second.str("item_id"))
        rule.onNodeWithText("章节 18:50").performClick(); advance(rule, 300)
        assertEquals(1130.0, core.calls.last { it.first == "player.seek" }.second.dbl("pos")!!, 0.0)
    }

    @Test fun 选集连续点击只提交一次在途切换() {
        val release = CompletableDeferred<Unit>()
        val core = episodeCore()
        var stops = 0
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.stopPlayback") { stops++; release.await() }
                return core.callJson(command, args, onPartial)
            }
        }
        try {
            showPlayer(port, "mpv")
            rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel("episodes") }
            advance(rule, 300)
            val first = rule.onNode(hasText("S1E7", substring = true)).fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            val second = rule.onNode(hasText("S1E8", substring = true)).fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            rule.runOnIdle { first(); second() }
            advance(rule, 300)
            assertEquals("在途停止只能提交一次", 1, stops)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 500)
            assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
            assertEquals(2, core.calls.count { it.first == "player.play" })
        } finally { release.complete(Unit) }
    }

    private fun exoState(position: Long, ended: Boolean = false) = rule.runOnIdle {
        val info = ReflectionHelpers.getField<Any>(currentExo(), "playbackInfo")
        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(3_660_000_000L, true, false, false, null,
            androidx.media3.common.MediaItem.fromUri("asset:///episode-test.mp4"))
        val period = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
        ReflectionHelpers.setField(info, "timeline", timeline)
        ReflectionHelpers.setField(info, "periodId", period)
        ReflectionHelpers.setField(info, "loadingMediaPeriodId", period)
        ReflectionHelpers.setField(info, "positionUs", position * 1000)
        ReflectionHelpers.setField(info, "bufferedPositionUs", position * 1000)
        ReflectionHelpers.setField(info, "playbackState", if (ended) androidx.media3.common.Player.STATE_ENDED else androidx.media3.common.Player.STATE_READY)
    }

    @Test fun 手机生产起播接入阶段日志且MPV首帧明确未知() {
        org.robolectric.shadows.ShadowLog.clear()
        val core = FakeCore().loggedIn().player()
        showPlayer(core, "mpv")
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").any { it.msg.startsWith("phase=startup_request_complete") }
        }
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").map { it.msg }.filter { it.startsWith("phase=startup_") }
        assertEquals(2, logs.size)
        assertTrue(logs.last().contains("engine=mpv origin=page"))
        assertTrue(logs.last().contains("first_frame_supported=false"))
        assertTrue(logs.none { "sh6" in it || "requested-version" in it })
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 手机Media3生产取流后提交加载并区分两个阶段() {
        org.robolectric.shadows.ShadowLog.clear()
        val core = FakeCore().loggedIn().player().apply {
            on("player.play") { buildJsonObject {
                put("media_source_id", "ea")
                put("play_url", "asset:///startup-measurement.mp4")
                put("resume_secs", 0.0)
            } }
        }
        showPlayer(core, "exo")
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").any { it.msg.startsWith("phase=startup_load") }
        }
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").map { it.msg }.filter { it.startsWith("phase=startup_") }
        assertEquals(1, logs.count { it.startsWith("phase=startup_request ") })
        assertTrue(logs.single { it.startsWith("phase=startup_request_complete") }.contains("request_ms="))
        assertTrue(logs.single { it.startsWith("phase=startup_load") }.contains("address_to_load_ms="))
        assertTrue(logs.none { "startup-measurement" in it || "asset:" in it })
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 手机MPV缓冲绝对末端传到滑杆且控件隐藏停止查询() {
        val core = FakeCore().loggedIn().player().apply {
            ret("player.status", buildJsonObject { put("buffered", 120.0) })
        }
        showPlayer(core, "mpv")
        rule.runOnIdle { core.tick(40.0); core.tick(40.25) }
        advance(rule, 1200)
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("已缓冲至 2:00"))
        core.ret("player.status", buildJsonObject { })
        advance(rule, 1200)
        assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("缓冲进度未知"))
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.setOsdVisible(false) }
        advance(rule, 400)
        val queries = core.calls.count { it.first == "player.status" }
        advance(rule, 2200)
        assertEquals(queries, core.calls.count { it.first == "player.status" })
        val quiet = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        quiet.assertIsDisplayed()
        quiet.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
        assertEquals((40.25 / 3660).toFloat(), quiet.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .00001f)
    }

    @Test fun 手机Media3缓冲来自播放器且不查核心缓冲状态() {
        val core = FakeCore().loggedIn().player()
        showPlayer(core, "exo")
        rule.runOnIdle {
            val player = currentExo()
            val info = ReflectionHelpers.getField<Any>(player, "playbackInfo")
            val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(3_660_000_000L, true, false, false, null,
                androidx.media3.common.MediaItem.fromUri("asset:///buffer-test.mp4"))
            val period = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
            ReflectionHelpers.setField(info, "timeline", timeline)
            ReflectionHelpers.setField(info, "periodId", period)
            ReflectionHelpers.setField(info, "loadingMediaPeriodId", period)
            ReflectionHelpers.setField(info, "positionUs", 40_000_000L)
            ReflectionHelpers.setField(info, "bufferedPositionUs", 120_000_000L)
        }
        advance(rule, 600)
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("已缓冲至 2:00"))
        assertTrue(core.calls.none { it.first == "player.status" })
    }

    @Test fun 手机后台停止缓冲查询恢复前台重新采样() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply {
                currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            }
        }
        val core = FakeCore().loggedIn().player()
        showPlayer(core, "mpv", owner)
        // 暂停保持控件可见，让本测试只验证生命周期而非五秒自动收起。
        rule.runOnIdle {
            core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status", buildJsonObject {
                put("position", 40.0); put("duration", 3660.0); put("paused", true); put("buffering", false)
            }))
        }
        advance(rule, 1200)
        rule.waitForIdle()
        assertTrue(core.calls.any { it.first == "player.status" })
        rule.runOnIdle { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        advance(rule, 400)
        rule.waitForIdle()
        val queries = core.calls.count { it.first == "player.status" }
        advance(rule, 1600)
        rule.waitForIdle()
        assertEquals(queries, core.calls.count { it.first == "player.status" })
        rule.runOnIdle { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        advance(rule, 400)
        rule.waitForIdle()
        assertTrue("恢复前台应重新查询缓冲", core.calls.count { it.first == "player.status" } > queries)
    }

    @Test fun 控件重开后旧缓冲查询返回不能覆盖新值() = verifyLateBufferResponse(false)

    @Test fun 换版本后旧缓冲查询返回不能覆盖新值() = verifyLateBufferResponse(true)

    private fun verifyLateBufferResponse(switchVersion: Boolean) {
        val release = CompletableDeferred<Unit>()
        var first = true
        var waiting = false
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            ret("player.status", buildJsonObject { put("buffered", 120.0) })
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "player.status" && first) {
                    first = false; waiting = true
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                return result
            }
        }
        try {
            showPlayer(port, "mpv")
            rule.runOnIdle { core.tick(40.0); core.tick(40.25) }
            advance(rule, 600)
            assertTrue(waiting)
            core.ret("player.status", buildJsonObject { put("buffered", 180.0) })
            if (switchVersion) {
                rule.onNodeWithContentDescription("更多").performClick()
                advance(rule, 300)
                rule.onNodeWithText("版本与线路").performClick()
                advance(rule, 300)
                rule.onNodeWithText("1080p").performClick()
                advance(rule, 400)
                assertEquals(2, core.calls.count { it.first == "player.play" })
                rule.runOnIdle { core.tick(40.0); core.tick(40.25) }
                advance(rule, 1200)
            } else {
                rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.setOsdVisible(false) }
                advance(rule, 400)
                rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.setOsdVisible(true) }
                advance(rule, 600)
            }
            val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("已缓冲至 3:00"))
            rule.runOnIdle { release.complete(Unit) }
            advance(rule, 300)
            assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("已缓冲至 3:00"))
        } finally { release.complete(Unit) }
    }

    @Test fun 手机连按快进累加且旧时钟不回弹() {
        org.robolectric.shadows.ShadowLog.clear()
        val core = FakeCore().loggedIn().player().apply { ret("player.seek", JsonNull) }
        showPlayer(core, "mpv")
        rule.runOnIdle { core.tick(40.0); core.tick(40.25) }
        advance(rule, 400)
        repeat(3) { rule.onNodeWithContentDescription("前进 10 秒").performClick() }
        assertEquals(listOf(50.25, 60.25, 70.25), core.calls.filter { it.first == "player.seek" }.map { it.second.dbl("pos") })
        rule.runOnIdle { core.tick(40.5) }
        advance(rule, 300)
        rule.onNodeWithText("1:10").assertIsDisplayed()
        rule.runOnIdle { core.tick(70.5) }
        advance(rule, 300)
        rule.runOnIdle { core.tick(70.75) }
        advance(rule, 300)
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").any { it.msg.startsWith("phase=seek_clock_advanced") }
        }
        val seekLogs = org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").map { it.msg }.filter { it.startsWith("phase=seek_") }
        assertEquals(3, seekLogs.count { it.startsWith("phase=seek_request ") })
        assertEquals(1, seekLogs.count { it.startsWith("phase=seek_target_observed") })
        assertEquals(1, seekLogs.count { it.startsWith("phase=seek_clock_advanced") })
        assertTrue(seekLogs.none { "sh6" in it || "requested-version" in it })
        rule.onNodeWithContentDescription("后退 10 秒").performClick()
        assertEquals(60.75, core.calls.last { it.first == "player.seek" }.second.dbl("pos")!!, .001)
    }

    @Test fun Media3换版本和选集继续通过页面起播并等待旧会话停止() {
        org.robolectric.shadows.ShadowLog.clear()
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            on("player.play") { arguments -> buildJsonObject {
                put("media_source_id", arguments.str("media_source_id") ?: "ea")
                put("resume_secs", 0.0)
            } }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Player("sh6", "测试剧集", engine = "exo", ar = 16f / 9)) {
                        composable<Route.Player> { PlayerPage(nav, it) }
                    }
                }
            }
        }
        advance(rule, 800)
        assertEquals("初次起播请求", 1, core.calls.count { it.first == "player.play" })
        val exo = ReflectionHelpers.getStaticField<ExoPlayer>(PlaybackService::class.java, "externalPlayer")
        playbackService = Robolectric.buildService(PlaybackService::class.java).create()
        val focus = ReflectionHelpers.getField<AudioManager.OnAudioFocusChangeListener>(playbackService!!.get(), "focusListener")
        rule.runOnIdle { focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) }
        assertEquals(.3f, exo.volume, .001f)
        rule.onNodeWithContentDescription("更多").performClick()
        advance(rule, 300)
        assertEquals("打开更多后的请求", 1, core.calls.count { it.first == "player.play" })
        rule.onNodeWithText("版本与线路").performClick()
        advance(rule, 300)
        assertEquals("打开版本面板后的请求", 1, core.calls.count { it.first == "player.play" })
        assertNull(Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStoppedService)
        rule.onNodeWithText("1080p").performClick()
        advance(rule, 500)
        assertEquals(1f, exo.volume, .001f)
        assertEquals(PlaybackService::class.java.name,
            Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStoppedService?.component?.className)
        val plays = core.calls.filter { it.first == "player.play" }
        assertEquals(plays.map { listOf(it.second.str("item_id"), it.second.str("engine"), it.second.str("media_source_id")) }.toString(), 2, plays.size)
        assertEquals("exo", plays.last().second.str("engine"))
        assertEquals("eb", plays.last().second.str("media_source_id"))
        assertEquals(0.0, plays.last().second.dbl("resume_secs")!!, 0.0)
        assertTrue(plays.last().second.bool("from_start"))
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        val stop = core.calls.indexOfFirst { it.first == "player.stopPlayback" }
        val next = core.calls.indexOfLast { it.first == "player.play" }
        assertTrue(stop in 0 until next)
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel("episodes") }
        advance(rule, 300)
        assertNull(Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStoppedService)
        rule.onNode(hasText("S1E7", substring = true)).performClick()
        advance(rule, 500)
        assertEquals(PlaybackService::class.java.name,
            Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStoppedService?.component?.className)
        val episode = core.calls.last { it.first == "player.play" }.second
        assertEquals(3, core.calls.count { it.first == "player.play" })
        assertEquals(2, core.calls.count { it.first == "player.stopPlayback" })
        assertEquals("sh7", episode.str("item_id"))
        assertEquals("exo", episode.str("engine"))
        assertFalse(episode!!.containsKey("media_source_id"))
        assertFalse(episode.containsKey("resume_secs"))
        assertEquals(listOf("player.play", "player.stopPlayback", "player.play", "player.stopPlayback", "player.play"),
            core.calls.map { it.first }.filter { it == "player.play" || it == "player.stopPlayback" })
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").count { it.msg.startsWith("phase=startup_request_complete") } == 3
        }
        val startupLogs = org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").map { it.msg }
            .filter { it.startsWith("phase=startup_request_complete") }
        assertEquals(2, startupLogs.count { "origin=target" in it })
        assertEquals(3, startupLogs.map { it.substringAfter("attempt=").substringBefore(" ") }.distinct().size)
    }

    @Test fun 自动回退等待停止和慢起播后仍恢复播放状态及晚到音轨() {
        org.robolectric.shadows.ShadowLog.clear()
        val stopRelease = CompletableDeferred<Unit>()
        val playRelease = CompletableDeferred<Unit>()
        val core = FakeCore().loggedIn().player().apply {
            ret("player.stopPlayback", JsonNull)
            ret("player.setSpeed", JsonNull)
            ret("player.setVolume", JsonNull)
            ret("player.setPause", JsonNull)
            ret("player.setTrack", JsonNull)
            ret("player.setAspectRatio", JsonNull)
            ret("prefs.getPrefs", buildJsonObject { put("sub_enabled", false) })
            on("player.play") { buildJsonObject {
                put("media_source_id", "resolved-version")
                put("resume_secs", 42.5)
            } }
            on("player.tracks") {
                if (!playRelease.isCompleted) arr() else arr(buildJsonObject {
                    put("kind", "audio"); put("id", "mpv-japanese")
                    put("title", "日语"); put("lang", "jpn")
                })
            }
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "player.stopPlayback") stopRelease.await()
                if (command == "player.play" && args.str("engine") == "mpv") playRelease.await()
                return result
            }
        }
        showPlayer(port, "auto")
        lateinit var exo: ExoPlayer
        rule.runOnIdle {
            exo = currentExo()
            exo.seekTo(42_500)
            exo.playWhenReady = false
            exo.setPlaybackSpeed(1.5f)
            exo.volume = 0.4f
            injectError(exo, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, selectedAudio = true)
            assertEquals("日语", exo.currentTracks.groups.single().getTrackFormat(0).label)
        }
        advance(rule, 800)
        assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        assertEquals("旧停止挂起时不启动 MPV", 1, core.calls.count { it.first == "player.play" })
        assertEquals(42.5, core.calls.single { it.first == "player.stopPlayback" }.second.dbl("pos")!!, 0.0)

        rule.runOnIdle { stopRelease.complete(Unit) }
        advance(rule, 800)
        val plays = core.calls.filter { it.first == "player.play" }
        assertEquals(listOf("exo", "mpv"), plays.map { it.second.str("engine") })
        assertEquals("resolved-version", plays.last().second.str("media_source_id"))
        assertEquals(42.5, plays.last().second.dbl("resume_secs")!!, 0.0)
        assertFalse(plays.last().second.bool("from_start"))
        rule.runOnIdle { assertTrue("旧 Media3 已释放", exo.isReleased) }
        assertTrue(core.calls.indexOfFirst { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })

        // 起播等待超过旧轮询窗口；次数必须留给起播成功后的轨道。
        advance(rule, 12_500)
        assertEquals("MPV 尚未起播成功，不读取轨表", 0, core.calls.count { it.first == "player.tracks" })
        assertFalse(core.calls.any { it.first == "player.setSpeed" || it.first == "player.setTrack" })
        rule.runOnIdle { playRelease.complete(Unit) }
        advance(rule, 1_800)
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").count { it.msg.startsWith("phase=startup_request_complete") } == 2
        }
        val attempts = org.robolectric.shadows.ShadowLog.getLogsForTag("lp-playback").map { it.msg }
            .filter { it.startsWith("phase=startup_request_complete") }
        assertTrue(attempts.any { "engine=media3" in it })
        assertTrue(attempts.any { "engine=mpv" in it && "first_frame_supported=false" in it })
        assertEquals(2, attempts.map { it.substringAfter("attempt=").substringBefore(" ") }.distinct().size)
        assertEquals(1.5, core.calls.single { it.first == "player.setSpeed" }.second.dbl("speed")!!, 0.0)
        assertEquals(40.0, core.calls.single { it.first == "player.setVolume" }.second.dbl("volume")!!, 0.0)
        assertTrue(core.calls.single { it.first == "player.setPause" }.second.bool("paused"))
        val picks = core.calls.filter { it.first == "player.setTrack" }
        assertEquals(listOf("", "mpv-japanese"), picks.map { it.second.str("id") })
        assertEquals(listOf("sub", "audio"), picks.map { it.second.str("kind") })
        advance(rule, 2_000)
        assertEquals("音轨只恢复一次", 2, core.calls.count { it.first == "player.setTrack" })
        assertEquals("回退不重复起播", 2, core.calls.count { it.first == "player.play" })
    }

    @Test fun 手机同页换集沿用手选字幕并使用新轨表ID() {
        var item = "sh6"
        val core = FakeCore().loggedIn().player().apply {
            ret("player.setTrack", JsonNull); ret("player.stopPlayback", JsonNull)
            on("player.play") { args -> item = args.str("item_id")!!; buildJsonObject { put("media_source_id", "ea") } }
            on("emby.itemDetail") { args -> buildJsonObject {
                put("id", args.str("item_id")!!); put("type_", "Episode"); put("series_id", "s2"); put("season_id", "sh")
            } }
            on("player.tracks") { arr(
                buildJsonObject { put("id", if (item == "sh6") "1" else "8"); put("kind", "sub"); put("title", "中文字幕"); put("lang", "zho"); put("codec", "subrip"); put("forced", false); put("selected", true) },
                buildJsonObject { put("id", if (item == "sh6") "2" else "9"); put("kind", "sub"); put("title", "英文字幕"); put("lang", "eng"); put("codec", "subrip"); put("forced", false) },
            ) }
        }
        showPlayer(core, "mpv")
        rule.onNodeWithContentDescription("字幕").performClick()
        advance(rule, 300)
        rule.onNodeWithText("英文字幕").performClick()
        advance(rule, 300)
        assertEquals(listOf("2"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
        rule.runOnIdle { xyz.linplayer.app.plugin.PluginPlayer.host!!.openPanel("episodes") }
        advance(rule, 300)
        rule.onNode(hasText("S1E7", substring = true)).performClick()
        advance(rule, 2_000)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(listOf("2", "9"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
        advance(rule, 1_500)
        assertEquals("只恢复一次", 2, core.calls.count { it.first == "player.setTrack" })
    }

    @Test fun 自动模式网络错误显示失败而不回退() {
        assertNoFallback("auto", PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    }

    @Test fun 手动Media3解码错误显示失败而不回退() {
        assertNoFallback("exo", PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
    }

    @Test fun 手机等偏好读取后只起播一次并使用缓冲目标() {
        val release = CompletableDeferred<Unit>()
        val core = FakeCore().loggedIn().player().apply {
            ret("prefs.getPrefs", buildJsonObject { put("buffer_target_bytes", 134217728); put("sub_enabled", true) })
        }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "prefs.getPrefs") release.await()
                return result
            }
        }
        showPlayer(port, "exo")
        assertEquals(0, core.calls.count { it.first == "player.play" })
        rule.runOnIdle { release.complete(Unit) }
        advance(rule, 800)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        rule.runOnIdle { assertEquals(134217728, actualBufferTarget(currentExo())) }
    }

    private fun assertNoFallback(mode: String, errorCode: Int) {
        val core = FakeCore().loggedIn().player()
        showPlayer(core, mode)
        rule.runOnIdle { injectError(currentExo(), errorCode) }
        advance(rule, 1_000)
        rule.onNodeWithText("这一片没能播起来").assertIsDisplayed()
        assertEquals(listOf("exo"), core.calls.filter { it.first == "player.play" }.map { it.second.str("engine") })
        assertFalse(core.calls.any { it.first == "player.stopPlayback" })
    }

    private fun showPlayer(core: CorePort, mode: String, owner: androidx.lifecycle.LifecycleOwner? = null, ar: Float = 16f / 9, fontScale: Float = 1f, dark: Boolean? = null) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalApp provides app,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) {
                    val context = LocalContext.current
                    SideEffect { activity = context as Activity }
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Player("sh6", "测试剧集", versionId = "requested-version", engine = mode, ar = ar)) {
                        composable<Route.Player> { entry ->
                            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides
                                (owner ?: androidx.lifecycle.compose.LocalLifecycleOwner.current)) {
                                PlayerPage(nav, entry)
                            }
                        }
                    }
                }
            }
        }
        advance(rule, 800)
    }

    private fun currentExo(): ExoPlayer = ReflectionHelpers.getStaticField(PlaybackService::class.java, "externalPlayer")

    /** 注入安装版本的 Media3 状态，仍由真实页面读取错误并执行整条回退链路。 */
    private fun injectError(player: ExoPlayer, errorCode: Int, selectedAudio: Boolean = false) {
        val info: Any = ReflectionHelpers.getField(player, "playbackInfo")
        if (selectedAudio) {
            val selector: Any = ReflectionHelpers.getField(info, "trackSelectorResult")
            val group = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLabel("日语").setLanguage("ja").build())
            ReflectionHelpers.setField(selector, "tracks", Tracks(listOf(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))))
        }
        val error = if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            ExoPlaybackException.createForSource(java.io.IOException("网络连接失败"), errorCode)
        else ExoPlaybackException.createForRenderer(IllegalStateException("解码初始化失败"), "测试解码器", 0, null, C.FORMAT_HANDLED, false, errorCode)
        ReflectionHelpers.setField(info, "playbackError", error)
        assertEquals(errorCode, player.playerError?.errorCode)
    }
}
