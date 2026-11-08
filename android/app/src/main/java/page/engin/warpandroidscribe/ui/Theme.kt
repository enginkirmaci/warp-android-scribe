package com.engin.warpandroidscribe.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ScribeColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181E)
    val Key = Color(0xFF1A1E24)
    val Text = Color(0xFFE5E7EB)
    val Muted = Color(0xFF9CA3AF)
    val Green = Color(0xFF22C55E)
    val GreenDeep = Color(0xFF16A34A)
    val Recording = Color(0xFFEF4444)
    val RecordingDeep = Color(0xFFB91C1C)
    val RecordingBg = Color(0xFF2A1414)
    val Error = Color(0xFFF87171)
    val ErrorBg = Color(0xFF2A1215)
}

private val ScribeScheme = darkColorScheme(
    primary = ScribeColors.Green,
    onPrimary = Color(0xFF04140A),
    background = ScribeColors.Background,
    onBackground = ScribeColors.Text,
    surface = ScribeColors.Surface,
    onSurface = ScribeColors.Text,
    surfaceVariant = ScribeColors.Key,
    onSurfaceVariant = ScribeColors.Muted,
    error = ScribeColors.Error,
    onError = Color(0xFF2A0A0A),
    outline = Color(0xFF2A3038),
)

@Composable
fun ScribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScribeScheme, content = content)
}
