package com.example.entify_again.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

object CategoryColors {
    val Comment = Color(0xFF2196F3)
    val Like = Color(0xFFE91E63)
    val Follow = Color(0xFF4CAF50)
    val Remake = Color(0xFF673AB7)
    val Share = Color(0xFF00ACC1)
    val Warning = Color(0xFFFF5722)
    val System = Color(0xFFFF9800)

    fun forCategory(category: String): Color = when (category.uppercase()) {
        "COMMENT" -> Comment
        "LIKE" -> Like
        "FOLLOW" -> Follow
        "REMAKE" -> Remake
        "SHARE" -> Share
        "WARNING" -> Warning
        "SYSTEM" -> System
        else -> System
    }
}

enum class ThemeMode { LIGHT, DARK, SYSTEM }

val LocalThemeMode = compositionLocalOf { ThemeMode.SYSTEM }

private val DarkColorScheme = darkColorScheme(
    background = Color(0xFF0E0D1A),
    surface = Color(0xFF1A1928),
    surfaceVariant = Color(0xFF252436),
    primary = Color(0xFF7C6FF7),
    secondary = Color(0xFF4DD0E1),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB8B5C8)
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFF8F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEEDF5),
    primary = Color(0xFF5B4FD6),
    secondary = Color(0xFF0097A7),
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
    onSurfaceVariant = Color(0xFF5A5770)
)

@Composable
fun EntifyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> darkTheme
    }
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            }
        }
    }

    CompositionLocalProvider(LocalThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
