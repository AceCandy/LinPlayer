package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
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
import xyz.linplayer.app.data.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvPhoneParityTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    @After fun clean() { scope.cancel(); if (::app.isInitialized) app.bg.cancel() }

    private fun core() = FakeCore().loggedIn().player().apply {
        ret("prefs.getPrefs", buildJsonObject { put("default_speed", 1.5); put("sub_enabled", true) })
        on("player.play") { buildJsonObject {
            put("media_source_id", "ea"); put("play_url", "asset:///startup-measurement.mp4"); put("resume_secs", 0.0)
        } }
        for (command in listOf("player.setSpeed", "player.setVolume", "player.setPause", "player.setTrack",
            "player.setAspectRatio", "player.stopPlayback", "player.setPlaybackPrefs")) ret(command, JsonNull)
    }

    private fun show(core: FakeCore, engine: String) {
        app = AppState(core, scope)
        runBlocking { app.boot() }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val nav = TvNav().apply { push(TvRoute.Player("sh6", "测试剧集", engine = engine)) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 1500)
    }

    private fun click(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.Enter)
        advance(rule, 400)
    }

    private fun switchEngine() {
        click("更多")
        click("换内核重播")
        advance(rule, 1000)
    }

    @Test fun 手动双向切核保留暂停倍率和精确续播() {
        val core = core().apply {
            ret("player.status", buildJsonObject {
                put("position", 42.5); put("duration", 3660); put("paused", true); put("speed", 1.75); put("volume", 40)
            })
        }
        show(core, "mpv")
        switchEngine()
        val second = core.calls.last { it.first == "player.play" }.second
        assertEquals("exo", second.str("engine"))
        assertTrue(second.bool("resume_exact"))
        assertEquals(42.5, second.dbl("resume_secs")!!, .001)
        assertEquals("ea", second.str("media_source_id"))
        switchEngine()
        assertEquals(1.75, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        assertTrue(core.calls.last { it.first == "player.setPause" }.second.bool("paused"))
        assertEquals(listOf("player.play", "player.stopPlayback", "player.play", "player.stopPlayback", "player.play"),
            core.calls.map { it.first }.filter { it in listOf("player.play", "player.stopPlayback") })
    }

    @Test fun Media3默认倍率进入真实内核且显式修改记忆() {
        val core = core()
        show(core, "exo")
        switchEngine()
        assertEquals(1.5, core.calls.last { it.first == "player.setSpeed" }.second.dbl("speed")!!, .001)
        click("更多"); click("倍速"); click("2.0x")
        assertEquals(2.0, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.dbl("default_speed")!!, .001)
        assertNull(core.calls.last { it.first == "player.setPlaybackPrefs" }.second?.get("liuying_speed"))
    }
}
