package xyz.linplayer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 手机文字的语义入口；从当前主题取字族，变体由共用组件选择。 */
object LpText {
    val hero @Composable get() = MaterialTheme.typography.displayLarge
    val heading @Composable get() = MaterialTheme.typography.displayMedium
    val headline @Composable get() = MaterialTheme.typography.headlineMedium
    val title @Composable get() = MaterialTheme.typography.titleLarge
    val section @Composable get() = MaterialTheme.typography.titleMedium
    val list @Composable get() = MaterialTheme.typography.titleSmall
    val body @Composable get() = MaterialTheme.typography.bodyLarge
    val secondary @Composable get() = MaterialTheme.typography.bodyMedium
    val caption @Composable get() = MaterialTheme.typography.bodySmall
    val badge @Composable get() = MaterialTheme.typography.labelSmall
    val action @Composable get() = MaterialTheme.typography.labelLarge
    val filter @Composable get() = MaterialTheme.typography.labelMedium
    val card @Composable get() = body.copy(lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val compact @Composable get() = secondary.copy(lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val number @Composable get() = caption.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium)
}
