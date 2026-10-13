package xyz.linplayer.app

import android.app.Application
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
        val opened = mutableListOf<String>()
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
