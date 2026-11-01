package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.components.CardAction
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.pages.rememberCardActions
import xyz.linplayer.app.ui.theme.LpTheme

/** 列表热路径的稳定性回归：卡片参数跨重组复用，插件数据异常不让懒列表崩溃。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneListStabilityTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    /** 菜单是卡片参数，每次重组新建会让可见卡片全部无法跳过。 */
    @Test fun cardActionsSurviveRecomposition() {
        val app = AppState(FakeCore().loggedIn(), scope)
        val tick = mutableIntStateOf(0)
        val seen = mutableListOf<List<CardAction>>()
        rule.setContent {
            CompositionLocalProvider(LocalApp provides app) {
                tick.intValue
                seen += rememberCardActions(app, rememberCoroutineScope(), Item("m1", "影片", "Movie"))
            }
        }
        rule.runOnIdle { tick.intValue++ }
        rule.waitForIdle()
        assertEquals(2, seen.size)
        assertSame(seen[0], seen[1])
    }
}
