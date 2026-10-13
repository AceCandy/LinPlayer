package xyz.linplayer.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.ui.theme.lpTween

internal val PlayerMenuAccent = Color(0xFF9CCAFF)
private val PlayerMenuBackground = Color(0xFF1D2124)

/** 入口附近向下展开，底部入口向上展开；窄窗口和RTL均限制在窗口内。 */
internal class PlayerMenuPositionProvider(private val anchor: Rect?, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val a = anchor?.let { IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
            ?: IntRect(windowSize.width - gap, gap, windowSize.width - gap, gap)
        var x = if (layoutDirection == LayoutDirection.Ltr) a.left else a.right - popupContentSize.width
        val below = a.bottom + gap
        val above = a.top - gap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height - gap -> below
            above >= gap -> above
            else -> {
                val left = a.left - gap - popupContentSize.width
                val right = a.right + gap
                x = when {
                    left >= gap -> left
                    right + popupContentSize.width <= windowSize.width - gap -> right
                    else -> x
                }
                a.top - gap
            }
        }
        return IntOffset(x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
            y.coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)))
    }
}

/** 无遮罩的播放器菜单；退出完成前保留表面，业务读取由调用方立即取消。 */
@Composable
internal fun PlayerMenu(open: Boolean, anchor: Rect?, width: Dp, tall: Boolean = false,
    onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    if (!visible.currentState && !visible.targetState) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val maxWidth = (LocalConfiguration.current.screenWidthDp - 8).coerceAtLeast(1).dp
    val maxHeight = minOf(if (tall) 420 else 320, LocalConfiguration.current.screenHeightDp - 8).coerceAtLeast(48).dp
    Popup(remember(anchor, gap) { PlayerMenuPositionProvider(anchor, gap) }, onDismissRequest = onClose,
        properties = PopupProperties(focusable = open)) {
        AnimatedVisibility(visible, enter = fadeIn(lpTween(150)), exit = fadeOut(lpTween(100))) {
            Column(Modifier.widthIn(max = maxWidth).width(width).heightIn(max = maxHeight)
                .pointerInput(open) {
                    if (!open) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
                .shadow(8.dp, RoundedCornerShape(4.dp)).background(PlayerMenuBackground, RoundedCornerShape(4.dp))
                .testTag("player.menu").padding(vertical = 4.dp), content = content)
        }
    }
}

/** 左勾选与浅蓝文字表示当前项，保留轨道语言副标题。 */
@Composable
internal fun PlayerMenuRow(label: String, onClick: () -> Unit, selected: Boolean = false,
    sub: String? = null, badge: String? = null, icon: ImageVector? = null) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = selected }
            .pressable(onClick).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (selected || icon != null) Icon(if (selected) LpIcons.check else icon!!, null,
                    Modifier.size(16.dp).testTag(if (selected) "player.menu.check" else "player.menu.icon"),
                    tint = if (selected) PlayerMenuAccent else Color.White.copy(alpha = .7f))
            }
            Column(Modifier.weight(1f)) {
                Text(label, color = if (selected) PlayerMenuAccent else Color.White.copy(alpha = .7f), style = LpText.action)
                if (sub != null) Text(sub, color = Color.White.copy(alpha = .5f), style = LpText.caption)
            }
            if (badge != null) Text(badge, color = Color.White.copy(alpha = .5f), style = LpText.caption)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
    }
}

@Composable
internal fun PlayerMenuNote(text: String, m: Modifier = Modifier, maxLines: Int = 4) {
    Text(text, m.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White.copy(alpha = .6f),
        style = LpText.caption, maxLines = maxLines)
}
