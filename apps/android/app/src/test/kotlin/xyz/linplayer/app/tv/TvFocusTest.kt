package xyz.linplayer.app.tv

import org.junit.Assert.assertFalse
import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache

/**
 * 焦点路径断言(UI_TV.md §11.3)。☠ 焦点问题在静态截图上**完全看不出来** —— 出图之外必须有按键驱动的断言。
 * 按键走 `performKeyInput`,返回走 OnBackPressedDispatcher —— 和遥控器落到的是同一组处理。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvFocusTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var app: AppState
    private lateinit var core: FakeCore
    private lateinit var nav: TvNav
    private lateinit var back: OnBackPressedDispatcher

    @Before fun boot() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
    }

    private fun mount(route: TvRoute.Player, intercept: ((FakeCore) -> xyz.linplayer.app.core.CorePort)? = null, setup: FakeCore.() -> Unit = {}) {
        core = FakeCore().loggedIn().apply(setup)
        app = AppState(intercept?.invoke(core) ?: core, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        runBlocking { app.boot() }
        nav = TvNav().apply { push(route) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvFrame(app) { PlayerHost(nav) }
        }
        advance(rule, 800)
    }

    private fun focused(tag: String) = rule.onNode(hasTestTag(tag)).assertIsFocused()
    private fun pressBack() { rule.runOnUiThread { back.onBackPressed() }; advance(rule, 300) }

    @Test fun 播放页OSD收起后方向键只唤醒不移动焦点() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        focused("osd.play")
        advance(rule, 5600)
        rule.onNode(hasText("字幕")).assertDoesNotExist()
        focused("player.root")
        press(rule, Key.DirectionRight)
        rule.onNode(hasText("字幕")).assertExists()
        // 唤醒那一下不许把焦点挪到快进上:焦点回到播放/暂停
        focused("osd.play")
    }

    @Test fun 分集加载失败不能误判季末退出且可以重试连播() {
        var fail = true
        mount(TvRoute.Player("sh6", "第六集")) {
            player()
            val response = handlers.getValue("emby.playbackSeason")
            on("emby.playbackSeason") { a ->
                if (fail) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分集加载失败", true)
                response(a)
            }
        }
        core.tick(3658.0); advance(rule, 300)
        core.tick(3658.25); advance(rule, 300)
        core.tick(3660.0, eof = true); advance(rule, 600)
        assertTrue("加载失败不能退出播放页", nav.top.route is TvRoute.Player)
        rule.onNode(hasText("无法加载下一集", substring = true)).assertExists()
        fail = false
        rule.onNode(hasText("重试")).assertIsFocused()
        press(rule, Key.Enter)
        advance(rule, 1200)
        assertTrue(core.calls.count { it.first == "emby.playbackSeason" } >= 2)
        assertEquals("sh7", lastPlayed())
    }

    private fun lastPlayed() = (core.calls.last { it.first == "player.play" }.second?.get("item_id") as kotlinx.serialization.json.JsonPrimitive).content

    @Test fun 播完直接连续播放三集() {
        mount(TvRoute.Player("sh6", "第六集")) {
            player(); ret("player.stopPlayback", kotlinx.serialization.json.JsonNull)
        }
        for (next in listOf("sh7", "sh8")) {
            core.tick(3658.0); advance(rule, 300)
            core.tick(3658.25); advance(rule, 300)
            core.tick(3660.0, eof = true); advance(rule, 1200)
            assertEquals(next, lastPlayed())
            assertTrue(core.calls.indexOfLast { it.first == "player.stopPlayback" } < core.calls.indexOfLast { it.first == "player.play" })
        }
        assertEquals(3, core.calls.count { it.first == "player.play" })
    }

    @Test fun 完播等待慢分集请求返回后才切下一集() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            mount(TvRoute.Player("sh6", "第六集"), intercept = { fake ->
                object : xyz.linplayer.app.core.CorePort by fake {
                    override suspend fun callJson(command: String, args: kotlinx.serialization.json.JsonObject?,
                        onPartial: ((kotlinx.serialization.json.JsonElement) -> Unit)?): kotlinx.serialization.json.JsonElement {
                        val result = fake.callJson(command, args, onPartial)
                        if (command == "emby.playbackSeason") release.await()
                        return result
                    }
                }
            }) { player() }
            core.tick(3658.0); advance(rule, 300)
            core.tick(3658.25); advance(rule, 300)
            core.tick(3660.0, eof = true); advance(rule, 600)
            assertTrue(nav.top.route is TvRoute.Player)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 1200)
            assertEquals("sh7", lastPlayed())
        } finally { release.complete(Unit) }
    }

    @Test fun 片尾胶囊在控件收起时按确认播下一集() {
        mount(TvRoute.Player("sh6", "第六集")) { player() }
        core.tick(3645.0); advance(rule, 300)
        core.tick(3645.25); advance(rule, 300)
        advance(rule, 5600)
        focused("player.root")
        rule.onNode(hasTestTag("player.nextUp")).assertExists()
        assertEquals(1, core.calls.count { it.first == "player.play" })
        press(rule, Key.Enter)
        advance(rule, 600)
        assertEquals("sh7", lastPlayed())
    }

    @Test fun 核心返回当前季末时退出而不跨季() {
        mount(TvRoute.Player("sh6", "第六集")) {
            player()
            ret("emby.playbackSeason", arr(item("sh6", "季末", "Episode", season = 1, episode = 6)))
        }
        core.tick(3658.0); advance(rule, 300)
        core.tick(3658.25); advance(rule, 300)
        core.tick(3660.0, eof = true); advance(rule, 600)
        assertFalse(nav.top.route is TvRoute.Player)
        assertEquals(1, core.calls.count { it.first == "player.play" })
    }

    @Test fun 自动内核发送实际Media3且设置变化不重启当前片() {
        val previous = xyz.linplayer.app.data.UiPrefs.engine.value
        try {
            xyz.linplayer.app.data.UiPrefs.engine.value = "auto"
            mount(TvRoute.Player("sh6", "测试")) { player() }
            val plays = core.calls.filter { it.first == "player.play" }
            assertEquals("\"exo\"", plays.single().second?.get("engine").toString())
            rule.runOnIdle { xyz.linplayer.app.data.UiPrefs.engine.value = "mpv" }
            advance(rule, 600)
            assertEquals(1, core.calls.count { it.first == "player.play" })
        } finally { xyz.linplayer.app.data.UiPrefs.engine.value = previous }
    }

    @Test fun 自动偏好下显式手动内核仍优先() {
        val previous = xyz.linplayer.app.data.UiPrefs.engine.value
        try {
            xyz.linplayer.app.data.UiPrefs.engine.value = "auto"
            mount(TvRoute.Player("sh6", "测试", engine = "mpv")) { player() }
            assertEquals("\"mpv\"", core.calls.single { it.first == "player.play" }.second?.get("engine").toString())
        } finally { xyz.linplayer.app.data.UiPrefs.engine.value = previous }
    }

    @Test fun 播放页OSD收起时菜单键和媒体键都有反应() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        advance(rule, 5600)
        rule.onNode(hasText("字幕")).assertDoesNotExist()
        press(rule, Key.MediaPlayPause)
        assertTrue("播放/暂停媒体键没发到核心层", core.calls.any { it.first == "player.setPause" })
        rule.onNode(hasText("字幕")).assertDoesNotExist()
        press(rule, Key.Menu)
        rule.onNode(hasText("画面比例")).assertExists()
    }

    @Test fun 播放页返回键先收OSD再按两次才退出() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        pressBack()
        assertEquals("OSD 显示时返回 = 收起 OSD,不退页", 2, nav.stack.size)
        pressBack()
        assertEquals("第一次返回只唤出 OSD 并提示", 2, nav.stack.size)
        pressBack()
        assertEquals("2 秒内再按才退出", 1, nav.stack.size)
    }
}
