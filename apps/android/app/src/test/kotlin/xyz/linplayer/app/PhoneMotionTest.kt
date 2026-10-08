package xyz.linplayer.app

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import xyz.linplayer.app.ui.components.pressable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.LpTheme
import xyz.linplayer.app.ui.theme.lpSpring
import xyz.linplayer.app.ui.theme.lpTween

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PhoneMotionTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val before = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    @After fun cleanup() { Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, before) }

    @Test fun systemMotionDisabledDuringUseImmediatelyChangesTweenAndSpring() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var scale = -1f
        var tween: FiniteAnimationSpec<Float>? = null
        var spring: FiniteAnimationSpec<Float>? = null
        rule.setContent {
            LpTheme {
                scale = LocalMotionScale.current
                tween = lpTween(300)
                spring = lpSpring()
            }
        }
        rule.runOnIdle { assertEquals(1f, scale) }
        rule.runOnIdle { Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0f, scale)
            assertEquals(0, (tween as TweenSpec<Float>).durationMillis)
            assertTrue(spring is TweenSpec<*>)
            assertEquals(0, (spring as TweenSpec<Float>).durationMillis)
        }
    }
    @Test fun buttonPhysicallyContractsAndReturnsAfterRelease() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.size(200.dp).background(Color.White).testTag("surface"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(100.dp).pressable({ }).background(Color.Red).testTag("button"))
                }
            }
        }
        fun edge(): Color {
            val image = rule.onNodeWithTag("surface").captureToImage()
            return image.toPixelMap()[(image.width * .255f).toInt(), image.height / 2]
        }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(Color.Red, edge())
        rule.onNodeWithTag("button").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Color.White, edge())
        rule.onNodeWithTag("button").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1500)
        assertEquals(Color.Red, edge())
    }

    @Test fun quickTapInScrollableContainerStillShowsFeedbackWithoutDelayingClick() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        rule.mainClock.autoAdvance = false
        var clicks = 0
        rule.setContent {
            LpTheme {
                Box(Modifier.size(200.dp).background(Color.White).testTag("surface")) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(50.dp))
                        Box(Modifier.size(100.dp).pressable({ clicks++ }).background(Color.Red).testTag("button"))
                        Spacer(Modifier.height(50.dp))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        val beforeImage = rule.onNodeWithTag("surface").captureToImage()
        assertEquals(Color.Red, beforeImage.toPixelMap()[(beforeImage.width * .255f).toInt(), beforeImage.height / 2])
        rule.onNodeWithTag("button").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, clicks) }
        rule.mainClock.advanceTimeBy(32)
        val image = rule.onNodeWithTag("surface").captureToImage()
        assertTrue("short tap must expose the light background", image.toPixelMap()[(image.width * .255f).toInt(), image.height / 2].green > .8f)
        rule.mainClock.advanceTimeBy(1500)
        val settled = rule.onNodeWithTag("surface").captureToImage()
        assertEquals(Color.Red, settled.toPixelMap()[(settled.width * .255f).toInt(), settled.height / 2])
        repeat(2) { rule.onNodeWithTag("button").performTouchInput { click() } }
        rule.runOnIdle { assertEquals(3, clicks) }
        rule.mainClock.advanceTimeBy(1500)
    }

    @Test fun smallScaleSpringHasActualOvershootRatherThanSnappingBeforeRebound() {
        var spec: FiniteAnimationSpec<Float>? = null
        rule.setContent { LpTheme { spec = lpSpring(visibilityThreshold = .001f) } }
        rule.runOnIdle {
            val animation = TargetBasedAnimation(spec!!, Float.VectorConverter, .94f, 1f)
            val maximum = (0..700 step 16).maxOf { animation.getValueFromNanos(it * 1_000_000L) }
            assertTrue("small press recovery must cross its target", maximum > 1.001f)
        }
    }

}
