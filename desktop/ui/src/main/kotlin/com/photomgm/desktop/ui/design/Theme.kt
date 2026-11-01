// desktop/ui/design/Theme.kt —— Material3 主题装配（把令牌接到 M3 组件上）
//
// 为什么要过一层 MaterialTheme：Button / TextField / Surface 这些 M3 组件的默认色
// 来自 ColorScheme，如果不映射，会出现"我们的卡片是 #FBFCFA、M3 按钮却是紫色"的割裂。
// 业务代码写 `style = PmType.title` 直接用令牌；只有标准 M3 组件才吃这里的映射。
package com.photomgm.desktop.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PmColorScheme = lightColorScheme(
    primary = PmColor.accentFill,
    onPrimary = PmColor.onAccent,
    primaryContainer = PmColor.accentWash,
    onPrimaryContainer = PmColor.accentText,

    secondary = PmColor.dim,
    onSecondary = PmColor.surface,

    background = PmColor.canvas,
    onBackground = PmColor.ink,

    surface = PmColor.surface,
    onSurface = PmColor.ink,
    surfaceVariant = PmColor.surface2,
    onSurfaceVariant = PmColor.dim,

    outline = PmColor.line2,
    outlineVariant = PmColor.line,

    error = PmColor.bad,
    onError = PmColor.onAccent,
)

private val PmTypography = Typography(
    displaySmall = PmType.display,
    headlineSmall = PmType.headline,
    titleLarge = PmType.title.copy(fontSize = 17.sp),
    titleMedium = PmType.title,
    titleSmall = PmType.title.copy(fontSize = 14.sp),
    bodyLarge = PmType.body.copy(fontSize = 15.sp),
    bodyMedium = PmType.body,
    bodySmall = PmType.bodySmall,
    labelLarge = PmType.label.copy(fontSize = 13.sp),
    labelMedium = PmType.label,
    labelSmall = PmType.label.copy(fontSize = 11.sp),
)

private val PmShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(PmRadius.control),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(PmRadius.card),
    large = androidx.compose.foundation.shape.RoundedCornerShape(PmRadius.shell),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(PmRadius.shell),
)

@Composable
fun PhotoMgmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PmColorScheme,
        typography = PmTypography,
        shapes = PmShapes,
        content = content,
    )
}
