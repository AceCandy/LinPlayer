package xyz.linplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.linplayer.app.ui.player.TrackCandidate
import xyz.linplayer.app.ui.player.preferredTrackIndex

class TrackPrefsTest {
    private val tracks = listOf(TrackCandidate("English", "eng", true), TrackCandidate("日语", "jpn", false), TrackCandidate("简体", "chi", false))
    @Test fun regexPrecedesLanguageAndInvalidRegexFallsBack() {
        assertEquals(2, preferredTrackIndex(tracks, "jpn", "简体|chi", false))
        assertEquals(1, preferredTrackIndex(tracks, "JPN", "[", false))
        assertEquals(-1, preferredTrackIndex(tracks, null, "", false))
        assertEquals(-1, preferredTrackIndex(tracks, null, "", true))
        assertEquals(0, preferredTrackIndex(tracks.map { it.copy(selected = false) }, null, "", true))
        assertEquals(-1, preferredTrackIndex(emptyList(), null, "", true))
    }
}
