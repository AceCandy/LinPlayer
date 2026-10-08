package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.library
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.GlassIcon
import xyz.linplayer.app.ui.components.PrimaryAction
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.pages.LibraryPage
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.plugin.Wallpaper
import xyz.linplayer.app.ui.theme.DarkColors
import xyz.linplayer.app.ui.theme.LightColors
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpTheme

/** 手机浅色文字对比度及切换主题后的真实外观页渲染回归。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneThemeTest {
    @get:Rule val rule = createComposeRule()
    private val originalTheme = UiPrefs.theme.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun resetTheme() {
        UiPrefs.setTheme(ApplicationProvider.getApplicationContext(), originalTheme)
        UiPrefs.setFont(ApplicationProvider.getApplicationContext(), "")
        Wallpaper.set(null)
        PageCache.clear()
        scope.cancel()
    }

    @Test fun lightTextContrast() {
        val c = LightColors
        for (background in listOf(Color.Black, Color.White)) {
            val chip = c.chip.compositeOver(background)
            assertTrue("浅色服务器标签对比度不足", contrast(c.fg, chip) >= 4.5f)
            assertTrue("浅色服务器箭头对比度不足", contrast(c.fg2, chip) >= 4.5f)
        }
        for (background in listOf(c.bg, c.s1.compositeOver(c.bg),
            c.s2.compositeOver(c.bg), c.s3.compositeOver(c.bg))) {
            for (foreground in listOf(c.fg, c.fg2, c.fg3)) {
                assertTrue("浅色文字对比度不足: $foreground / $background",
                    contrast(foreground, background) >= 4.5f)
            }
        }
    }

    @Test fun appearanceFollowsThemeSwitch() {
        UiPrefs.theme.value = "dark"
        val app = AppState(FakeCore(), scope)
        rule.setContent {
            LpTheme(darkOverride = UiPrefs.theme.value == "dark") {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.SettingsSub("appearance")) {
                        composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("壁纸").assertDoesNotExist()
        rule.onNodeWithText("思源黑体").performClick()
        assertEquals("sans", UiPrefs.uiFont.value)
        rule.onNodeWithText("思源宋体").performClick()
        assertEquals("serif", UiPrefs.uiFont.value)
        rule.onNodeWithText("系统默认").performClick()
        assertEquals("", UiPrefs.uiFont.value)
        assertBackground(DarkColors.bg)
        rule.onRoot().captureRoboImage("build/phone-theme/dark.png")
        rule.onNodeWithText("浅色").performClick()
        rule.waitForIdle()
        assertBackground(LightColors.bg)
        rule.onRoot().captureRoboImage("build/phone-theme/light.png")
        rule.onNodeWithText("深色").performClick()
        rule.waitForIdle()
        assertBackground(DarkColors.bg)
    }

    @Test fun homeKeepsThemeBackgroundWhenWallpaperCannotRender() {
        PageCache.clear()
        val wall = buildJsonObject { put("kind", "shader"); put("file", "unsupported") }
        val core = FakeCore().loggedIn().apply {
            ret("plugin.initialWallpaper", wall)
            listOf("emby.listRandom", "emby.listResume", "emby.listNextUp", "emby.views", "emby.listCollections")
                .forEach { ret(it, arr()) }
        }
        Wallpaper.set(wall)
        UiPrefs.theme.value = "light"
        val app = AppState(core, scope)
        rule.setContent {
            // 模拟窗口旧黑底，真实 PhoneRoot 必须自己铺当前主题底色。
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LpTheme(darkOverride = UiPrefs.theme.value == "dark") { PhoneRoot(app) }
            }
        }
        rule.waitForIdle()
        assertBackground(LightColors.bg)
        rule.runOnIdle { UiPrefs.theme.value = "dark" }
        assertBackground(DarkColors.bg)
        rule.runOnIdle { UiPrefs.theme.value = "light" }
        assertBackground(LightColors.bg)
        rule.onRoot().captureRoboImage("build/phone-theme/home-light.png")
    }

    @Test fun glassControlsAndMaterialSurfacesUseCurrentPalette() {
        UiPrefs.theme.value = "light"
        var surface = Color.Unspecified
        rule.setContent {
            LpTheme(darkOverride = UiPrefs.theme.value == "dark") {
                surface = MaterialTheme.colorScheme.surface
                Box(Modifier.size(44.dp).background(Lp.colors.bg)) {
                    GlassIcon(LpIcons.search, "搜索") { }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("标准控件表面不能透到错误底层", 1f, surface.alpha)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("浅色玻璃按钮仍为黑底", pixels[22, 5].luminance() > .7f)
        assertTrue("浅色玻璃按钮图标对比度不足", contrast(LightColors.fg, pixels[22, 5]) >= 4.5f)
        rule.onRoot().captureRoboImage("build/phone-theme/glass-light.png")
        rule.runOnIdle { UiPrefs.theme.value = "dark" }
        val dark = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("深色按钮没有切回深色", dark[22, 5].luminance() < .2f)
        assertEquals(1f, surface.alpha)
    }

    @Test fun primaryActionRemainsReadableInLightTheme() {
        rule.setContent {
            LpTheme(darkOverride = false) {
                PrimaryAction("播放", Modifier.size(240.dp, 48.dp).testTag("primary")) { }
            }
        }
        val pixels = rule.onNodeWithTag("primary").captureToImage().toPixelMap()
        val background = pixels[pixels.width / 2, 5]
        assertTrue("浅色主按钮中心的文字对比度不足", contrast(LightColors.mediaOnAccent, background) >= 4.5f)
    }

    @Test fun homeContentRendersBothPalettes() {
        checkMediaPage(Route.Home, FakeCore().loggedIn(), "home-content")
    }

    @Test fun libraryRendersBothPalettes() {
        checkMediaPage(Route.Library("lib-0", "电影"), FakeCore().loggedIn().library(), "library")
    }

    @Test fun detailRendersBothPalettes() {
        checkMediaPage(Route.Detail("m1", "Movie"), FakeCore().loggedIn().movie(), "detail")
    }

    private fun checkMediaPage(route: Any, core: FakeCore, name: String) {
        PageCache.clear()
        Wallpaper.set(null)
        FakeImages.install(ApplicationProvider.getApplicationContext())
        UiPrefs.theme.value = "light"
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = UiPrefs.theme.value == "dark") {
                CompositionLocalProvider(LocalApp provides app) {
                    Box(Modifier.fillMaxSize().background(Lp.colors.bg)) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = route) {
                            composable<Route.Home> { HomePage(nav) }
                            composable<Route.Library> { LibraryPage(nav, it) }
                            composable<Route.Detail> { DetailPage(nav, it) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val light = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("$name 浅色页底仍为深色", light[2, light.height - 2].luminance() > .5f)
        rule.onNodeWithText(if (name == "home-content") "继续观看" else "沙丘 2").assertExists()
        if (name == "library") {
            val label = rule.onNodeWithText("电影").captureToImage().toPixelMap()
            assertTrue("浅色媒体库标题区的文字对比度不足",
                contrast(LightColors.fg, label[label.width - 1, 0]) >= 4.5f)
        }
        rule.onRoot().captureRoboImage("build/phone-theme/$name-light.png")
        rule.runOnIdle { UiPrefs.theme.value = "dark" }
        val dark = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("$name 深色页底仍为浅色", dark[2, dark.height - 2].luminance() < .2f)
        rule.onRoot().captureRoboImage("build/phone-theme/$name-dark.png")
        rule.runOnIdle { UiPrefs.theme.value = "light" }
        val again = rule.onRoot().captureToImage().toPixelMap()
        assertTrue("$name 返回浅色未恢复", again[2, again.height - 2].luminance() > .5f)
    }

    private fun assertBackground(expected: Color) {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(expected, pixels[2, pixels.height / 2])
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
