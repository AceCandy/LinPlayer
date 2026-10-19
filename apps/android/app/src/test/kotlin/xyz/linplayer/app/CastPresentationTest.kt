package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.LocalTvMode
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.pages.People
import xyz.linplayer.app.ui.pages.Person
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h600dp-mdpi", sdk = [36], application = Application::class)
class CastPresentationTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel() }

    private fun mount(tv: Boolean = false, dark: Boolean = true) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(FakeCore().loggedIn(), scope)
        runBlocking { app.boot() }
        val people = (0..20).map { Person("a$it", "演员$it", if (it == 1) null else "角色$it") } +
            Person("director", "导演甲", "导演") + Person("writer", "编剧乙", "编剧")
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalTvMode provides tv) {
                    Column(Modifier.fillMaxSize().background(Lp.colors.bg)) { People(app, people) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun style(text: String): androidx.compose.ui.text.TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style
    }

    private fun checkLayout(tv: Boolean) {
        rule.onNodeWithContentDescription("演员0").assertWidthIsEqualTo(if (tv) 88.dp else 72.dp)
        val avatar = rule.onNodeWithContentDescription("演员0").fetchSemanticsNode().boundsInRoot
        val name = rule.onNodeWithText("演员0").fetchSemanticsNode().boundsInRoot
        val role = rule.onNodeWithText("角色0").fetchSemanticsNode().boundsInRoot
        assertTrue(avatar.bottom <= name.top && name.bottom < role.top)
        assertEquals(FontWeight.Bold, style("演员0").fontWeight)
        assertTrue(style("演员0").fontSize.value > style("角色0").fontSize.value)
        rule.onNodeWithText("角色1").assertDoesNotExist()
    }

    @Test fun phoneDark() = phone(dark = true)
    @Test fun phoneLight() = phone(dark = false)
    private fun phone(dark: Boolean) {
        mount(dark = dark)
        checkLayout(tv = false)
        rule.onRoot().captureRoboImage("build/cast/phone-${if (dark) "dark" else "light"}.png")
        rule.onNodeWithTag("detail.people.row").performScrollToIndex(22)
        rule.onNodeWithText("编剧乙").assertIsDisplayed()
        rule.onNodeWithText("编剧").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvRemoteReachesCrewAtEnd() {
        mount(tv = true)
        checkLayout(tv = true)
        rule.onNodeWithTag("detail.people.row").performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(24) {
            rule.onNodeWithTag("detail.people.row").performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitForIdle()
        }
        rule.onNodeWithText("编剧乙").assertIsDisplayed()
        rule.onNodeWithText("编剧").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/cast/tv-crew.png")
        repeat(24) {
            rule.onNodeWithTag("detail.people.row").performKeyInput { pressKey(Key.DirectionLeft) }
            rule.waitForIdle()
        }
        rule.onNodeWithText("演员0").assertIsDisplayed()
    }
}
