package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.pages.DanmakuSettingsPanel
import xyz.linplayer.app.ui.player.*
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h850dp-mdpi", sdk = [36], application = Application::class)
class DanmakuSourcesTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel() }

    @Test fun failedSourceTestKeepsDraftAndDoesNotSave() {
        val core = FakeCore().loggedIn().apply {
            ret("danmaku.getOfficialDanmaku", buildJsonObject { put("enabled", false) })
            ret("danmaku.getDanmakuConfig", buildJsonArray {
                add(buildJsonObject { put("id", "official"); put("name", "官方源"); put("official", true) })
            })
            ret("danmaku.getBlockwords", buildJsonObject { put("words", JsonArray(emptyList())); put("users", JsonArray(emptyList())) })
            on("danmaku.testSource") { throw CoreException("E_UPSTREAM", "测试源不可达", true) }
        }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent { LpTheme { CompositionLocalProvider(LocalApp provides app) { DanmakuSettingsPanel() } } }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("删除").assertCountEquals(0)
        rule.onNodeWithText("添加源").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("测试源")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("https://danmaku.invalid")
        rule.onNodeWithText("测试并保存").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("测试源", substring = false).assertIsDisplayed()
        assertFalse(core.calls.any { it.first == "danmaku.setDanmakuConfig" })
        rule.onRoot().captureRoboImage("build/danmaku/source-test-failure.png")
    }

    @Test
    @Config(qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun tvOfficialSourceCanMoveAndToggleWithRemote() {
        var sources = buildJsonArray {
            add(buildJsonObject { put("id", "official"); put("name", "官方源"); put("official", true) })
            add(buildJsonObject { put("id", "custom"); put("name", "自建源") })
        }
        val core = FakeCore().loggedIn().apply {
            on("danmaku.getDanmakuConfig") { sources }
            on("danmaku.setDanmakuConfig") { a -> sources = a!!["sources"] as JsonArray; JsonNull }
        }
        val app = AppState(core, scope); runBlocking { app.boot() }
        rule.setContent { TvFrame(app) { Box(Modifier.fillMaxSize()) { DanmakuSourcesPanel {} } } }
        rule.waitForIdle()
        rule.onNodeWithText("官方源").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { keyDown(androidx.compose.ui.input.key.Key.Enter); keyUp(androidx.compose.ui.input.key.Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithText("删除").assertDoesNotExist()
        rule.onNodeWithText("下移").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { keyDown(androidx.compose.ui.input.key.Key.Enter); keyUp(androidx.compose.ui.input.key.Key.Enter) }
        rule.waitForIdle()
        assertEquals("official", sources.last().obj().str("id"))
        rule.onNodeWithText("启用").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { keyDown(androidx.compose.ui.input.key.Key.Enter); keyUp(androidx.compose.ui.input.key.Key.Enter) }
        rule.waitForIdle()
        assertTrue(sources.last().obj().bool("disabled"))
        rule.onRoot().captureRoboImage("build/danmaku/tv-source-actions.png")
    }

    @Test fun lateAutomaticResultCannotOverwriteManualChoice() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeCore().loggedIn().apply {
            ret("emby.itemDetail", buildJsonObject { put("name", "作品"); put("series_id", "series"); put("season_id", "season"); put("episode_no", 1) })
            var generation = 0
            on("player.danmakuBegin") { buildJsonObject { put("generation", ++generation) } }
            ret("player.danmakuSet", JsonNull)
            ret("player.setDanmakuEnabled", JsonNull)
            ret("player.danmakuLayout", JsonNull)
        }
        val port = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command != "danmaku.resolve") return fake.callJson(command, args, onPartial)
                val manual = args?.get("selection") != null
                if (!manual) { waiting.complete(Unit); withContext(NonCancellable) { release.await() } }
                return buildJsonObject {
                    put("items", buildJsonArray { add(buildJsonObject { put("text", if (manual) "手选" else "自动") }) })
                    put("match", buildJsonObject { put("source_name", if (manual) "手选源" else "自动源") })
                }
            }
        }
        val app = AppState(port, scope); app.boot()
        val old = launch { resolveDanmakuFor(app, "episode") }
        waiting.await()
        val selected = buildJsonObject { put("source_id", "s"); put("episode_id", "manual") }
        assertTrue(resolveDanmakuFor(app, "episode", selected))
        release.complete(Unit); old.join()
        assertEquals("手选源", DanmakuStyle.source.value)
        assertEquals(1, fake.calls.count { it.first == "player.danmakuSet" })
    }
}
