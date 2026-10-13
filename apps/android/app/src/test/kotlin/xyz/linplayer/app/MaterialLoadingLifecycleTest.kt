package xyz.linplayer.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.material.loadingindicator.LoadingIndicator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowValueAnimator
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import xyz.linplayer.app.ui.components.LpLoading
import xyz.linplayer.app.ui.theme.LpTheme
import java.time.Duration

/** 使用有界的真实主线程帧，不让 Compose 空闲等待消耗无限 View 动画。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h800dp-mdpi", sdk = [36], application = Application::class,
    shadows = [LoopingAnimatorShadow::class, LoopingObjectAnimatorShadow::class])
class MaterialLoadingLifecycleTest {
    @Test fun materialStopsItsRealAnimatorAndRespectsSystemZeroScale() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val resolver = activity.contentResolver
        val before = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val beforeAnimator = ValueAnimator.getDurationScale()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
        }
        val visible = mutableStateOf(true)
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
            activity.setContent { LpTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) LpLoading(style = 0)
                }
            } }
            step(100)
            val view = descendants(activity.window.decorView).filterIsInstance<LoadingIndicator>().single()
            val delegate = field(view.drawable, "animatorDelegate")!!
            assertFalse("宿主尚未STARTED时挂载View也不能启动动画", (field(delegate, "animator") as? ObjectAnimator)?.isRunning == true)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            step(100)
            val animator = field(delegate, "animator") as ObjectAnimator
            assertTrue("前台应由生产路径启动真实动画", animator.isRunning)
            val first = field(delegate, "animationFraction")
            step(200)
            assertNotEquals("真实动画时间必须推进", first, field(delegate, "animationFraction"))
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            step(32)
            assertFalse(animator.isRunning)
            val stopped = field(delegate, "animationFraction")
            step(600)
            assertEquals(stopped, field(delegate, "animationFraction"))
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            step(100)
            assertTrue("恢复前台仍在等待时应播放", animator.isRunning)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            step(32)
            assertFalse("系统关闭动画应停止", animator.isRunning)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("零倍率仍有静态图形", pixels.any { it ushr 24 > 0 })
            bitmap.recycle()
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            step(100)
            assertTrue(animator.isRunning)
            visible.value = false
            step(32)
            assertFalse("离组合应停止", animator.isRunning)
        } finally {
            controller.pause().stop().destroy()
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, before)
            ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, beforeAnimator)
        }
    }

    private fun step(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val frame = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frame))
            remaining -= frame
        }
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}

/** Robolectric 默认把无限循环缩成两轮；保留循环，由生产生命周期负责停止。 */
@Implements(ValueAnimator::class)
open class LoopingAnimatorShadow : ShadowValueAnimator() {
    @RealObject private lateinit var animator: ValueAnimator
    @Implementation override fun setRepeatCount(value: Int) {
        ReflectionHelpers.setField(animator, "mRepeatCount", value)
    }
}

@Implements(ObjectAnimator::class)
class LoopingObjectAnimatorShadow : LoopingAnimatorShadow()
