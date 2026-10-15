package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.components.MediaFilterChip
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.PrivateNeonHost
import xyz.linplayer.app.ui.components.privateNeonBorder
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpTheme
import xyz.linplayer.app.ui.theme.DarkColors
import xyz.linplayer.app.ui.theme.privatePalette
import xyz.linplayer.app.ui.theme.R

/** 检查真实绘制的颜色变化与静态降级，防止仅动画数值变化而边框不动。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PrivateNeonTest {
    private val systemMotion = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }
    @get:Rule val rule = createComposeRule(effectContext = systemMotion)

    @Test fun filterGlowFitsDarkSurfaceAndKeepsExpandedTouchTarget() = filterGeometry(true)
    @Test fun filterGlowFitsLightSurfaceAndKeepsExpandedTouchTarget() = filterGeometry(false)

    private fun filterGeometry(dark: Boolean) {
        var clicks = 0
        rule.setContent {
            LpTheme(darkOverride = dark, privateSpace = true) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    PrivateNeonHost {
                        Box(Modifier.size(200.dp, 100.dp).testTag("chip.touch").background(Lp.colors.bg).padding(16.dp)) {
                            MediaFilterChip("筛选", false) { clicks++ }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val image = rule.onNodeWithTag("chip.touch").captureToImage().toPixelMap()
        val bg = privatePalette(if (dark) DarkColors else xyz.linplayer.app.ui.theme.LightColors).bg
        assertEquals("触摸区顶部不能成为按钮轮廓", bg, image[44, 16])
        val edge = image[44, 24]
        assertTrue("可见表面上沿应有明亮荧光", edge.red > .7f || edge.green > .7f)
        rule.onNodeWithTag("chip.touch").captureRoboImage("build/private-neon/filter-$dark.png")
        rule.onNodeWithTag("chip.touch").performTouchInput { click(Offset(44f, 18f)) }
        rule.runOnIdle { assertEquals("可见按钮上方的扩展触摸区仍需响应", 1, clicks) }
    }

    @Test fun halfSystemScaleCompletesCycleInFiveSeconds() = scaledCycle(.5f)
    @Test fun doubleSystemScaleCompletesCycleInTwentySeconds() = scaledCycle(2f)

    private fun scaledCycle(scale: Float) {
        systemMotion.scaleFactor = scale
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme(darkOverride = true, privateSpace = true) {
                CompositionLocalProvider(LocalMotionScale provides scale) {
                    PrivateNeonHost {
                        Box(Modifier.size(280.dp, 200.dp).testTag("neon").background(Lp.colors.bg)
                            .privateNeonBorder(R.lg, inset = 4.dp))
                    }
                }
            }
        }
        step(64)
        val first = pixels()
        val half = (5000 * scale).toLong()
        step(half)
        val middle = pixels()
        step(half)
        val full = pixels()
        fun distance(a: IntArray, b: IntArray): Long = a.indices.sumOf { i ->
            (0..2).sumOf { channel -> kotlin.math.abs((a[i] shr (channel * 8) and 255) - (b[i] shr (channel * 8) and 255)).toLong() }
        }
        assertTrue("半圈颜色必须明显变化", distance(first, middle) > 10_000)
        assertTrue("系统倍率只能应用一次，一圈后应回到原色位", distance(first, full) < distance(first, middle) / 20)
    }

    @Test fun buttonsHaveEdgesAndBareIconsOnlyHaveGlowWhileUnhostedPlayerStaysPlain() {
        val hosted = mutableStateOf(true)
        rule.setContent {
            LpTheme(darkOverride = true, privateSpace = true) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    val content: @Composable () -> Unit = {
                        Column(Modifier.size(240.dp, 220.dp).background(Lp.colors.bg).padding(24.dp)) {
                            LpButton("播放", {}, Modifier.testTag("action"))
                            LpIconButton(LpIcons.search, "搜索", Modifier.testTag("icon")) {}
                        }
                    }
                    if (hosted.value) PrivateNeonHost(content) else content()
                }
            }
        }
        rule.waitForIdle()
        val button = rule.onNodeWithTag("action").captureToImage().toPixelMap()
        val icon = rule.onNodeWithTag("icon").captureToImage().toPixelMap()
        assertEquals("纯图标不应给命中区描边", privatePalette(DarkColors).bg, icon[0, 24])
        rule.runOnIdle { hosted.value = false }
        rule.waitForIdle()
        val plainButton = rule.onNodeWithTag("action").captureToImage().toPixelMap()
        val plainIcon = rule.onNodeWithTag("icon").captureToImage().toPixelMap()
        assertNotEquals("共用按钮本身应有亮边", button[button.width / 2, 0], plainButton[button.width / 2, 0])
        assertNotEquals("图标周围应有柔光", icon[8, 24], plainIcon[8, 24])
        assertEquals("未安装流光宿主的播放窗口保持原底色", privatePalette(DarkColors).bg, plainIcon[8, 24])
    }

    @Test fun flowingPixelsStopInBackgroundAndAtZeroScaleWithoutRecomposingContent() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val motion = mutableStateOf(1f)
        val privateSpace = mutableStateOf(true)
        var compositions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme(darkOverride = true, privateSpace = privateSpace.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalMotionScale provides motion.value) {
                    PrivateNeonHost {
                        SideEffect { compositions++ }
                        Box(Modifier.size(280.dp, 200.dp).testTag("neon")
                            .background(Lp.colors.bg).privateNeonBorder(R.lg, inset = 4.dp)) {
                            Column(Modifier.padding(32.dp)) {
                                LpButton("播放", {})
                                LpIconButton(LpIcons.search, "搜索") {}
                            }
                        }
                    }
                }
            }
        }
        step(32)
        val first = pixels()
        val count = compositions
        step(2500)
        val moving = pixels()
        assertFalse("青红边缘应随时间移动", first.contentEquals(moving))
        assertEquals("流光只能触发绘制，不能逐帧重组内容", count, compositions)
        rule.onNodeWithTag("neon").captureRoboImage("build/private-neon/flow.png")

        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        step(32)
        val stopped = pixels()
        step(2000)
        assertArrayEquals("后台应停止绘制动画", stopped, pixels())
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        step(64)
        val resumed = pixels()
        step(2000)
        assertFalse("回到前台应继续流动", resumed.contentEquals(pixels()))

        rule.runOnIdle { motion.value = 0f }
        step(32)
        val still = pixels()
        step(2000)
        assertArrayEquals("系统零倍率应保留静态荧光", still, pixels())
        assertTrue("静态框仍有青光", still.any { val c = Color(it); c.green > .7f && c.blue > .7f && c.red < .5f })
        assertTrue("静态框仍有红光", still.any { val c = Color(it); c.red > .7f && c.green < .5f })

        rule.runOnIdle { privateSpace.value = false; motion.value = 1f }
        repeat(4) { step(32) }
        val normal = pixels()
        step(2000)
        assertArrayEquals("普通空间不能产生流光", normal, pixels())
        assertNotEquals("退出后边缘恢复普通底色", still[4 * 280 + 140], normal[4 * 280 + 140])
    }

    private fun step(ms: Long) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private fun pixels(): IntArray {
        val image = rule.onNodeWithTag("neon").captureToImage().toPixelMap()
        return IntArray(image.width * image.height) { i -> image[i % image.width, i / image.width].toArgb() }
    }
}
