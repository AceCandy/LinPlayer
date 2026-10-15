package xyz.linplayer.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.player
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.player.PlaybackService

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PlayerActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var host: ActivityController<TestPlayerActivity>? = null

    @After fun cleanup() {
        host?.pause()?.stop()?.destroy()
        PlaybackService.stop(RuntimeEnvironment.getApplication())
        TestPlayerActivity.state?.bg?.cancel()
        TestPlayerActivity.state = null
        scope.cancel()
    }

    @Test fun manifestChoosesDirectionBeforePlayerCreationAndKeepsAllParameters() {
        val context = RuntimeEnvironment.getApplication()
        val route = Route.Player("m1", "标题", "v2", "exo", 16f / 9,
            src = "{\"item\":\"source-item\"}", fromStart = true,
            subtitle = "{\"sourceId\":\"v2\",\"index\":4}")
        for ((ratio, target, direction) in listOf(
            Triple(16f / 9, PlayerActivity::class.java, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
            Triple(9f / 16, PortraitPlayerActivity::class.java, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
            Triple(0f, PlayerActivity::class.java, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        )) {
            val expected = route.copy(ar = ratio)
            val intent = PlayerActivity.intent(context, expected, 42)
            assertEquals(ComponentName(context, target), intent.component)
            assertEquals(expected, PlayerActivity.readRoute(intent))
            val info = context.packageManager.getActivityInfo(intent.component!!, 0)
            assertFalse(info.exported)
            assertEquals(direction, info.screenOrientation)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
            assertTrue(org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(info, "supportsPictureInPicture"))
        }
    }

    @Test fun privatePlaybackIntentCannotReopenAfterProcessUnlockIsLost() {
        val core = FakeCore().loggedIn().player()
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        val intent = PlayerActivity.intent(context, Route.Player("m1", "标题"), privateSpace = true)
            .setClass(context, TestPlayerActivity::class.java)
        host = Robolectric.buildActivity(TestPlayerActivity::class.java, intent).create()
        assertTrue(host!!.get().isFinishing)
        assertFalse(core.calls.any { it.first == "player.play" })
    }

    @Test fun staleNotificationDoesNotStartAnotherPlayback() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(PlayerActivity::class.java,
            Intent(context, PlayerActivity::class.java)).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun standalonePlayerKeepsPlaybackOnNotificationReturnAndFinishesWithoutUnlocking() {
        val core = FakeCore().loggedIn().player().apply { ret("player.stopPlayback", JsonNull) }
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        TestPlayerActivity.state = app
        val context = RuntimeEnvironment.getApplication()
        val intent = PlayerActivity.intent(context,
            Route.Player("m1", "测试", "v2", "mpv", 16f / 9, fromStart = true))
            .setClass(context, TestPlayerActivity::class.java)
        host = Robolectric.buildActivity(TestPlayerActivity::class.java, intent).setup()
        rule.waitUntil(5_000) { core.calls.any { it.first == "player.play" } }
        rule.waitForIdle()
        val activity = host!!.get()
        val play = core.calls.single { it.first == "player.play" }.second
        assertEquals("v2", play.str("media_source_id"))
        assertEquals("mpv", play.str("engine"))
        assertTrue(play.bool("from_start"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)

        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            service.get().onStartCommand(Intent(context, PlaybackService::class.java), 0, 1)
            val notification = shadowOf(service.get()).lastForegroundNotification
            val resume = shadowOf(notification.contentIntent).savedIntent
            assertEquals(activity.componentName, resume.component)
            assertNull("通知只能唤回现有窗口，不能用旧路由重新起播", PlayerActivity.readRoute(resume))
            assertTrue(resume.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
            host!!.newIntent(resume)
            rule.waitForIdle()
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertFalse(core.calls.any { it.first == "player.stopPlayback" })
            rule.onRoot().captureRoboImage("build/player-activity/landscape.png")
            rule.onNodeWithContentDescription("切换方向").performClick()
            rule.runOnIdle {
                RuntimeEnvironment.setQualifiers("w440dp-h960dp-port-mdpi")
                val config = android.content.res.Configuration(activity.resources.configuration)
                activity.onConfigurationChanged(config)
                activity.window.setLayout(440, 960)
                val decor = activity.window.decorView
                decor.dispatchConfigurationChanged(config)
                decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(440, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(960, android.view.View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 440, 960)
            }
            rule.waitForIdle()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
            assertEquals(1, core.calls.count { it.first == "player.play" })
            assertFalse(core.calls.any { it.first == "player.stopPlayback" })
            val portraitBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("配置与窗口必须同时变成竖屏", portraitBounds.height > portraitBounds.width)
            rule.onRoot().captureRoboImage("build/player-activity/portrait.png")

            rule.runOnIdle {
                core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("plugin.shellRequest",
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(72))
                        put("op", kotlinx.serialization.json.JsonPrimitive("ui.confirm"))
                        put("args", kotlinx.serialization.json.buildJsonObject {
                            put("title", kotlinx.serialization.json.JsonPrimitive("播放中确认"))
                        })
                    }))
            }
            rule.onNodeWithText("播放中确认").assertIsDisplayed()
            rule.onNodeWithText("确定").performClick()
            rule.waitUntil { core.calls.any { it.first == "plugin.shellResult" } }
            assertTrue(core.calls.single { it.first == "plugin.shellResult" }.second.bool("data"))
            rule.onNodeWithContentDescription("返回").performClick()
            rule.waitUntil { activity.isFinishing }
            assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertEquals("m1", shadowOf(activity).resultIntent.getStringExtra(PlayerActivity.PLAYED_ITEM))
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
            host!!.pause().stop().destroy()
            host = null
            rule.waitUntil { core.calls.any { it.first == "player.stopPlayback" } }
            assertEquals(1, core.calls.count { it.first == "player.stopPlayback" })
        } finally { service.destroy() }
    }
}

class TestPlayerActivity : PlayerActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(R.style.Theme_LinPlayer_Player)
        super.onCreate(savedInstanceState)
    }
    override fun playerApp(): AppState = requireNotNull(state)
    companion object { var state: AppState? = null }
}
