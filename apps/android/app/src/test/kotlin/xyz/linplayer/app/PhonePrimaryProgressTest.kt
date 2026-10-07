package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
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
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.pages.PrimaryProgressPanel
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhonePrimaryProgressTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun cleanup() { scope.cancel() }

    @Test fun 主服选择落库与冲突保留并覆盖深浅主题() {
        val dark = mutableStateOf(false)
        var server = ""
        fun state() = buildJsonObject {
            put("server", server); put("user_id", "user"); put("name", "家庭媒体服务器")
            put("valid", server.isNotEmpty()); put("pending", 1); put("conflicts", 1)
        }
        val core = FakeCore().apply {
            ret("account.listAccounts", JsonArray(listOf(buildJsonObject {
                put("server", "https://primary.invalid"); put("user_id", "user")
                put("name", "家庭媒体服务器"); put("source_kind", "emby")
            })))
            on("prefs.getPrimaryProgressServer") { state() }
            on("prefs.setPrimaryProgressServer") { args -> server = args.str("server_id").orEmpty(); state() }
            on("prefs.retryPrimaryProgressSync") { state() }
        }
        val app = AppState(core, scope)
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app) { PrimaryProgressPanel() }
            }
        }
        rule.onNodeWithText("家庭媒体服务器").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("https://primary.invalid", server) }
        rule.onNodeWithText("待同步 1 项 · 冲突 1 项").assertIsDisplayed()
        rule.onNodeWithText("重试待同步").performClick()
        rule.onNodeWithText("待同步 1 项 · 冲突 1 项").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/primary-progress-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/primary-progress-dark.png")
        rule.onNodeWithText("未指定").performClick()
        rule.runOnIdle {
            assertEquals("", server)
            assertEquals(2, core.calls.count { it.first == "prefs.setPrimaryProgressServer" })
            assertEquals(1, core.calls.count { it.first == "prefs.retryPrimaryProgressSync" })
        }
    }
}
