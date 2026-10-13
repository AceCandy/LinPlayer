package xyz.linplayer.app

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpLoading
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.LpTheme
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h800dp-mdpi", sdk = [36], application = Application::class)
class PhoneLoadingTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val before = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private val beforeAnimatorScale = android.animation.ValueAnimator.getDurationScale()
    private val originalStyle = UiPrefs.loadingStyle.value
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
    }

    @Before fun setup() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        // 显式固定 View 动画倍率，以观察 Material 的真实时间推进。
        animatorScale(1f)
    }
    @After fun cleanup() {
        UiPrefs.setLoadingStyle(ctx, originalStyle)
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, before)
        animatorScale(beforeAnimatorScale)
        scope.cancel()
    }

    @Test fun sevenStylesRenderWithZeroMotionAndOnlyThemedStylesChangeColor() {
        val tint = mutableStateOf(Color.Red)
        rule.setContent { LpTheme {
            CompositionLocalProvider(LocalMotionScale provides 0f) {
                Row(Modifier.background(Color.Black)) {
                    for ((_, id) in UiPrefs.loadingOptions) LpLoading(Modifier.testTag("style$id"), size = 64.dp, style = id, tint = tint.value)
                }
            }
        } }
        rule.waitUntil(10_000) { UiPrefs.loadingOptions.all { (_, id) -> pixels("style$id").count { it and 0x00ffffff != 0 } > 10 } }
        val red = UiPrefs.loadingOptions.associate { (_, id) -> id to pixels("style$id") }
        rule.onRoot().captureRoboImage("build/phone-loading/seven-styles.png")
        rule.runOnIdle { tint.value = Color.Blue }
        rule.waitForIdle()
        for ((_, id) in UiPrefs.loadingOptions) {
            val same = red.getValue(id).contentEquals(pixels("style$id"))
            assertEquals("只有 Material / Style4 跟随主题颜色", id !in listOf(0, 4), same)
        }
    }

    @Test fun lottieStopsInBackgroundResumesAndDoesNotReturnAfterRemoval() {
        rule.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        rule.setContent { LpTheme {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (visible.value) LpLoading(Modifier.testTag("animation"), size = 64.dp, style = 1)
                }
            }
        } }
        rule.waitUntil(10_000) { step(64); pixels("animation").any { it and 0x00ffffff != 0 } }
        val first = pixels("animation")
        step(400)
        assertFalse("前台必须推进真实绘制帧", first.contentEquals(pixels("animation")))
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        step(32)
        val stopped = pixels("animation")
        step(700)
        assertArrayEquals("后台不能继续推进帧", stopped, pixels("animation"))
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        step(400)
        assertFalse("恢复前台继续等待时应播放", stopped.contentEquals(pixels("animation")))
        rule.runOnIdle { visible.value = false; owner.lifecycle.currentState = Lifecycle.State.CREATED }
        step(32)
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        step(400)
        rule.onNodeWithTag("animation").assertDoesNotExist()
    }

    @Test fun appearanceSelectsPreviewImmediatelyAndPersistsAcrossReload() {
        UiPrefs.setLoadingStyle(ctx, 0)
        val app = AppState(FakeCore(), scope)
        rule.setContent { LpTheme {
            CompositionLocalProvider(LocalApp provides app) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.SettingsSub("appearance")) {
                    composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                }
            }
        } }
        rule.onNodeWithText("加载动画").performScrollTo().performClick()
        for ((label, _) in UiPrefs.loadingOptions) rule.onNodeWithContentDescription("$label 预览", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Style7").performScrollTo().performClick()
        assertEquals(7, UiPrefs.loadingStyle.value)
        rule.onNodeWithText("Style7").assertIsDisplayed()
        UiPrefs.loadingStyle.value = 0
        UiPrefs.load(ctx)
        assertEquals(7, UiPrefs.loadingStyle.value)
        ctx.getSharedPreferences("lp_ui", 0).edit().putInt("loading_style", 6).apply()
        UiPrefs.load(ctx)
        assertEquals("无效旧值回落默认", 0, UiPrefs.loadingStyle.value)
    }

    @Test fun loadingButtonKeepsGeometryAndBlocksDuplicateClicks() {
        val loading = mutableStateOf(false)
        var clicks = 0
        rule.setContent { LpTheme { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LpButton("确定并继续播放", { clicks++ }, Modifier.testTag("button"), loading = loading.value)
        } } }
        val before = rule.onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { loading.value = true }
        assertEquals(before, rule.onNodeWithTag("button").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("button").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
        rule.onNodeWithContentDescription("加载中").assertExists()
        rule.runOnIdle { loading.value = false }
        rule.onNodeWithTag("button").performClick()
        assertEquals(1, clicks)
    }

    private fun step(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        rule.waitForIdle()
    }
    private fun pixels(tag: String): IntArray {
        val image = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return IntArray(image.width * image.height) { i -> image[i % image.width, i / image.width].let {
            ((it.alpha * 255).toInt() shl 24) or ((it.red * 255).toInt() shl 16) or
                ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt()
        } }
    }
    private fun animatorScale(value: Float) {
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, value)
    }
}
