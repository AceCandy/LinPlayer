package xyz.linplayer.app

import android.app.Application
import android.media.AudioManager
import androidx.compose.runtime.CompositionLocalProvider
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

    @After fun cleanup() {
        playbackService?.destroy()
        PlaybackService.stop(ApplicationProvider.getApplicationContext())
        scope.cancel()
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

    private fun showPlayer(core: CorePort, mode: String, owner: androidx.lifecycle.LifecycleOwner? = null) {
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Player("sh6", "测试剧集", versionId = "requested-version", engine = mode, ar = 16f / 9)) {
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
