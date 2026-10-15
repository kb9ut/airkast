package com.example.airkast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * アプリのテーマ。ライト/ダークは OS 設定に追従し、Android 12+ では Dynamic Color、
 * それ以外は静的パレットを使う。システムバーの色/アイコン色は MainActivity の
 * enableEdgeToEdge() が OS のライト/ダークに合わせて設定する。
 */
@Composable
fun AirkastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> AirkastDarkColorScheme
        else -> AirkastLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AirkastTypography,
        shapes = AirkastShapes,
        content = content,
    )
}
