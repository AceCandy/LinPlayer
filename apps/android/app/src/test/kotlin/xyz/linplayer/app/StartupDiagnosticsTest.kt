package xyz.linplayer.app

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StartupDiagnosticsTest {
    @Test fun Application创建前的异常也能落盘() {
        assumeTrue(BuildConfig.STARTUP_DIAGNOSTICS)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        try {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.filesDir, "last-crash.txt")
            file.delete()
            val app = LinPlayerApp()
            ReflectionHelpers.callInstanceMethod<Unit>(app, "attachBaseContext",
                ReflectionHelpers.ClassParameter.from(Context::class.java, context))
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(
                Thread.currentThread(), IllegalStateException("provider 初始化失败"))
            assertTrue(file.readText().contains("provider 初始化失败"))
            file.delete()
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test fun 核心未加载仍可显示异常并复制脱敏报告() {
        assumeTrue(BuildConfig.STARTUP_DIAGNOSTICS)
        val controller = Robolectric.buildActivity(StartupDiagnosticsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(Application::class.java, activity.application.javaClass)
            activity.showFailure(UnsatisfiedLinkError("测试库加载失败 token=PRIVATE_VALUE"))
            File(activity.filesDir, "last-crash.txt").writeText("播放器进程 IllegalStateException token=PRIVATE_VALUE")
            activity.findViewById<Button>(StartupDiagnosticsActivity.COPY_ID).performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val report = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("UnsatisfiedLinkError"))
            assertTrue(report.contains("测试库加载失败"))
            assertTrue(report.contains("播放器进程 IllegalStateException"))
            assertFalse(report.contains("PRIVATE_VALUE"))
        } finally { controller.pause().stop().destroy() }
    }
}
