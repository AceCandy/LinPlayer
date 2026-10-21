package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.tv.advance
import xyz.linplayer.app.ui.pages.PhoneLiuyingChrome
import xyz.linplayer.app.ui.pages.PhoneLiuyingFilters
import xyz.linplayer.app.ui.player.FeedPlayback
import xyz.linplayer.app.ui.theme.LpTheme

/** 纯合成亮色背景用于检查叠图控件，不代表解码画面或真实视频适配。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneLiuyingChromeTest {
    @get:Rule val rule = createComposeRule()
    private var dark by mutableStateOf(true)
    private var sheet by mutableStateOf(false)
    private var next = 0
    private var label by mutableStateOf("")

    private fun show(fontScale: Float = 1f, manyLabels: Boolean = false) {
        val labels = (1..if (manyLabels) 40 else 3).map { n -> buildJsonObject {
            put("kind", if (n % 2 == 0) "exposure" else "channel")
            put("name", "label-$n"); put("title", if (n % 2 == 0) "衣着$n" else "频道$n"); put("count", n * 9)
        } }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LpTheme(darkOverride = dark, privateSpace = true) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(Color(0xFFE4CFA6), Color(0xFF85B9B0), Color(0xFFC8DCC6))))) {
                        Text("合成亮色背景 · 非视频画面", Modifier.align(Alignment.Center))
                        PhoneLiuyingChrome("normal", "dance", "频道1 · 全部衣着", true,
                            buildJsonObject {
                                put("title", "光影之间，找到自己的节奏。这是一段用于检查两行标题的展示文字。")
                                put("author", "入库作者"); put("favorite", true)
                            }, false, true,
                            FeedPlayback(18.0, 52.0, false, false, true, {}, {}, {}),
                            {}, {}, {}, {}, { sheet = true }, {})
                        if (sheet) PhoneLiuyingFilters(labels, true, label, "", false,
                            true, true, {}, { next++ }, { _, name -> label = name }, {}, { label = "" }, { sheet = false })
                    }
                }
            }
        }
        advance(rule, 300)
    }

    private fun render(name: String) {
        rule.onNodeWithTag("liuying.favorite").assertIsDisplayed()
        rule.onNodeWithTag("liuying.filter").assertIsDisplayed()
        rule.onNodeWithTag("liuying.mode").assertIsDisplayed()
        rule.onNodeWithTag("liuying.sound").assertIsDisplayed()
        rule.onNodeWithTag("liuying.progress").assertIsDisplayed()
        val pixels = rule.onNodeWithTag("liuying.progress").captureToImage().toPixelMap()
        val whiteCounts = (0 until pixels.height).map { y ->
            (0 until pixels.width).count { x ->
                val color = pixels[x, y]
                color.red > .7f && color.green > .7f && color.blue > .7f
            }
        }
        val trackRows = whiteCounts.indices.filter { whiteCounts[it] > 20 }
        val dotRows = whiteCounts.indices.filter { whiteCounts[it] in 1..6 }
        assertTrue("轨道和圆点均实际绘制", trackRows.isNotEmpty() && dotRows.isNotEmpty())
        assertEquals("圆点与轨道垂直居中", trackRows.average(), dotRows.average(), .5)
        val rail = rule.onNodeWithTag("liuying.rail").getUnclippedBoundsInRoot()
        val details = rule.onNodeWithTag("liuying.details").getUnclippedBoundsInRoot()
        assertTrue("右侧操作不遮挡标题", rail.bottom <= details.top)
        rule.onRoot().captureRoboImage("build/liuying-mobile/$name.png")
        rule.onNodeWithTag("liuying.filter").performClick(); advance(rule, 300)
        rule.onNodeWithText("完成").assertIsDisplayed()
        rule.onNodeWithTag("liuying.next").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, next)
        rule.onNodeWithTag("liuying.clear").performScrollTo(); advance(rule, 300)
        rule.onNode(isPopup()).captureRoboImage("build/liuying-mobile/$name-filter.png")
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
    }

    @Test fun portraitDarkAndLightKeepOverlayLegible() {
        show()
        render("portrait-dark-synthetic")
        rule.runOnIdle { dark = false }; advance(rule, 300)
        next = 0
        render("portrait-light-synthetic")
    }

    @Test
    @Config(qualifiers = "w873dp-h393dp-land-mdpi")
    fun landscapeControlsAndSheetRemainReachable() {
        show()
        render("landscape-synthetic")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun smallPhoneLargeTextAndLongLabelListCanScroll() {
        show(fontScale = 1.3f, manyLabels = true)
        render("small-large-text-synthetic")
        rule.onNodeWithTag("liuying.filter").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.label.label-40").performScrollTo().performClick(); advance(rule, 300)
        assertEquals("label-40", label)
        rule.onNodeWithText("完成").assertIsDisplayed()
    }
}
