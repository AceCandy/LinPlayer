package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.player.*
import xyz.linplayer.app.ui.theme.LpTheme

/** 平板横屏下播放器控制层与各弹出菜单的截图基线：菜单贴着入口、宽度不随屏宽拉伸。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi", sdk = [36], application = Application::class)
class PhonePlayerTabletTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel(); SubStyle.loaded.value = false }

    private fun core() = FakeCore().loggedIn().apply {
        ret("player.tracks", arr(
            buildJsonObject { put("id", "1"); put("kind", "audio"); put("lang", "jpn"); put("title", "原声"); put("selected", true) },
            buildJsonObject { put("id", "2"); put("kind", "audio"); put("lang", "chi") },
            buildJsonObject { put("id", "3"); put("kind", "sub"); put("lang", "zh-Hans"); put("codec", "ass"); put("selected", true) }))
        ret("emby.itemDetail", buildJsonObject { put("series_id", "series"); put("season_id", "s1"); put("season_no", 1) })
        ret("emby.seasonEpisodes", buildJsonObject {
            put("total", 8)
            put("items", buildJsonArray { (1..8).forEach { add(item("e$it", "第 $it 集", "Episode", season = 1, episode = it, runtime = 2700.0)) } })
        })
    }

    @Test fun osdAndMenusOnTablet() {
        val app = AppState(core(), scope)
        runBlocking { app.boot() }
        val panel = mutableStateOf<String?>(null)
        val entrance = mutableStateOf("more")
        rule.setContent {
            LpTheme(darkOverride = true) { CompositionLocalProvider(LocalApp provides app) {
                val anchors = remember { mutableStateMapOf<String, Rect>() }
                Box(Modifier.fillMaxSize().background(Color(0xFF31535E))) {
                    Osd(false, "", title = "测试片源", episodeLabel = "S1E1 第 1 集", position = 600.0,
                        duration = 2700.0, paused = true, speed = 1.0, heat = emptyList(),
                        onBack = {}, onToggle = {}, onSeek = {}, onSpeed = {}, onLock = {}, onShot = {},
                        onPanel = { entrance.value = it; panel.value = it }, onPanelAnchor = { k, r -> anchors[k] = r },
                        onOrientation = {}, onChapters = {}, onPip = {})
                    PlayerPanel(panel.value, "e1", anchor = anchors[entrance.value], speed = 1.0,
                        onSpeed = {}, onOpen = { panel.value = it }, onClose = { panel.value = null })
                }
            } }
        }
        rule.waitForIdle()
        captureScreenRoboImage("build/player-tablet/osd.png")
        for (kind in listOf("音轨", "字幕", "更多")) {
            rule.onNodeWithContentDescription(kind).performClick()
            rule.waitForIdle()
            // 菜单是固定刻度的小浮层，平板上不得拉成大面板
            assertTrue(rule.onNodeWithTag("player.menu").fetchSemanticsNode().boundsInRoot.width < 320f)
            captureScreenRoboImage("build/player-tablet/menu-$kind.png")
            rule.runOnIdle { panel.value = null }
            rule.waitForIdle()
        }
        rule.runOnIdle { entrance.value = "more"; panel.value = "episodes" }
        rule.waitUntil(5000) { rule.onAllNodesWithText("第 3 集", substring = true).fetchSemanticsNodes().isNotEmpty() }
        captureScreenRoboImage("build/player-tablet/menu-episodes.png")
    }
}
