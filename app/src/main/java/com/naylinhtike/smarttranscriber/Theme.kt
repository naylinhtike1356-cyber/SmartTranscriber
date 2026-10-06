package com.naylinhtike.smarttranscriber

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Slate600 = Color(0xFF475569)
val Slate300 = Color(0xFFCBD5E1)
val Slate100 = Color(0xFFF1F5F9)

val Cyan500 = Color(0xFF06B6D4)
val Cyan400 = Color(0xFF22D3EE)
val Emerald500 = Color(0xFF10B981)
val Amber500 = Color(0xFFF59E0B)
val Rose500 = Color(0xFFF43F5E)
val Indigo500 = Color(0xFF6366F1)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan400,
    onPrimary = Slate900,
    primaryContainer = Color(0xFF164E63),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = Indigo500,
    onSecondary = Color.White,
    background = Slate900,
    surface = Slate800,
    surfaceVariant = Slate700,
    onBackground = Slate100,
    onSurface = Slate100,
    onSurfaceVariant = Slate300,
    error = Rose500
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0891B2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF164E63),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Slate900,
    onSurface = Slate900,
    onSurfaceVariant = Slate700,
    error = Rose500
)

@Composable
fun SmartTranscriberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
