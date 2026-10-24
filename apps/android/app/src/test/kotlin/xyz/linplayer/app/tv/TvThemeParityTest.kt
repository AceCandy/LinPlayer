package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.ui.theme.LightColors
import xyz.linplayer.app.ui.theme.DarkColors
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.privatePalette

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi", sdk = [36], application = Application::class)
class TvThemeParityTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app = AppState(FakeCore().loggedIn().apply {
        ret("emby.views", arr(buildJsonObject {
            put("id", "classified"); put("name", "媒体库"); put("is_adult", true)
        }))
    }, scope)
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val theme = UiPrefs.theme.value
    private val color = UiPrefs.colorScheme.value

    @After fun clean() {
        UiPrefs.setTheme(ctx, theme)
        UiPrefs.setColorScheme(ctx, color)
        TvC.sharedColors = null
        scope.cancel()
        app.bg.cancel()
    }

    @Test fun frameFollowsLocalModeAndColorWithoutSplittingControls() {
        UiPrefs.setTheme(ctx, "light")
        UiPrefs.setColorScheme(ctx, "blue")
        var sharedAccent = Color.Transparent
        var materialAccent = Color.Transparent
        val secondFrame = mutableStateOf(false)
        rule.setContent {
            TvFrame(app) {
                val c = Lp.colors
                val m = androidx.tv.material3.MaterialTheme.colorScheme
                SideEffect { sharedAccent = c.acc; materialAccent = m.primary }
                Box(Modifier.size(64.dp).testTag("surface").background(TvC.bg))
            }
            if (secondFrame.value) TvFrame(app) { }
        }
        rule.waitForIdle()
        assertEquals(LightColors.bg, pixel())
        assertEquals(Color(0xFF315DA8), sharedAccent)
        assertEquals(sharedAccent, materialAccent)
        assertEquals(sharedAccent, TvC.acc)
        assertEquals(LightColors.warn, TvC.warn)
        rule.runOnIdle { runBlocking { app.boot(); app.enterPrivateSpace() } }
        rule.waitForIdle()
        assertEquals(privatePalette(LightColors).bg, pixel())
        assertEquals(privatePalette(LightColors).acc, TvC.acc)
        rule.runOnIdle { app.leavePrivateSpace() }
        rule.waitForIdle()
        assertEquals(LightColors.bg, pixel())
        assertEquals(Color(0xFF315DA8), TvC.acc)
        rule.runOnIdle {
            UiPrefs.setTheme(ctx, "dark")
            UiPrefs.setColorScheme(ctx, "green")
        }
        rule.waitForIdle()
        assertEquals(DarkColors.bg, pixel())
        assertEquals(Color(0xFF82D6B0), sharedAccent)
        assertEquals(sharedAccent, TvC.acc)
        assertEquals(sharedAccent, materialAccent)
        rule.runOnIdle { secondFrame.value = true }
        rule.waitForIdle()
        rule.runOnIdle { secondFrame.value = false }
        rule.waitForIdle()
        assertEquals(DarkColors.bg, pixel())
        assertEquals(Color(0xFF82D6B0), TvC.acc)
    }

    private fun pixel() = rule.onNodeWithTag("surface").captureToImage().toPixelMap()[32, 32]
}
