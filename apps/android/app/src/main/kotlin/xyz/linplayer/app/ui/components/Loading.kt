package xyz.linplayer.app.ui.components

import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.google.android.material.loadingindicator.LoadingIndicator
import xyz.linplayer.app.R
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.ui.theme.Sp

/** 手机原生等待的唯一动画入口；预览可显式指定样式，其余跟随本机设置。 */
@Composable
fun LpLoading(m: Modifier = Modifier, size: Dp = 32.dp, tint: Color = Lp.colors.mediaAccent,
    style: Int = UiPrefs.loadingStyle.value, label: String = "加载中") {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val motion = LocalMotionScale.current
    val started = state.isAtLeast(Lifecycle.State.STARTED)
    val playing = started && motion > 0f
    val modifier = m.size(size).semantics { contentDescription = label }
    val resource = when (style) {
        1 -> R.raw.lp_loading_1; 2 -> R.raw.lp_loading_2; 3 -> R.raw.lp_loading_3
        4 -> R.raw.lp_loading_4; 5 -> R.raw.lp_loading_5; 7 -> R.raw.lp_loading_7
        else -> null
    }
    if (resource == null) {
        val ctx = LocalContext.current
        val latestPlaying by rememberUpdatedState(playing)
        val view = remember(ctx) {
            LoadingIndicator(ContextThemeWrapper(ctx, com.google.android.material.R.style.Theme_Material3_DayNight)).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setContainerColor(android.graphics.Color.TRANSPARENT)
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // 等原生窗口可见性回调完成，最后按宿主播放条件同步。
                        v.post { if (v.isAttachedToWindow) drawable.setVisible(v.isShown, false, latestPlaying) }
                    }
                    override fun onViewDetachedFromWindow(v: View) { drawable.setVisible(false, false, false) }
                })
            }
        }
        AndroidView(factory = { view }, modifier = modifier,
            onRelease = { it.drawable.setVisible(false, false, false) }, update = {
                // 原生View的窗口回调也会启播；后台保持不可见，不能绕过宿主生命周期。
                it.visibility = if (started) View.VISIBLE else View.INVISIBLE
                val px = (size.value * it.resources.displayMetrics.density).toInt()
                it.setIndicatorSize(px); it.setContainerWidth(px); it.setContainerHeight(px)
                it.setIndicatorColor(tint.toArgb())
            })
        DisposableEffect(view, playing, motion) {
            view.drawable.setVisible(true, false, playing)
            onDispose { view.drawable.setVisible(false, false, false) }
        }
    } else {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resource))
        val animation = animateLottieCompositionAsState(composition, isPlaying = playing,
            restartOnPlay = false, iterations = LottieConstants.IterateForever)
        val properties = if (style == 4) rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(LottieProperty.COLOR, tint.toArgb(), "**"),
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, tint.toArgb(), "**"),
        ) else null
        // 零倍率取有效中间帧，避免部分资源起始透明而看不到等待反馈。
        LottieAnimation(composition, progress = { if (motion <= 0f) .5f else animation.progress },
            modifier = modifier, dynamicProperties = properties)
    }
}

/** 保留原状态说明的行内等待，不遮盖已经加载的内容。 */
@Composable
fun LoadingLabel(text: String, m: Modifier = Modifier, tint: Color = Lp.colors.mediaAccent) {
    Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
        LpLoading(size = 24.dp, tint = tint)
        Text(text, color = tint, style = LpText.secondary)
    }
}

/** 下拉阶段保留刷新手势反馈，请求进行时使用所选等待动画。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BoxScope.LpPullIndicator(state: PullToRefreshState, refreshing: Boolean) {
    PullToRefreshDefaults.IndicatorBox(state = state, isRefreshing = refreshing,
        modifier = Modifier.align(Alignment.TopCenter), containerColor = Lp.colors.s2) {
        if (refreshing) LpLoading(label = "刷新中")
        else Icon(LpIcons.refresh, if (state.distanceFraction >= 1f) "松开刷新" else "下拉刷新", tint = Lp.colors.mediaAccent)
    }
}
