package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.str

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvTrackTimingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun cleanup() { scope.cancel() }

    @Test fun 字幕手选与关闭经页面控制器串行提交() {
        val release = CompletableDeferred<Unit>()
        val core = FakeCore().loggedIn().player().apply { ret("player.setTrack", JsonNull) }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "player.setTrack" && args.str("id") == "1") release.await()
                return result
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val nav = TvNav().apply { push(TvRoute.Player("sh6", "测试剧集", engine = "mpv")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 2_500)
        try {
            rule.onNodeWithText("字幕").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; press(rule, Key.Enter)
            advance(rule, 600)
            rule.onNodeWithText("简体中文(内封)").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; press(rule, Key.Enter)
            advance(rule, 200)
            rule.onNodeWithText("字幕").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; press(rule, Key.Enter)
            advance(rule, 200)
            rule.onNodeWithText("关闭字幕").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; press(rule, Key.Enter)
            advance(rule, 200)
            assertEquals(listOf("1"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
        } finally { rule.runOnIdle { release.complete(Unit) } }
        advance(rule, 500)
        assertEquals(listOf("1", ""), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
    }

    @Test fun 电视遥控换集只恢复新轨表中的手选字幕() {
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
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val nav = TvNav().apply { push(TvRoute.Player("sh6", "测试剧集", engine = "mpv")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 2_500)
        rule.onNodeWithText("字幕").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; press(rule, Key.Enter)
        advance(rule, 400)
        rule.onNodeWithText("英文字幕(内封)").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; press(rule, Key.Enter)
        advance(rule, 300)
        assertEquals(listOf("2"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
        press(rule, Key.MediaNext)
        advance(rule, 3_000)
        assertEquals("sh7", core.calls.last { it.first == "player.play" }.second.str("item_id"))
        assertEquals(listOf("2", "9"), core.calls.filter { it.first == "player.setTrack" }.map { it.second.str("id") })
    }

    @Test fun 慢起播不耗尽轨表窗口且起播后应用详情选轨一次() {
        val release = CompletableDeferred<Unit>()
        val core = FakeCore().loggedIn().player().apply { ret("player.setTrack", JsonNull) }
        val port = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "player.play") release.await()
                return result
            }
        }
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val nav = TvNav().apply {
            push(TvRoute.Player("sh6", "测试剧集", engine = "mpv", audioIndex = 1, subIndex = 3))
        }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 13_000)
        assertEquals(1, core.calls.count { it.first == "player.play" })
        assertEquals("起播挂起时不读取旧轨表", 0, core.calls.count { it.first == "player.tracks" })
        assertEquals("起播挂起时不应用旧轨表", 0, core.calls.count { it.first == "player.setTrack" })
        rule.runOnIdle { release.complete(Unit) }
        advance(rule, 2_500)
        val picks = core.calls.filter { it.first == "player.setTrack" }
        assertEquals(listOf("audio", "sub"), picks.map { it.second.str("kind") })
        assertEquals(listOf("1", "2"), picks.map { it.second.str("id") })
        advance(rule, 2_000)
        assertEquals("详情选轨不重复应用", 2, core.calls.count { it.first == "player.setTrack" })
    }
    @Test fun 电视偏好未就绪不提前起播且完成后只起一次() {
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
        val app = AppState(port, scope)
        runBlocking { app.boot() }
        val nav = TvNav().apply { push(TvRoute.Player("sh6", "测试剧集", engine = "exo")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 2_500)
        assertEquals(0, core.calls.count { it.first == "player.play" })
        rule.runOnIdle { release.complete(Unit) }
        advance(rule, 2_500)
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

}
