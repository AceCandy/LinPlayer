package xyz.linplayer.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TV安装包在未报告电视模式的设备上，也必须进入遥控器界面。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MainActivityShapeTest {
    @Before @After fun cleanup() { MainActivity.SelfCheck.page = null }

    @Test fun normalSystemModeUsesBuildShape() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        shadowOf(activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
        assertEquals(BuildConfig.TV_BUILD, activity.tvShape())
    }

    @Test fun televisionSystemStillUsesTv() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        shadowOf(activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        assertTrue(activity.tvShape())
    }

    @Test fun explicitTvSelfCheckStillWorks() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity.SelfCheck.page = "tv"
        assertTrue(activity.tvShape())
    }
}
