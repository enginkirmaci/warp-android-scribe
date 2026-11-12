package com.engin.warpandroidscribe.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

object ScribeColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181E)
    /** One flat fill for every key; no key carries an outline or a raised tone. */
    val Key = Color(0xFF1A1E24)
    val Text = Color(0xFFE5E7EB)
    val Muted = Color(0xFF9CA3AF)
    /** The fallback primary when the phone offers no wallpaper accent. */
    val Green = Color(0xFF22C55E)
    val Recording = Color(0xFFEF4444)
    val RecordingBg = Color(0xFF2A1414)
    val Error = Color(0xFFF87171)
    val ErrorBg = Color(0xFF2A1215)
}

/**
 * The phone's own wallpaper palette, so the keyboard's two primaries (the
 * Dictate pill and Enter) wear the colour the rest of the system uses.
 */
class ScribeAccent(val base: Color, val on: Color)

/**
 * Reads the accent the phone's wallpaper palette uses, then the platform's
 * [android.R.color.system_accent1_500]. A ROM without a dynamic palette, or an
 * API below 31, keeps the app's own green.
 */
@Composable
fun rememberScribeAccent(): ScribeAccent {
    val context = LocalContext.current
    return remember(context) { systemAccent(context) }
}

private fun systemAccent(context: Context): ScribeAccent {
    val base = paletteAccent(context) ?: platformAccent(context)
    if (base != null) return ScribeAccent(base, onColor(base))
    return ScribeAccent(ScribeColors.Green, ScribeColors.Background)
}

/** The colour picked in the phone's wallpaper palette (Android 12 dynamic colour). */
private fun paletteAccent(context: Context): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val raw = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            "theme_customization_overlay_packages",
        )
    }.getOrNull() ?: return null
    val hex = runCatching {
        JSONObject(raw).optString("android.theme.customization.accent_color")
    }.getOrNull()
    val argb = hex?.takeIf { it.isNotEmpty() }?.toLongOrNull(16)?.toInt() ?: return null
    return Color(argb)
}

private fun platformAccent(context: Context): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return systemColor(context, android.R.color.system_accent1_500)
}

private fun systemColor(context: Context, id: Int): Color? =
    runCatching { Color(ContextCompat.getColor(context, id)) }.getOrNull()

/** Whichever of the two label colours keeps the better contrast on [color]. */
private fun onColor(color: Color): Color {
    fun contrast(label: Color): Float {
        val a = color.luminance() + 0.05f
        val b = label.luminance() + 0.05f
        return max(a, b) / min(a, b)
    }
    return if (contrast(ScribeColors.Background) >= contrast(ScribeColors.Text)) {
        ScribeColors.Background
    } else {
        ScribeColors.Text
    }
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
