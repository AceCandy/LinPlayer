package xyz.linplayer.app

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import org.robolectric.RuntimeEnvironment
import xyz.linplayer.app.ui.player.syncLibassTrack
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.core.Native
import xyz.linplayer.app.ui.player.Libass

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [ShadowAssNative::class],
    instrumentedPackages = ["xyz.linplayer.app.core"])
class LibassSelectionTest {
    @Before fun reset() { Libass.reset(); ShadowAssNative.opened.clear() }

    @Test fun 切换内封轨时新头晚到仍能打开() {
        Libass.header("first", "first".toByteArray())
        assertTrue(Libass.activateTrack("first", "/fonts"))
        assertFalse(Libass.activateTrack("second", "/fonts"))
        assertFalse("等待新头时不显示旧字幕", Libass.isActive)
        Libass.header("second", "second".toByteArray())
        assertTrue(Libass.isActive)
        assertEquals(listOf("first", "second"), ShadowAssNative.opened)
    }

    @Test fun 内封轨仅在有外挂合并时还原一层且保留原始冒号() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val external = MediaItem.SubtitleConfiguration.Builder(Uri.parse("file:///unused.ass"))
                .setId("external").setMimeType("text/x-ssa").build()
            for ((merged, selectedId, parserId) in listOf(
                Triple(false, "0:original:track", "0:original:track"),
                Triple(true, "0:original:track", "original:track"),
                Triple(true, "0:", "ass"),
                Triple(false, null, "ass"),
            )) {
                Libass.reset()
                ShadowAssNative.opened.clear()
                player.setMediaItem(MediaItem.Builder().setUri("file:///unused.mkv")
                    .setSubtitleConfigurations(if (merged) listOf(external) else emptyList()).build())
                val format = Format.Builder().setId(selectedId).setSampleMimeType("application/x-media3-cues")
                    .setCodecs("text/x-ssa").build()
                val tracks = Tracks(listOf(Tracks.Group(TrackGroup("text", format), false,
                    intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
                syncLibassTrack(player, tracks, false)
                assertFalse(Libass.isActive)
                Libass.header(parserId, "header:$parserId".toByteArray())
                assertTrue("合并=$merged ID=$selectedId", Libass.isActive)
                assertEquals(listOf("header:$parserId"), ShadowAssNative.opened)
                syncLibassTrack(player, Tracks.EMPTY, false)
                assertFalse(Libass.isActive)
            }
        } finally { player.release(); Libass.reset() }
    }

    @Test fun 外挂只激活选中轨关闭后晚到文件不能重开() {
        Libass.file("first", "first".toByteArray())
        assertEquals(emptyList<String>(), ShadowAssNative.opened)
        assertTrue(Libass.activateTrack("first", "/fonts"))
        assertFalse(Libass.activateTrack("second", "/fonts"))
        Libass.file("second", "second".toByteArray())
        assertEquals(listOf("file:first", "file:second"), ShadowAssNative.opened)
        Libass.deactivate()
        Libass.file("second", "late".toByteArray())
        assertFalse(Libass.isActive)
        assertEquals(2, ShadowAssNative.opened.size)
    }
}

/** 仅替代JNI边界，轨道等待与激活运行真实Libass状态机。 */
@Implements(value = Native::class, isInAndroidSdk = false)
class ShadowAssNative {
    companion object {
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        @JvmStatic @Implementation fun __staticInitializer__() {
            val ctor = Native::class.java.getDeclaredConstructor().apply { isAccessible = true }
            ReflectionHelpers.setStaticField(Native::class.java, "INSTANCE", ctor.newInstance())
        }
    }
    @Implementation fun assVersion(): Int = 1
    @Implementation fun assOpen(header: ByteArray?, fontsDir: String): Int { opened += header?.decodeToString().orEmpty(); return 0 }
    @Implementation fun assOpenFile(file: ByteArray, fontsDir: String): Int { opened += "file:" + file.decodeToString(); return 0 }
    @Implementation fun assClose() {}
}
