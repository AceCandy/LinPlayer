package xyz.linplayer.app.tv

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.pages.args
import java.io.File

/** 真实 TV 播放页与遥控焦点；背景为合成素材，不是实际解码画面。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-mdpi", sdk = [36], application = Application::class)
class TvPlayerLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val core = FakeCore().loggedIn().player()
    private val app = AppState(core, scope)
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val oldTheme = UiPrefs.theme.value
    private val oldColor = UiPrefs.colorScheme.value
    private val oldTouch = org.robolectric.shadows.ShadowWindowManagerGlobal.getInTouchMode()
    private lateinit var back: OnBackPressedDispatcher
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "tools/visual-fixtures/catalog.json").exists() }

    @After fun clean() {
        scope.cancel(); app.bg.cancel()
        UiPrefs.setTheme(context, oldTheme); UiPrefs.setColorScheme(context, oldColor)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(oldTouch)
    }

    private fun show(series: Boolean, light: Boolean = false, fontScale: Float = 1f, speed: Double = 1.0, brightFrame: Boolean = false) {
        UiPrefs.setTheme(context, if (light) "light" else "dark"); UiPrefs.setColorScheme(context, "amber")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        if (!series) {
            core.ret("emby.itemDetail", args("id" to "movie", "name" to "远岸来信", "type_" to "Movie"))
            core.ret("emby.playbackSeason", arr())
        }
        core.ret("prefs.getPrefs", args("default_speed" to speed))
        core.ret("player.stopPlayback", JsonNull)
        runBlocking { app.boot() }
        val nav = TvNav().apply { push(TvRoute.Player(if (series) "sh6" else "movie", "远岸来信", engine = "mpv")) }
        val still = BitmapFactory.decodeFile(File(root, "tools/visual-fixtures/visual-1-Backdrop.png").path).asImageBitmap()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvFrame(app) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize()) {
                        if (brightFrame) Box(Modifier.fillMaxSize().background(Color.White))
                        else Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        PlayerHost(nav)
                    }
                }
            }
        }
        advance(rule, 800)
        core.tick(1517.0); advance(rule, 150)
        core.tick(1517.25); advance(rule, 150)
        rule.onNodeWithTag("osd.bar").assertIsFocused()
    }

    private fun capture(name: String) {
        val bounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("osd.speed", "osd.dm", "osd.audio", "osd.sub", "osd.more", "osd.bar")) {
            val rect = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag outside $bounds: $rect", rect.left >= 0 && rect.right <= bounds.right && rect.bottom <= bounds.bottom)
        }
        val bar = rule.onNodeWithTag("osd.bar").fetchSemanticsNode().boundsInRoot
        assertTrue("进度应随窗口宽度伸缩", bar.width > bounds.width * .85f && bar.width < bounds.width)
        rule.onNodeWithTag("osd.play").assertDoesNotExist()
        val actions = listOfNotNull(if (name.startsWith("series")) "osd.next" else null,
            "osd.speed", "osd.dm", "osd.audio", "osd.sub", "osd.more").map {
            rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        assertTrue("工具组位于进度条上方", actions.all { it.bottom < bar.top })
        assertTrue("工具顺序和间距", actions.zipWithNext().all { (a, b) -> a.right < b.left })
        val title = rule.onNodeWithText(if (name.startsWith("movie")) "远岸来信" else "幕府将军").fetchSemanticsNode().boundsInRoot
        assertTrue("标题不侵入工具组", title.right < actions.first().left)
        assertTrue("时间码位于进度条下方", rule.onNodeWithText("25:17").fetchSemanticsNode().boundsInRoot.top >= bar.bottom)
        assertTrue("标题不应留在顶部", rule.onNodeWithText(if (name.startsWith("movie")) "远岸来信" else "幕府将军")
            .fetchSemanticsNode().boundsInRoot.top > bounds.height / 2)
        shot(name)
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage(File(root, "build/visual-alignment/tv/player-$name.png").path)

    @Test fun movieControlsAndHiddenVideo() {
        show(series = false)
        rule.onNodeWithTag("osd.next").assertDoesNotExist()
        capture("movie")
        val expanded = rule.onRoot().captureToImage().toPixelMap()
        rule.runOnUiThread { back.onBackPressed() }; advance(rule, 300)
        rule.onNodeWithTag("osd.play").assertDoesNotExist()
        rule.onNodeWithText("远岸来信").assertDoesNotExist()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        val hidden = rule.onRoot().captureToImage().toPixelMap()
        val x = hidden.width / 2; val y = hidden.height * 4 / 5
        assertTrue("收起后不残留底部遮罩", hidden[x, y].luminance() > expanded[x, y].luminance())
        shot("hidden")
    }

    @Test fun seriesNextAndSubtitleFocus() {
        show(series = true)
        press(rule, Key.DirectionUp)
        rule.onNodeWithTag("osd.next").assertIsFocused()
        capture("series")
        val rect = rule.onNodeWithTag("osd.next").fetchSemanticsNode().boundsInRoot
        val x = rect.center.x.toInt(); val y = (rect.top + rect.height * .15f).toInt()
        val beforeHold = rule.onRoot().captureToImage().toPixelMap()[x, y]
        rule.onRoot().performKeyInput { keyDown(Key.Enter) }; advance(rule, 750)
        rule.onRoot().performKeyInput { keyUp(Key.Enter) }; advance(rule, 300)
        assertEquals("长按松手后不能残留按钮按压态", beforeHold, rule.onRoot().captureToImage().toPixelMap()[x, y])
        rule.onNodeWithTag("osd.next").assertIsFocused()
        press(rule, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag("osd.sub").assertIsFocused()
        capture("series-sub-focus")
        press(rule, Key.Enter)
        rule.onNodeWithText("字幕").assertExists()
        shot("subtitles")
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi") fun compactLargeText() {
        show(series = true, fontScale = 1.4f, speed = 3.75)
        rule.onNodeWithText("3.75×").assertIsDisplayed()
        assertTrue(rule.onNodeWithTag("osd.speed").fetchSemanticsNode().boundsInRoot.width >
            rule.onNodeWithTag("osd.next").fetchSemanticsNode().boundsInRoot.width)
        capture("series-compact-large-text")
    }

    @Test fun lightThemeVideoControlsKeepContrast() {
        show(series = false, light = true)
        capture("movie-light")
    }

    @Test fun brightVideoKeepsEpisodeTextReadable() {
        show(series = true, brightFrame = true)
        val rect = rule.onNodeWithText("S1E6：妾之王国").fetchSemanticsNode().boundsInRoot
        val background = rule.onRoot().captureToImage().toPixelMap()[(rect.left - 4).toInt(), rect.center.y.toInt()]
        val ink = Color.White.copy(alpha = .8f).compositeOver(background)
        val contrast = (ink.luminance() + .05f) / (background.luminance() + .05f)
        assertTrue("明亮画面的副标题对比度 $contrast 不足", contrast >= 4.5f)
        capture("series-bright-frame")
    }
}
