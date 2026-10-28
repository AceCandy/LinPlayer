package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache

/**
 * 真页面逐张出图(UI_TV.md §11.3),和草稿同一张画布:`Television1080p`,1920×1080。
 *
 * `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*TvPageShots*'`
 * → `app/build/tvpages/<草稿序号>-<名字>.png`。截图不进仓库;对照脚本把同序号的草稿拼在左边。
 *
 * ★ 只替掉核心层([FakeCore]),页面、焦点、面板、返回栈全是真代码;按键走 `performKeyInput`,和遥控器同一条分发路径。
 * ★ 草稿里的 03 / 04 / 05 / 36(组件表、系统态拼图)没有对应页面:它们画的就是 tv/kit 本身,
 *   由 [xyz.linplayer.app.TvDraftShots] 的逐像素回归守着(组件库挪进正式包前后 45 张图一致)。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvPageShots(private val name: String) {
    @get:Rule val rule = createComposeRule()

    class Case(
        val draft: Int, val name: String, val setup: FakeCore.() -> Unit = {},
        val waitMs: Long = 800, val after: (ComposeContentTestRule, FakeCore) -> Unit = { _, _ -> },
        /** 按完键到截图之间再等多久;预览游标 700ms 就提交,要拍它就不能等。 */
        val settle: Long = 600, val before: () -> Unit = {},
        val content: @Composable () -> Unit,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test fun shot() {
        val c = cases.first { it.name == name }
        val core = FakeCore().loggedIn().apply(c.setup)
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        c.before()
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { c.content() } }
        advance(rule, c.waitMs)
        c.after(rule, core)
        advance(rule, c.settle)
        rule.onRoot().captureRoboImage("build/tvpages/%02d-%s.png".format(c.draft, c.name))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = cases.map { arrayOf<Any>(it.name) }
    }
}

internal fun advance(rule: ComposeContentTestRule, ms: Long) {
    var left = ms
    while (left > 0) { rule.mainClock.advanceTimeBy(50); left -= 50 }
}

@OptIn(ExperimentalTestApi::class)
internal fun press(rule: ComposeContentTestRule, vararg keys: Any) {
    // Key 是 value class,不许直接做 vararg;装箱传进来再拆
    keys.map { it as Key }.forEach { k ->
        rule.onRoot().performKeyInput { pressKey(k) }
        // 按键回调里写的状态要等 GlobalSnapshotManager 投递到主线程才生效;时钟手动推进时这一步不会自己发生
        Snapshot.sendApplyNotifications()
        advance(rule, 250)
    }
}

/** 测试用播放页宿主:栈与 Local 同 [TvPlaybackHost];不给 onExit,退出就是 nav.pop()。 */
@Composable
internal fun PlayerHost(nav: TvNav) {
    val top = nav.top
    val r = top.route as? TvRoute.Player ?: return
    key(top.id) {
        CompositionLocalProvider(LocalNav provides nav, LocalFocusMemory provides top.memory) { TvPlayerPage(r) }
    }
}

@Composable
internal fun Shell(route: TvRoute.Player) {
    PlayerHost(remember { TvNav().apply { push(route) } })
}

/** 播放页的状态事件:时间真的往前走 → 撤黑幕。 */
internal fun FakeCore.tick(pos: Double, eof: Boolean = false) {
    events.tryEmit(CoreEvent("player.status", buildJsonObject {
        put("position", pos); put("duration", 3660.0); put("paused", false); put("buffering", false); put("eof", eof)
    }))
}

private fun playing(rule: ComposeContentTestRule, core: FakeCore) {
    listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
}

private val playerRoute = TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")

internal val cases = listOf(
    TvPageShots.Case(6, "player-osd", { player() }, after = { r, c -> playing(r, c) }) { Shell(playerRoute) },
    TvPageShots.Case(7, "player-seek", { player() }, after = { r, c -> playing(r, c); press(r, Key.DirectionRight, Key.DirectionRight) }, settle = 300) { Shell(playerRoute) },
    TvPageShots.Case(8, "player-subtitles", { player() }, after = { r, c ->
        playing(r, c); press(r, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.Enter)
    }) { Shell(playerRoute) },
    TvPageShots.Case(9, "player-more", { player() }, after = { r, c -> playing(r, c); press(r, Key.Menu) }) { Shell(playerRoute) },
    TvPageShots.Case(11, "player-nextup", { player() }, after = { r, c -> playing(r, c); c.tick(3645.0); advance(r, 100); c.tick(3645.25); advance(r, 5600) }) { Shell(playerRoute) },
    TvPageShots.Case(12, "player-failed", { player(failPlay = true) }) { Shell(playerRoute) },
)
