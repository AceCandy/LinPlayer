package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneDetailOptionsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private val versionName = "2160p · 导演剪辑版 · 高动态范围收藏版"
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun stream(type: String, lang: String, title: String) = buildJsonObject {
        put("type_", type); put("language", lang); put("title", title); put("codec", "aac")
    }
    private fun core(preferred: Boolean = true) = FakeCore().loggedIn().movie().apply {
        ret("emby.itemMedia", arr(*(1..2).map { n -> buildJsonObject {
            put("id", "v$n"); put("name", if (n == 1) versionName else "1080p 标准版")
            put("preferred", preferred && n == 1); put("container", "mkv"); put("size_bytes", 10000000000L)
            put("streams", arr(
                stream("Audio", "jpn", "日语原声 · 多声道音轨"),
                stream("Audio", "eng", "英语配音 · 多声道音轨"),
                stream("Subtitle", "chi", "简体中文特效字幕 · 完整对白与注释"),
                stream("Subtitle", "eng", "英文字幕"),
            ))
        } }.toTypedArray()))
        ret("prefs.getPrefs", buildJsonObject { put("audio_lang", "jpn"); put("sub_lang", "chi") })
        ret("prefs.setPrefs", JsonNull)
    }
    private fun open(core: FakeCore) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Route.Detail("m1", "Movie")) {
                        composable<Route.Detail> { DetailPage(nav, it) }
                        composable<Route.Player> {
                            val r = it.toRoute<Route.Player>()
                            Text("播放目标：${r.itemId} / ${r.versionId ?: "自动"}")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    private fun option(label: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(label) and hasClickAction())
        return rule.onNode(hasText(label) and hasClickAction()).performScrollTo()
    }
    private fun dialogShot(name: String) = rule.onNode(isDialog()).captureRoboImage("build/detail-options/$name.png")

    @Test fun versionDialogAndManualSelectionKeepPlaybackTarget() {
        open(core())
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("继续观看", substring = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        rule.onRoot().captureRoboImage("build/detail-options/movie-light-large.png")
        val layout = layouts.single()
        assertFalse("大字号续播按钮不能省略剩余时长",
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        option("版本").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/detail-options/options-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/detail-options/options-dark-large.png")
        option("版本").performClick()
        rule.onNodeWithText("选择版本").assertIsDisplayed()
        rule.onNodeWithText("正则选中").assertIsDisplayed()
        dialogShot("version-dark-large")
        rule.runOnIdle { dark.value = false }
        dialogShot("version-light-large")
        rule.onNodeWithText("1080p 标准版").performClick()
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        rule.onNodeWithText("继续观看", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("播放目标：m1 / v2").assertIsDisplayed()
    }

    @Test fun languageDialogsPreserveOtherPreference() {
        val core = core()
        open(core)
        option("音轨").performClick()
        dialogShot("audio-light-large")
        rule.onNode(hasText("英语") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        var saved = core.calls.last { it.first == "prefs.setPrefs" }.second
        assertEquals("eng", saved.str("audio_lang")); assertEquals("chi", saved.str("sub_lang"))
        option("字幕").performClick()
        rule.onNode(hasText("简体中文特效字幕 · 完整对白与注释") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        dialogShot("subtitle-light-large")
        rule.runOnIdle { dark.value = true }
        dialogShot("subtitle-dark-large")
        rule.onNodeWithText("不显示字幕").performClick()
        saved = core.calls.last { it.first == "prefs.setPrefs" }.second
        assertEquals("eng", saved.str("audio_lang")); assertEquals("", saved.str("sub_lang"))
        assertEquals("false", saved?.get("sub_enabled").toString())
    }

    @Test fun displayFallbackDoesNotForceFirstVersion() {
        open(core(preferred = false))
        rule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        rule.onNodeWithText("继续观看", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("播放目标：m1 / 自动").assertIsDisplayed()
    }
}
