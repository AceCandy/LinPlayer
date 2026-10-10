package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpTheme

/** 同一套手机组件在三字体、系统缩放和深浅色下保持可读与一致。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneTypographyTest {
    @get:Rule val rule = createComposeRule()
    private val original = UiPrefs.uiFont.value
    @After fun restore() { UiPrefs.uiFont.value = original }

    private fun layout(text: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.first()
    }

    @Test fun topBarAndFieldsStayReadableAcrossFontsAndScales() {
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    Column(Modifier.fillMaxSize().background(Lp.colors.bg)) {
                        LpTopBar("名称很长的媒体库标题", "30+ 部")
                        LpCell("普通设置", sub = "设置说明")
                        LpCell("媒体设置", mediaStyle = true, sub = "设置说明")
                        LpField("", {}, placeholder = "搜索片名")
                        LpField("输入后的片名", {}, placeholder = "搜索片名")
                    }
                }
            }
        }
        for (font in listOf("", "sans", "serif")) for (factor in listOf(1f, 1.3f, 2f)) for (isDark in listOf(false, true)) {
            rule.runOnIdle { UiPrefs.uiFont.value = font; scale.value = factor; dark.value = isDark }
            rule.waitForIdle()
            val subtitle = layout("30+ 部")
            assertFalse("副标题裁切: $font/$factor/$isDark", subtitle.didOverflowHeight)
            val title = layout("名称很长的媒体库标题")
            assertFalse("标题裁切: $font/$factor/$isDark", title.didOverflowHeight)
            val ordinary = layout("普通设置").layoutInput.style
            val media = layout("媒体设置").layoutInput.style
            assertEquals("设置主文案字号不同", ordinary.fontSize, media.fontSize)
            assertEquals("设置主文案行高不同", ordinary.lineHeight, media.lineHeight)
            val placeholder = layout("搜索片名").layoutInput.style
            val input = layout("输入后的片名").layoutInput.style
            assertEquals("输入提示字号不同", input.fontSize, placeholder.fontSize)
            assertEquals("输入提示行高不同", input.lineHeight, placeholder.lineHeight)
            if ((font == "serif" && factor == 2f) || (font == "" && factor == 1f)) {
                rule.onRoot().captureRoboImage("build/typography-${font.ifEmpty { "system" }}-$factor-$isDark.png")
            }
        }
    }

    @Test fun timeDigitsKeepTheirWidthAcrossFonts() {
        rule.setContent {
            LpTheme {
                Column {
                    androidx.compose.material3.Text("00:00", style = xyz.linplayer.app.ui.theme.LpText.number)
                    androidx.compose.material3.Text("11:11", style = xyz.linplayer.app.ui.theme.LpText.number)
                    androidx.compose.material3.Text("88:88", style = xyz.linplayer.app.ui.theme.LpText.number)
                }
            }
        }
        for (font in listOf("", "sans", "serif")) {
            rule.runOnIdle { UiPrefs.uiFont.value = font }
            rule.waitForIdle()
            val width = layout("00:00").getLineRight(0)
            for (value in listOf("11:11", "88:88")) {
                assertEquals("时间数字宽度不稳定: $font/$value", width, layout(value).getLineRight(0), .1f)
            }
        }
    }

    @Test fun materialSlotsKeepSelectedFontAndChineseSpacing() {
        val styles = mutableListOf<androidx.compose.ui.text.TextStyle>()
        rule.setContent {
            LpTheme {
                val t = MaterialTheme.typography
                styles.clear()
                styles.addAll(listOf(t.displayLarge, t.displayMedium, t.displaySmall,
                    t.headlineLarge, t.headlineMedium, t.headlineSmall,
                    t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium,
                    t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall))
            }
        }
        for (font in listOf("", "sans", "serif")) {
            rule.runOnIdle { UiPrefs.uiFont.value = font }
            rule.waitForIdle()
            val family = styles.first().fontFamily
            for (style in styles) {
                assertEquals("M3样式脱离已选字体: $font", family, style.fontFamily)
                assertEquals("中文额外字距", 0.sp, style.letterSpacing)
                assertNotEquals("缺失明确行高", androidx.compose.ui.unit.TextUnit.Unspecified, style.lineHeight)
            }
        }
    }
}
