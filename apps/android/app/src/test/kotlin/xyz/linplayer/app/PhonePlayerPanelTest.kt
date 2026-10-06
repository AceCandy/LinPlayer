package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
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
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.player.PlayerPanel
import xyz.linplayer.app.ui.player.trackLanguage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [36], application = Application::class)
class PhonePlayerPanelTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel() }

    @Test fun trackPanelUsesChineseLanguagesAndDispatchesSelection() {
        val core = FakeCore().loggedIn().apply {
            ret("player.tracks", arr(
                buildJsonObject { put("id", "1"); put("kind", "audio"); put("lang", "jpn"); put("title", "原声 AAC stereo"); put("selected", true) },
                buildJsonObject { put("id", "2"); put("kind", "audio"); put("lang", "eng") },
                buildJsonObject { put("id", "3"); put("kind", "audio"); put("lang", "und"); put("title", "未标注") },
                buildJsonObject { put("id", "4"); put("kind", "sub"); put("lang", "zh-Hans"); put("selected", true) },
                buildJsonObject { put("id", "5"); put("kind", "sub"); put("lang", "zh-TW") }))
            ret("player.setTrack", JsonNull)
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        val dark = mutableStateOf(false)
        val kind = mutableStateOf("audio")
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF455F48))) {
                        PlayerPanel(kind.value, "episode", onClose = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("日语").assertIsDisplayed()
        rule.onNodeWithText("英语").assertIsDisplayed()
        rule.onNodeWithText("未标注").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/player-osd/audio-panel-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/player-osd/audio-panel-dark.png")
        rule.onNodeWithText("英语").performClick()
        rule.waitForIdle()
        assertEquals("2", core.calls.last { it.first == "player.setTrack" }.second.str("id"))
        rule.runOnIdle { kind.value = "subtitle" }
        rule.waitForIdle()
        rule.onNodeWithText("关闭字幕").assertIsDisplayed()
        rule.onNodeWithText("简体中文").assertIsDisplayed()
        rule.onNodeWithText("繁体中文").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/subtitle-panel-dark.png")
        rule.onNodeWithText("繁体中文").performClick()
        rule.waitForIdle()
        assertEquals("5", core.calls.last { it.first == "player.setTrack" }.second.str("id"))
        assertEquals("sub", core.calls.last { it.first == "player.setTrack" }.second.str("kind"))
        assertEquals("简体中文", trackLanguage("zh-Hans", "subtitle"))
        assertEquals("繁体中文", trackLanguage("zh-TW", "subtitle"))
        assertEquals(null, trackLanguage("und", "audio"))
    }
}
