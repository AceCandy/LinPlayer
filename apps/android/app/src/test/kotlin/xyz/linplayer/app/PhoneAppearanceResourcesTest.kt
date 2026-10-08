package xyz.linplayer.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.ui.components.DetailBackgroundBlur

/** 内置字体与背景处理在最低支持系统上也必须可用。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], application = Application::class)
class PhoneAppearanceResourcesTest {
    @Test fun fontsLoadAndSelectionsSurviveReloadWhileLegacyPathsReset() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        for (id in listOf(R.font.noto_sans_sc_regular, R.font.noto_serif_sc_regular)) {
            val font = ResourcesCompat.getFont(ctx, id)
            assertNotNull(font)
        }
        for (id in listOf("sans", "serif", "")) {
            UiPrefs.setFont(ctx, id)
            UiPrefs.uiFont.value = ""
            UiPrefs.load(ctx)
            assertEquals(id, UiPrefs.uiFont.value)
        }
        ctx.getSharedPreferences("lp_ui", 0).edit().putString("ui_font", "legacy-font-file").commit()
        UiPrefs.load(ctx)
        assertEquals("", UiPrefs.uiFont.value)
        assertEquals("", ctx.getSharedPreferences("lp_ui", 0).getString("ui_font", null))
    }

    @Test fun backgroundBlurSoftensDetailKeepsOriginalAndBoundsWork() = runBlocking {
        val original = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 40)
            original.setPixel(x, y, if (x < 20) Color.BLACK else Color.WHITE)
        val result = DetailBackgroundBlur.transform(original, Size.ORIGINAL)
        assertTrue(Color.red(result.getPixel(19, 20)) in 1..38)
        assertTrue(Color.red(result.getPixel(20, 20)) in 217..254)
        assertEquals(Color.BLACK, original.getPixel(19, 20))
        assertEquals(Color.WHITE, original.getPixel(20, 20))
        assertEquals(255, Color.alpha(result.getPixel(0, 0)))
        assertEquals(40, result.width)
        val transparent = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 20 until 40) transparent.setPixel(x, y, Color.RED)
        val edge = DetailBackgroundBlur.transform(transparent, Size.ORIGINAL).getPixel(20, 20)
        assertTrue(Color.red(edge) >= 250)
        assertEquals(0, Color.green(edge))
        val large = Bitmap.createBitmap(1920, 960, Bitmap.Config.ARGB_8888)
        val bounded = DetailBackgroundBlur.transform(large, Size.ORIGINAL)
        assertTrue(maxOf(bounded.width, bounded.height) <= 1280)
    }
}
