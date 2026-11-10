package com.cleanbot.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Background
)

/**
 * Тёмная палитра бренда (Android < 12): светлые primary-тона и тёмные
 * onPrimary — иначе primary-кнопки нечитаемы (аудит 2026-11-11: тёмный
 * текст на teal). Свои surface/surfaceVariant вместо дефолтов M3, чтобы
 * фон совпадал с тёмной платформенной темой окна.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF00332D),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332D),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF23272B),
    onSurfaceVariant = Color(0xFFB9C0C7)
)

@Composable
fun CleanBotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Material You: на Android 12+ палитра строится из обоев пользователя —
    // рыночный стандарт look-and-feel; на более старых — брендовая палитра.
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
