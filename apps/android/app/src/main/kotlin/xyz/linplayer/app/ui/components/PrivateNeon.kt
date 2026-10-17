package xyz.linplayer.app.ui.components

import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.LocalPrivateSpace
import xyz.linplayer.app.ui.theme.R

private val LocalNeonPhase = staticCompositionLocalOf<State<Float>?> { null }
private val NeonCyan = Color(0xFF36FFF3)
private val NeonRed = Color(0xFFFF426D)

/** 手机浏览窗口共用一个相位；后台或零动画倍率保持静态，不驱动整页重组。 */
@Composable
internal fun PrivateNeonHost(content: @Composable () -> Unit) = PrivateNeonHost(true, content)

@Composable
internal fun PrivateNeonHost(enabled: Boolean, content: @Composable () -> Unit) {
    val privateSpace = LocalPrivateSpace.current && enabled
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motion = LocalMotionScale.current
    val still = remember { mutableFloatStateOf(0f) }
    val phase = if (privateSpace && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && motion > 0f) {
        val transition = rememberInfiniteTransition(label = "privateNeon")
        // Compose已应用系统倍率，基础时长不能再乘一次。
        transition.animateFloat(0f, 360f, infiniteRepeatable(
            tween(10_000, easing = LinearEasing), RepeatMode.Restart,
        ), label = "privateNeonPhase")
    } else still
    CompositionLocalProvider(LocalNeonPhase provides phase.takeIf { privateSpace }, content = content)
}

/** 放在形状裁剪之前，外侧柔光才不会被按钮自身裁掉。 */
@Composable
internal fun Modifier.privateNeonBorder(corner: Dp = R.sm, inset: Dp = 0.dp, enabled: Boolean = true): Modifier =
    privateNeon(corner, inset, enabled)

/** 纯图标只有柔光，不把透明命中区描成按钮。 */
@Composable
internal fun Modifier.privateNeonIcon(): Modifier = privateNeon(null, 0.dp, true)

@Composable
private fun Modifier.privateNeon(corner: Dp?, edgeInset: Dp, enabled: Boolean): Modifier {
    val phase = LocalNeonPhase.current
    if (!enabled || phase == null) return this
    return drawWithCache {
        val edge = edgeInset.toPx()
        val bounds = Size((size.width - edge * 2).coerceAtLeast(0f), (size.height - edge * 2).coerceAtLeast(0f))
        val outline = corner?.let { RoundedCornerShape(it).createOutline(bounds, layoutDirection, this) }
        val shader = SweepGradient(bounds.width / 2, bounds.height / 2,
            intArrayOf(NeonCyan.toArgb(), NeonCyan.toArgb(), NeonRed.toArgb(), NeonRed.toArgb(), NeonCyan.toArgb()),
            floatArrayOf(0f, .18f, .45f, .68f, 1f))
        val brush = ShaderBrush(shader)
        val matrix = Matrix()
        val broad = Stroke(12.dp.toPx())
        val soft = Stroke(8.dp.toPx())
        val glow = Stroke(4.dp.toPx())
        val line = Stroke(1.4.dp.toPx())
        onDrawWithContent {
            if (outline == null) {
                matrix.setRotate(phase.value, bounds.width / 2, bounds.height / 2)
                shader.setLocalMatrix(matrix)
                drawCircle(brush, radius = size.minDimension * .85f, alpha = .06f)
                drawCircle(brush, radius = size.minDimension * .67f, alpha = .12f)
                drawCircle(brush, radius = size.minDimension * .5f, alpha = .18f)
                drawContent()
            } else {
                drawContent()
                inset(edge, edge, edge, edge) {
                    matrix.setRotate(phase.value, bounds.width / 2, bounds.height / 2)
                    shader.setLocalMatrix(matrix)
                    drawOutline(outline, brush, alpha = .06f, style = broad)
                    drawOutline(outline, brush, alpha = .12f, style = soft)
                    drawOutline(outline, brush, alpha = .28f, style = glow)
                    drawOutline(outline, brush, style = line)
                }
            }
        }
    }
}
