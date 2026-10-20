package xyz.linplayer.app

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.ui.player.TrackCandidate
import xyz.linplayer.app.ui.player.preferredTrackIndex

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TrackPrefsTest {
    @Test fun coreDecisionMapsToNativeCandidateAndKeepMeansNoOverride() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = FakeCore()
        val app = AppState(core, scope)
        try {
            core.ret("player.selectTrackPreferences", buildJsonObject { put("sub", "1"); put("audio", "") })
            val tracks = listOf(TrackCandidate("English", "eng", true), TrackCandidate("简体", "chi", false))
            assertEquals(1, app.preferredTrackIndex(tracks, "chi", "简体", true))
            val args = core.calls.last().second!!
            assertEquals("chi", args["prefs"].obj().str("sub_lang"))
            assertEquals("简体", args["prefs"].obj().str("sub_regex"))
            assertEquals("sub", args["tracks"].arr()[1].obj().str("kind"))
            assertEquals(-1, app.preferredTrackIndex(tracks, null, "", false))
        } finally { scope.cancel(); app.bg.cancel() }
    }
}
