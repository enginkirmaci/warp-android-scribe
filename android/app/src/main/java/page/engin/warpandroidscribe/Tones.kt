package com.engin.warpandroidscribe

data class DictationTone(val value: String, val label: String)

/** The five cleanup tones Superwhisper's profile exposes. */
val TONES = listOf(
    DictationTone("casual", "Casual"),
    DictationTone("semi-casual", "Semi-casual"),
    DictationTone("balanced", "Balanced"),
    DictationTone("semi-formal", "Semi-formal"),
    DictationTone("formal", "Formal"),
)

const val DEFAULT_TONE = "semi-formal"

fun isSupportedTone(value: String): Boolean = TONES.any { it.value == value }

fun toneLabel(value: String): String =
    TONES.firstOrNull { it.value == value }?.label ?: value
