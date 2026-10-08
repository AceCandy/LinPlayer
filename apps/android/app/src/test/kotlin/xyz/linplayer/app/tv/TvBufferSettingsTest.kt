package xyz.linplayer.app.tv

import android.app.Application
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
import kotlinx.coroutines.runBlocking
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
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.long

/** 遥控真实设置入口，验证容量保存、失败回滚和恢复自动。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvBufferSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun cleanup() { scope.cancel(); PageCache.clear() }

    @Test fun 遥控设置缓冲失败回滚并可改容量及回自动() {
        var fail = true
        var bytes = 0L
        val core = FakeCore().loggedIn().settings().apply {
            ret("player.getPlaybackPrefs", buildJsonObject { put("buffer_target_bytes", bytes) })
            on("player.setPlaybackPrefs") { args ->
                if (fail) throw CoreException("E_NETWORK", "保存失败", true)
                bytes = args.long("buffer_target_bytes")!!
                buildJsonObject { put("buffer_target_bytes", bytes) }
            }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        PageCache.put("tv.settings.cat", 1)
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { Shell(TvRoute.Settings) } }
        advance(rule, 800)
        fun choose(label: String) {
            rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            press(rule, Key.Enter)
        }
        choose("播放缓冲"); choose("自定义")
        assertEquals(134217728L, core.calls.last { it.first == "player.setPlaybackPrefs" }.second.long("buffer_target_bytes"))
        rule.onNodeWithText("缓冲目标容量").assertDoesNotExist()
        fail = false
        choose("播放缓冲"); choose("自定义")
        rule.onNodeWithText("128 MiB").assertIsDisplayed()
        rule.onNodeWithText("缓冲目标容量").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight)
        assertEquals(201326592L, bytes)
        rule.onNodeWithText("192 MiB").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/management-ui/tv-buffer.png")
        choose("播放缓冲"); choose("自动")
        assertEquals(0L, bytes)
        rule.onNodeWithText("缓冲目标容量").assertDoesNotExist()
    }
}
