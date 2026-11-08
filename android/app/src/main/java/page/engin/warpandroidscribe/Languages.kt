package com.engin.warpandroidscribe

data class DictationLanguage(val code: String, val name: String)

/**
 * Codes accepted by the bridge (`validate_language`) and by Superwhisper's
 * language pin. `auto` is the detector's own choice.
 */
val SUPPORTED_LANGUAGES = listOf(
    DictationLanguage("auto", "Auto-detect"),
    DictationLanguage("en", "English"),
    DictationLanguage("tr", "Turkish"),
    DictationLanguage("de", "German"),
    DictationLanguage("fr", "French"),
    DictationLanguage("es", "Spanish"),
    DictationLanguage("it", "Italian"),
    DictationLanguage("pt", "Portuguese"),
    DictationLanguage("nl", "Dutch"),
    DictationLanguage("pl", "Polish"),
    DictationLanguage("ru", "Russian"),
    DictationLanguage("uk", "Ukrainian"),
    DictationLanguage("ar", "Arabic"),
    DictationLanguage("hi", "Hindi"),
    DictationLanguage("ja", "Japanese"),
    DictationLanguage("ko", "Korean"),
    DictationLanguage("zh", "Chinese"),
)

const val DEFAULT_LANGUAGE = "tr"

fun isSupportedLanguage(code: String): Boolean =
    SUPPORTED_LANGUAGES.any { it.code == code }

fun languageName(code: String): String =
    SUPPORTED_LANGUAGES.firstOrNull { it.code == code }?.name ?: code.uppercase()

fun languageShort(code: String): String =
    if (code == "auto") "AUTO" else code.substringBefore('-').uppercase()
