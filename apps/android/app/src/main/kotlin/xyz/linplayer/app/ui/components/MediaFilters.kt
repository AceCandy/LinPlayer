package xyz.linplayer.app.ui.components

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R

/** 媒体浏览页共用的条件与排序选中态。 */
@Composable
fun MediaFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lp.colors
    // 外层保留48dp触摸布局，内层只按可见表面绘制荧光。
    Box(Modifier.minimumInteractiveComponentSize()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            FilterChip(
                selected = selected, onClick = onClick,
                modifier = Modifier.privateNeonBorder(R.md),
                label = { Text(label, style = LpText.filter, maxLines = 1) },
                shape = RoundedCornerShape(R.md), border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = c.s2, labelColor = c.fg2,
                    selectedContainerColor = c.mediaAccent, selectedLabelColor = c.mediaOnAccent,
                ),
            )
        }
    }
}
