// ui/theme/Theme.kt —— 把令牌装配进 Material3，并提供少量原子组件会用到的语义色
//
// ★ 只映射用得到的槽位。M3 有 30 多个 colorScheme 槽位，全填一遍等于把令牌稀释；
//   这里只保证 Text/OutlinedTextField/Surface 这些被真正用到的组件跟随令牌。
package com.photomgm.desktop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PmColorScheme = lightColorScheme(
    primary = PmColor.accent,
    onPrimary = PmColor.surface,
    primaryContainer = PmColor.accentSoft,
    onPrimaryContainer = PmColor.accent,
    secondary = PmColor.ink2,
    onSecondary = PmColor.surface,
    secondaryContainer = PmColor.surface2,
    onSecondaryContainer = PmColor.ink,
    background = PmColor.canvas,
    onBackground = PmColor.ink,
    surface = PmColor.surface,
    onSurface = PmColor.ink,
    surfaceVariant = PmColor.surface2,
    onSurfaceVariant = PmColor.ink2,
    outline = PmColor.lineStrong,
    outlineVariant = PmColor.line,
    error = PmColor.danger,
    onError = PmColor.surface,
    errorContainer = PmColor.dangerSoft,
    onErrorContainer = PmColor.danger,
)

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = PmType.display,
        titleLarge = PmType.title,
        titleMedium = PmType.subtitle,
        bodyLarge = PmType.body,
        bodyMedium = PmType.body,
        bodySmall = PmType.caption,
        labelLarge = PmType.label,
        labelMedium = PmType.label,
        labelSmall = PmType.caption,
    )
}

private val PmShapes = Shapes(
    extraSmall = RoundedCornerShape(PmRadius.control),
    small = RoundedCornerShape(PmRadius.control),
    medium = RoundedCornerShape(PmRadius.card),
    large = RoundedCornerShape(PmRadius.panel),
)

@Composable
fun PmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PmColorScheme,
        typography = typography(),
        shapes = PmShapes,
        content = content,
    )
}
