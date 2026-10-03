package xyz.linplayer.app

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.player.PlaybackService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceTest {
    @Test fun 通知停止使用当前进度并调用核心停播() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(Dispatchers.Main)
        val core = FakeCore().apply {
            ret("player.status", buildJsonObject {
                put("position", 37.5); put("duration", 100); put("paused", true)
            })
            ret("player.stopPlayback", JsonPrimitive(true))
        }
        val app = AppState(core, scope)
        val ctx = RuntimeEnvironment.getApplication()
        PlaybackService.start(ctx, app, null, "测试影片")
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(ctx, PlaybackService::class.java).setAction("playback.stop"), 0, 1)
            val stop = core.calls.single { it.first == "player.stopPlayback" }
            assertEquals(37.5, stop.second.dbl("pos")!!, 0.0)
        } finally {
            controller.destroy()
            app.bg.cancel(); scope.cancel(); Dispatchers.resetMain()
        }
    }
}
