package com.engin.warpandroidscribe.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ScribeColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181E)
    val Key = Color(0xFF1A1E24)
    /** The action keys a dictation leans on: space, enter, delete. */
    val KeyRaised = Color(0xFF232A34)
    val KeyRaisedEdge = Color(0xFF323B49)
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

/** One type scale: 28 / 20 / 17 / 14 / 12 / 10 sp. Nothing else. */
object ScribeText {
    val PageTitle = 28.sp
    val SheetTitle = 20.sp
    val Key = 17.sp
    val Label = 14.sp
    val Body = 12.sp
    val Caption = 10.sp
}

/** One corner set: 8 keys and chips, 12 surfaces, 21 pills. */
object ScribeShape {
    val Chip = RoundedCornerShape(8.dp)
    val Surface = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(21.dp)
}

/** One height set: 40 chips, 44 keys and rows, 48 actions, 52 fields, 96 cards. */
object ScribeSize {
    val Chip = 40.dp
    val Key = 44.dp
    val Tap = 48.dp
    val Field = 52.dp
    val Card = 96.dp
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
    secondaryContainer = ScribeColors.Green.copy(alpha = 0.18f),
    onSecondaryContainer = ScribeColors.Green,
    error = ScribeColors.Error,
    onError = Color(0xFF2A0A0A),
    outline = Color(0xFF2A3038),
)

@Composable
fun ScribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScribeScheme, content = content)
}
