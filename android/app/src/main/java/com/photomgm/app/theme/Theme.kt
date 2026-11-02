// app/theme/Theme.kt —— Material3 主题（光/暗自适应 + 中文排版优化 + 形状系统）
// ★ 2026-10-07 UI 重建：沿用原色板（公路巡查·工业精致风），结构与实现全新书写。
package com.photomgm.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ──────────────────────────────────────────────────────────────────
// 色板（深青绿主色 + 琥珀辅色 + 暖灰中性色）
// ──────────────────────────────────────────────────────────────────
private val LightPrimary       = Color(0xFF0E5C6B)
private val LightOnPrimary     = Color(0xFFFFFFFF)
private val LightPrimaryCtr    = Color(0xFFB7E9F0)
private val LightOnPrimaryCtr  = Color(0xFF002027)
private val LightSecondary     = Color(0xFF5D6167)
private val LightOnSecondary   = Color(0xFFFFFFFF)
private val LightSecondaryCtr  = Color(0xFFE1E2E8)
private val LightOnSecondaryCtr= Color(0xFF1A1C1E)
private val LightTertiary      = Color(0xFFC96A00)
private val LightOnTertiary    = Color(0xFFFFFFFF)
private val LightTertiaryCtr   = Color(0xFFFFDCC2)
private val LightOnTertiaryCtr = Color(0xFF401E00)
private val LightError         = Color(0xFFBA1A1A)
private val LightOnError       = Color(0xFFFFFFFF)
private val LightErrorCtr      = Color(0xFFFFDAD6)
private val LightOnErrorCtr    = Color(0xFF410002)
private val LightSurface       = Color(0xFFFAFAF7)
private val LightOnSurface     = Color(0xFF1A1C1E)
private val LightSurfaceVar    = Color(0xFFE0E3E4)
private val LightOnSurfaceVar  = Color(0xFF434849)
private val LightSurfaceCtrLow = Color(0xFFF4F4F0)
private val LightSurfaceCtr    = Color(0xFFEEEDEA)
private val LightSurfaceCtrHi  = Color(0xFFE6E5E1)
private val LightOutline       = Color(0xFF737879)
private val LightOutlineVar    = Color(0xFFC3C7C8)

private val DarkPrimary        = Color(0xFF7FD2DF)
private val DarkOnPrimary      = Color(0xFF00363E)
private val DarkPrimaryCtr     = Color(0xFF004F59)
private val DarkOnPrimaryCtr   = Color(0xFFB7E9F0)
private val DarkSecondary      = Color(0xFFC5C6CC)
private val DarkOnSecondary    = Color(0xFF2F3134)
private val DarkSecondaryCtr   = Color(0xFF44474B)
private val DarkOnSecondaryCtr = Color(0xFFE1E2E8)
private val DarkTertiary       = Color(0xFFFFB780)
private val DarkOnTertiary     = Color(0xFF663600)
private val DarkTertiaryCtr    = Color(0xFF8C4E00)
private val DarkOnTertiaryCtr  = Color(0xFFFFDCC2)
private val DarkError          = Color(0xFFFFB4AB)
private val DarkOnError        = Color(0xFF690005)
private val DarkErrorCtr       = Color(0xFF93000A)
private val DarkOnErrorCtr     = Color(0xFFFFDAD6)
private val DarkSurface        = Color(0xFF121316)
private val DarkOnSurface      = Color(0xFFE3E2E0)
private val DarkSurfaceVar     = Color(0xFF434849)
private val DarkOnSurfaceVar   = Color(0xFFC3C7C8)
private val DarkSurfaceCtrLow  = Color(0xFF1A1C1F)
private val DarkSurfaceCtr     = Color(0xFF1F2125)
private val DarkSurfaceCtrHi   = Color(0xFF2A2D31)
private val DarkOutline        = Color(0xFF8D9192)
private val DarkOutlineVar     = Color(0xFF434849)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary, onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryCtr, onPrimaryContainer = LightOnPrimaryCtr,
    secondary = LightSecondary, onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryCtr, onSecondaryContainer = LightOnSecondaryCtr,
    tertiary = LightTertiary, onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryCtr, onTertiaryContainer = LightOnTertiaryCtr,
    error = LightError, onError = LightOnError,
    errorContainer = LightErrorCtr, onErrorContainer = LightOnErrorCtr,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVar, onSurfaceVariant = LightOnSurfaceVar,
    outline = LightOutline, outlineVariant = LightOutlineVar,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurfaceCtrLow,
    surfaceContainer = LightSurfaceCtr,
    surfaceContainerHigh = LightSurfaceCtrHi,
    surfaceContainerHighest = Color(0xFFDFDEDA),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2F3134),
    inverseOnSurface = Color(0xFFF4F4F0),
    inversePrimary = Color(0xFF7FD2DF),
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary, onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryCtr, onPrimaryContainer = DarkOnPrimaryCtr,
    secondary = DarkSecondary, onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryCtr, onSecondaryContainer = DarkOnSecondaryCtr,
    tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryCtr, onTertiaryContainer = DarkOnTertiaryCtr,
    error = DarkError, onError = DarkOnError,
    errorContainer = DarkErrorCtr, onErrorContainer = DarkOnErrorCtr,
    surface = DarkSurface, onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVar, onSurfaceVariant = DarkOnSurfaceVar,
    outline = DarkOutline, outlineVariant = DarkOutlineVar,
    surfaceContainerLowest = Color(0xFF0C0D0F),
    surfaceContainerLow = DarkSurfaceCtrLow,
    surfaceContainer = DarkSurfaceCtr,
    surfaceContainerHigh = DarkSurfaceCtrHi,
    surfaceContainerHighest = Color(0xFF35393D),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE3E2E0),
    inverseOnSurface = Color(0xFF2F3134),
    inversePrimary = Color(0xFF0E5C6B),
)

// ──────────────────────────────────────────────────────────────────
// 自定义令牌：缩略图选中边框宽 / 卡片角径等组件尺寸
// ──────────────────────────────────────────────────────────────────
@Immutable
data class PhotoMgmDimens(
    val thumbBorderWidth: androidx.compose.ui.unit.Dp = 2.5.dp,
    val thumbSafetyPad:   androidx.compose.ui.unit.Dp = 8.dp,
    val cardCorner:       androidx.compose.ui.unit.Dp = 16.dp,
    val touchMin:         androidx.compose.ui.unit.Dp = 48.dp,
)
internal val LocalDimens = staticCompositionLocalOf { PhotoMgmDimens() }

// ──────────────────────────────────────────────────────────────────
// 形状系统：小 6dp · 中 12dp · 大 20dp · 按钮 12dp
// ──────────────────────────────────────────────────────────────────
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ──────────────────────────────────────────────────────────────────
// 排版：中文 1.55 行高 · 标题较粗(600) · 正文 Regular · 标签 11sp
// ──────────────────────────────────────────────────────────────────
private val AppTypography = Typography(
    displayLarge   = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 48.sp),
    displayMedium  = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    displaySmall   = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    headlineLarge  = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    headlineSmall  = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, letterSpacing = 0.1.sp),
    titleLarge     = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium,   lineHeight = 24.sp),
    titleMedium    = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium,   lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,   lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge      = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal,   lineHeight = 23.sp),
    bodyMedium     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,   lineHeight = 22.sp),
    bodySmall      = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal,   lineHeight = 19.sp),
    labelLarge     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,   lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium    = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,   lineHeight = 17.sp, letterSpacing = 0.3.sp),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium,   lineHeight = 15.sp, letterSpacing = 0.4.sp),
)

@Composable
fun PhotoMgmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalDimens provides PhotoMgmDimens()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = AppTypography,
            shapes      = AppShapes,
            content     = content,
        )
    }
}
