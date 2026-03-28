package com.example.airkast

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ── Spotify-inspired Dark Color Palette ──
val AirkastBlack = Color(0xFF0A0A0A)
val AirkastSurface = Color(0xFF161616)
val AirkastSurfaceElevated = Color(0xFF1E1E1E)
val AirkastSurfaceVariant = Color(0xFF2A2A2A)
val AirkastAccent = Color(0xFF1DB954)
val AirkastAccentBright = Color(0xFF1ED760)
val AirkastTextPrimary = Color(0xFFFFFFFF)
val AirkastTextSecondary = Color(0xFFB3B3B3)
val AirkastTextTertiary = Color(0xFF727272)
val AirkastError = Color(0xFFFF4B4B)
val AirkastDivider = Color(0xFF2A2A2A)

private val AirkastDarkScheme = darkColorScheme(
    primary = AirkastAccent,
    onPrimary = Color.Black,
    primaryContainer = AirkastAccent,
    onPrimaryContainer = Color.Black,
    secondary = AirkastAccentBright,
    onSecondary = Color.Black,
    secondaryContainer = AirkastAccent.copy(alpha = 0.2f),
    onSecondaryContainer = AirkastAccent,
    tertiary = AirkastAccentBright,
    onTertiary = Color.Black,
    background = AirkastBlack,
    onBackground = AirkastTextPrimary,
    surface = AirkastSurface,
    onSurface = AirkastTextPrimary,
    surfaceVariant = AirkastSurfaceVariant,
    onSurfaceVariant = AirkastTextSecondary,
    surfaceContainerHighest = AirkastSurfaceElevated,
    outline = AirkastDivider,
    outlineVariant = AirkastDivider,
    error = AirkastError,
    onError = Color.White,
)

// ── Typography ──
val AirkastTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = AirkastTextPrimary,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
    ),
)

@Composable
fun AirkastTheme(
    content: @Composable () -> Unit
) {
    // ダークモード固定
    val colorScheme = AirkastDarkScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge: 透明ステータスバー＆ナビゲーションバー
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            // ダークモード → ライトアイコン (白)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AirkastTypography,
        content = content
    )
}
