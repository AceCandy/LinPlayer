package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.plugin.mediaItem
import xyz.linplayer.app.ui.theme.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneColorSchemeTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val original = UiPrefs.colorScheme.value
    @After fun clean() {
        UiPrefs.setColorScheme(ApplicationProvider.getApplicationContext(), original)
        scope.cancel()
    }

    @Test fun palettePersistsAndUnknownValuesFallBack() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for ((_, id) in UiPrefs.colorOptions) {
            UiPrefs.setColorScheme(ctx, id)
            UiPrefs.colorScheme.value = "amber"
            UiPrefs.load(ctx)
            assertEquals(id, UiPrefs.colorScheme.value)
        }
        UiPrefs.setColorScheme(ctx, "unknown")
        assertEquals("amber", UiPrefs.colorScheme.value)
    }

    @Test fun staticPalettesHaveOneAccentAndReadableText() {
        for (base in listOf(DarkColors, LightColors)) for (id in listOf("amber", "blue", "green", "purple")) {
            val c = phonePalette(base, id)
            assertEquals(c.acc, c.mediaAccent)
            assertEquals(c.accFg, c.mediaOnAccent)
            assertEquals(base.bg, c.bg)
            if (id != "amber") assertNotEquals(base.acc, c.acc)
            assertTrue("$id 按钮文字对比度不足", contrast(c.accFg, c.acc) >= 4.5f)
            for (bg in listOf(c.bg, c.s3.compositeOver(c.bg))) for (fg in if (base.isDark) listOf(c.fg, c.fg2) else listOf(c.fg, c.fg2, c.fg3))
                assertTrue("$id 正文对比度不足", contrast(fg, bg) >= 4.5f)
        }
    }

    @Test fun appearanceSelectionChangesAllControlsImmediately() {
        val app = AppState(FakeCore(), scope)
        var accent = Color.Unspecified
        var secondary = Color.Unspecified
        rule.setContent { LpTheme(darkOverride = true, color = UiPrefs.colorScheme.value) {
            accent = Lp.colors.acc
            secondary = MaterialTheme.colorScheme.secondary
            CompositionLocalProvider(LocalApp provides app) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.SettingsSub("appearance")) {
                    composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                }
            }
        } }
        rule.onNodeWithText("色系").performClick()
        rule.onNodeWithText("蓝色").performClick()
        rule.runOnIdle {
            assertEquals("blue", UiPrefs.colorScheme.value)
            assertEquals(phonePalette(DarkColors, "blue").acc, accent)
            assertEquals(accent, secondary)
        }
        rule.onRoot().captureRoboImage("build/phone-color-scheme/settings-blue.png")
        rule.onNodeWithText("色系").performClick()
        rule.onNodeWithText("橙金").performClick()
        rule.runOnIdle { assertEquals(DarkColors.acc, accent) }
    }

    @Test @Config(sdk = [28]) fun monetOnOlderAndroidUsesAmber() {
        var c: LpColors? = null
        rule.setContent { LpTheme(darkOverride = true, color = "monet") { c = Lp.colors } }
        rule.runOnIdle { assertEquals(DarkColors, c) }
    }

    @Test fun monetReadsSystemPaletteAndSwitchesBack() {
        var dark by mutableStateOf(true)
        var selected by mutableStateOf("monet")
        var c: LpColors? = null
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.setContent { LpTheme(darkOverride = dark, color = selected) {
            c = Lp.colors
            Box(Modifier.fillMaxSize().background(Lp.colors.bg))
        } }
        rule.runOnIdle { assertEquals(androidx.compose.material3.dynamicDarkColorScheme(ctx).primary, c!!.acc) }
        rule.runOnIdle { dark = false }
        rule.runOnIdle {
            assertEquals(androidx.compose.material3.dynamicLightColorScheme(ctx).background, c!!.bg)
            assertTrue(contrast(c.accFg, c.acc) >= 4.5f)
        }
        rule.runOnIdle { selected = "amber" }
        rule.runOnIdle { assertEquals(LightColors, c) }
    }

    @Test fun ordinaryPosterUsesTrustedBadgesAndKeepsMenu() {
        var clicked = false
        rule.setContent { LpTheme(darkOverride = true, color = "green") {
            Box(Modifier.fillMaxSize().background(Lp.colors.bg)) {
            MediaCard(Item("s", "统一海报", "Series", year = 2026, unplayed = 8, unplayedCountKnown = true,
                doubanRating = 8.6, rating = 9.9), null, {}, entrance = false,
                menu = listOf(CardAction("收藏") { clicked = true }))
            }
        } }
        rule.onNodeWithContentDescription("8 集未观看").assertExists()
        rule.onNodeWithText("9.9", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("2026", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasClickAction()).performTouchInput { longClick() }
        rule.onNodeWithTag("poster.menu").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/phone-color-scheme/poster-menu.png")
        rule.onNodeWithText("收藏").performClick()
        rule.runOnIdle { assertTrue(clicked) }
    }

    @Test fun pluginRatingRequiresExplicitDoubanSourceAndTenPointScale() {
        fun item(rating: String) = mediaItem(Json.parseToJsonElement("""{"id":"p","ratings":[$rating]}""").obj())
        assertNull(item("""{"source":"TMDB","value":8.6}""").doubanRating)
        assertEquals(8.6, item("""{"source":"豆瓣","value":8.6,"max":10}""").doubanRating!!, 0.0)
        assertNull(item("""{"source":"Douban","value":86,"max":100}""").doubanRating)
    }

    private fun contrast(fg: Color, bg: Color): Float {
        val a = fg.compositeOver(bg).luminance()
        val b = bg.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
