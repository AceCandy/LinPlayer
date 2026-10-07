package xyz.linplayer.app.tv

import android.app.Application
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
}
