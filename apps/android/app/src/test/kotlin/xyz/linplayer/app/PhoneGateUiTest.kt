package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.ui.pages.GatePage
import xyz.linplayer.app.ui.theme.LpTheme

/** 登录页两种版式（首登居中卡片 / 添加服务器推入页）的截图基线。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneGateUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun open(dark: Boolean, embedded: Boolean) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(FakeCore(), scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalApp provides app) { GatePage({}, embedded) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun firstLoginLight() {
        open(dark = false, embedded = false)
        rule.onNodeWithText("登录").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/gate-ui/first-light.png")
    }

    @Test fun firstLoginDark() {
        open(dark = true, embedded = false)
        rule.onRoot().captureRoboImage("build/gate-ui/first-dark.png")
    }

    @Test fun addServerLight() {
        open(dark = false, embedded = true)
        rule.onNodeWithText("添加服务器").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/gate-ui/add-light.png")
    }
}
