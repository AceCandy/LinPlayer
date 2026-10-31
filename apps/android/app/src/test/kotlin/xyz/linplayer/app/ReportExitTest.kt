package xyz.linplayer.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.data.Report

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReportExitTest {
    private fun exit(reason: Int, timestamp: Long) {
        val ctx = RuntimeEnvironment.getApplication()
        val info = ReflectionHelpers.newInstance(ApplicationExitInfo::class.java)
        ReflectionHelpers.setField(info, "mReason", reason)
        ReflectionHelpers.setField(info, "mTimestamp", timestamp)
        ReflectionHelpers.setField(info, "mPss", 80_000L)
        ReflectionHelpers.setField(info, "mRss", 160_000L)
        ReflectionHelpers.setField(info, "mDescription", "private-media-url")
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(info)
    }

    @Test fun firstLowMemoryExitIsLoggedWithoutAutomaticCrashReport() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("report", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, 100L)
        assertNull(Report.takePending(ctx))
        val message = ShadowLog.getLogsForTag("process-exit").single().msg
        assertTrue(message.contains("reason=${ApplicationExitInfo.REASON_LOW_MEMORY}"))
        assertTrue(message.contains("pss_kib=80000"))
        assertTrue(message.contains("rss_kib=160000"))
        assertFalse(message.contains("private-media-url"))
        ShadowLog.clear()
        assertNull(Report.takePending(ctx))
        assertTrue(ShadowLog.getLogsForTag("process-exit").isEmpty())
    }

    @Test fun newNativeCrashStillReportsOnce() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("report", Context.MODE_PRIVATE).edit().putLong("exit_seen_ts", 100L).commit()
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 200L)
        assertTrue(Report.takePending(ctx)!!.contains("原生层崩溃"))
        assertNull(Report.takePending(ctx))
    }
}
