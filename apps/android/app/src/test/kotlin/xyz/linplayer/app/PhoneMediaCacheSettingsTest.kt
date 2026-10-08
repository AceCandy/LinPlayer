package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.theme.LpTheme
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneMediaCacheSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun cleanup() { scope.cancel() }

    @Test fun clearCacheAlsoClearsDetailMetadata() {
        val core = FakeCore().apply {
            ret("system.cacheSize", buildJsonObject { put("bytes", 0) })
            ret("system.clearCache", buildJsonObject { put("bytes", 0) })
        }
        val app = AppState(core, scope)
        val key = app.detailCache.key("server", "user", "item")
        runBlocking {
            app.detailCache.put(key, buildJsonObject { put("id", "item"); put("name", "缓存片名") },
                app.detailCache.generation)
        }
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.SettingsSub("storage")) {
                    composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                }
            } }
        }
        rule.onNodeWithText("清理缓存").performScrollTo().performClick()
        rule.waitUntil(5000) { core.calls.any { it.first == "system.clearCache" } }
        assertNull(app.detailCache.peek(key))
        assertEquals(1, core.calls.count { it.first == "system.clearCache" })
    }

    @Test fun cacheCapacityPersistsAndFailedSaveRollsBack() {
        val core = FakeCore()
        var saved = 1024L shl 20
        var reject = false
        core.on("prefs.getPrefetchSettings") { buildJsonObject { put("media_cache_bytes", saved) } }
        core.on("prefs.setPrefetchSettings") {
            if (reject) throw CoreException("E_INTERNAL", "保存失败", false)
            saved = ((it!!["settings"] as JsonObject)["media_cache_bytes"] as JsonPrimitive).content.toLong()
            buildJsonObject { put("media_cache_bytes", saved) }
        }
        core.ret("system.dataPaths", buildJsonObject { put("root", "应用私有目录") })
        core.ret("system.cacheSize", buildJsonObject { put("bytes", 0) })
        val app = AppState(core, scope)
        rule.setContent {
            LpTheme { CompositionLocalProvider(LocalApp provides app) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = Route.SettingsSub("storage")) {
                    composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                }
            } }
        }
        rule.onNodeWithText("1024 MiB").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/phone-theme/media-cache.png")
        rule.onNodeWithContentDescription("保留媒体缓存").performClick()
        rule.runOnIdle { assertEquals(0L, saved) }
        rule.onNodeWithContentDescription("保留媒体缓存").performClick()
        rule.runOnIdle { assertEquals(1024L shl 20, saved); reject = true }
        rule.onNodeWithContentDescription("保留媒体缓存").performClick()
        rule.onNodeWithText("1024 MiB").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1024L shl 20, saved) }
    }
}
