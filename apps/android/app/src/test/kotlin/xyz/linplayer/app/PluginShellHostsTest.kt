package xyz.linplayer.app

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.plugin.PluginDialogs
import xyz.linplayer.app.plugin.PluginShell
import xyz.linplayer.app.plugin.WebShell
import xyz.linplayer.app.tv.FakeCore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginShellHostsTest {
    @Test fun twoWindowsDispatchEachRequestOnceAndOwnTheirWebViews() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val main = Robolectric.buildActivity(Activity::class.java).setup()
        val player = Robolectric.buildActivity(Activity::class.java).setup()
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val core = FakeCore()
        val app = AppState(core, mainScope)
        try {
            PluginShell.start(main.get(), app, mainScope)
            PluginShell.start(player.get(), app, playerScope)
            fun request(id: Int) {
                core.events.tryEmit(CoreEvent("plugin.shellRequest", buildJsonObject {
                    put("id", id); put("op", "ui.confirm"); put("args", JsonObject(emptyMap()))
                }))
                requireNotNull(PluginDialogs.current.value).reply(JsonPrimitive(true))
            }
            request(1)
            assertEquals(1, core.calls.count { it.first == "plugin.shellResult" })
            PluginShell.activate(main.get())
            request(2)
            assertEquals(2, core.calls.count { it.first == "plugin.shellResult" })

            val args = buildJsonObject { put("url", "about:blank") }
            val first = mainScope.launch { WebShell.open(main.get(), args) }
            val second = playerScope.launch { WebShell.open(player.get(), args) }
            fun webCount(view: android.view.View): Int = if (view is ViewGroup)
                (0 until view.childCount).sumOf { webCount(view.getChildAt(it)) } + if (view is WebView) 1 else 0
                else if (view is WebView) 1 else 0
            assertEquals(1, webCount(main.get().window.decorView))
            assertEquals(1, webCount(player.get().window.decorView))
            first.cancel()
            assertEquals(0, webCount(main.get().window.decorView))
            assertEquals(1, webCount(player.get().window.decorView))
            second.cancel()
            assertEquals(0, webCount(player.get().window.decorView))
            PluginShell.activate(player.get())
            PluginShell.release(player.get())
            playerScope.cancel()
            request(3)
            assertEquals("播放器关闭、浏览窗口尚未恢复时仍能接收一次请求", 3,
                core.calls.count { it.first == "plugin.shellResult" })
        } finally {
            mainScope.cancel(); playerScope.cancel(); app.bg.cancel()
            player.pause().stop().destroy(); main.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }
}
