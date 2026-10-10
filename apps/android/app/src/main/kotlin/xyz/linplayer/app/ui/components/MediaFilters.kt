package xyz.linplayer.app.ui.components

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R

/** 媒体浏览页共用的条件与排序选中态。 */
@Composable
fun MediaFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lp.colors
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, style = LpText.filter, maxLines = 1) },
        shape = RoundedCornerShape(R.md), border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.s2, labelColor = c.fg2,
            selectedContainerColor = c.mediaAccent, selectedLabelColor = c.mediaOnAccent,
        ),
    )
}
