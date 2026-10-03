package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.player.TrackPrefs
import xyz.linplayer.app.ui.player.rememberExoPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExoPrefsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun 字幕关闭偏好必须禁用Exo文本轨() {
        val prefs = mutableStateOf<TrackPrefs?>(null)
        var player: ExoPlayer? = null
        rule.setContent { player = rememberExoPlayer(true, prefs.value) }
        rule.runOnIdle { prefs.value = TrackPrefs(subEnabled = false) }
        rule.runOnIdle { assertTrue(C.TRACK_TYPE_TEXT in player!!.trackSelectionParameters.disabledTrackTypes) }
        rule.runOnIdle { prefs.value = TrackPrefs(subEnabled = true) }
        rule.runOnIdle { assertFalse(C.TRACK_TYPE_TEXT in player!!.trackSelectionParameters.disabledTrackTypes) }
    }
}
