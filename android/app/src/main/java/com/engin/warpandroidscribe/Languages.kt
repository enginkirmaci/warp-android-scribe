package com.engin.warpandroidscribe

data class DictationLanguage(val code: String, val name: String)

/**
 * Every language Superwhisper 1.2.0 exposes (`general show` options.languages).
 * `auto` is the detector's own choice. The bridge accepts any code in this
 * shape, so a future Superwhisper release can add one without an app update.
 */
val SUPPORTED_LANGUAGES = listOf(
    DictationLanguage("auto", "Automatic"),
    DictationLanguage("en", "English"),
    DictationLanguage("en-GB", "English (British)"),
    DictationLanguage("fr", "French"),
    DictationLanguage("de", "German"),
    DictationLanguage("ja", "Japanese"),
    DictationLanguage("es", "Spanish"),
    DictationLanguage("zh", "Chinese"),
    DictationLanguage("zh-TW", "Chinese (Traditional)"),
    DictationLanguage("nl", "Dutch"),
    DictationLanguage("hi", "Hindi"),
    DictationLanguage("ru", "Russian"),
    DictationLanguage("ko", "Korean"),
    DictationLanguage("it", "Italian"),
    DictationLanguage("uk", "Ukrainian"),
    DictationLanguage("pl", "Polish"),
    DictationLanguage("pt", "Portuguese"),
    DictationLanguage("tr", "Turkish"),
    DictationLanguage("el", "Greek"),
    DictationLanguage("cs", "Czech"),
    DictationLanguage("sv", "Swedish"),
    DictationLanguage("no", "Norwegian"),
    DictationLanguage("da", "Danish"),
    DictationLanguage("id", "Indonesian"),
    DictationLanguage("af", "Afrikaans"),
    DictationLanguage("sq", "Albanian"),
    DictationLanguage("am", "Amharic"),
    DictationLanguage("ar", "Arabic"),
    DictationLanguage("hy", "Armenian"),
    DictationLanguage("as", "Assamese"),
    DictationLanguage("az", "Azerbaijani"),
    DictationLanguage("ba", "Bashkir"),
    DictationLanguage("eu", "Basque"),
    DictationLanguage("be", "Belarusian"),
    DictationLanguage("bn", "Bengali"),
    DictationLanguage("bs", "Bosnian"),
    DictationLanguage("br", "Breton"),
    DictationLanguage("bg", "Bulgarian"),
    DictationLanguage("yue", "Cantonese"),
    DictationLanguage("ca", "Catalan"),
    DictationLanguage("hr", "Croatian"),
    DictationLanguage("et", "Estonian"),
    DictationLanguage("fo", "Faroese"),
    DictationLanguage("fi", "Finnish"),
    DictationLanguage("gl", "Galician"),
    DictationLanguage("ka", "Georgian"),
    DictationLanguage("gu", "Gujarati"),
    DictationLanguage("ht", "Haitian"),
    DictationLanguage("ha", "Hausa"),
    DictationLanguage("haw", "Hawaiian"),
    DictationLanguage("he", "Hebrew"),
    DictationLanguage("hu", "Hungarian"),
    DictationLanguage("is", "Icelandic"),
    DictationLanguage("jw", "Javanese"),
    DictationLanguage("kn", "Kannada"),
    DictationLanguage("kk", "Kazakh"),
    DictationLanguage("km", "Khmer"),
    DictationLanguage("lo", "Lao"),
    DictationLanguage("la", "Latin"),
    DictationLanguage("lv", "Latvian"),
    DictationLanguage("ln", "Lingala"),
    DictationLanguage("lt", "Lithuanian"),
    DictationLanguage("lb", "Luxembourgish"),
    DictationLanguage("mk", "Macedonian"),
    DictationLanguage("mg", "Malagasy"),
    DictationLanguage("ms", "Malay"),
    DictationLanguage("ml", "Malayalam"),
    DictationLanguage("mt", "Maltese"),
    DictationLanguage("mi", "Maori"),
    DictationLanguage("mr", "Marathi"),
    DictationLanguage("mn", "Mongolian"),
    DictationLanguage("my", "Myanmar"),
    DictationLanguage("ne", "Nepali"),
    DictationLanguage("nn", "Nynorsk"),
    DictationLanguage("oc", "Occitan"),
    DictationLanguage("ps", "Pashto"),
    DictationLanguage("fa", "Persian"),
    DictationLanguage("pa", "Punjabi"),
    DictationLanguage("ro", "Romanian"),
    DictationLanguage("sa", "Sanskrit"),
    DictationLanguage("sr", "Serbian"),
    DictationLanguage("sn", "Shona"),
    DictationLanguage("sd", "Sindhi"),
    DictationLanguage("si", "Sinhala"),
    DictationLanguage("sk", "Slovak"),
    DictationLanguage("sl", "Slovenian"),
    DictationLanguage("so", "Somali"),
    DictationLanguage("su", "Sundanese"),
    DictationLanguage("sw", "Swahili"),
    DictationLanguage("tl", "Tagalog"),
    DictationLanguage("tg", "Tajik"),
    DictationLanguage("ta", "Tamil"),
    DictationLanguage("tt", "Tatar"),
    DictationLanguage("te", "Telugu"),
    DictationLanguage("th", "Thai"),
    DictationLanguage("bo", "Tibetan"),
    DictationLanguage("tk", "Turkmen"),
    DictationLanguage("ur", "Urdu"),
    DictationLanguage("uz", "Uzbek"),
    DictationLanguage("vi", "Vietnamese"),
    DictationLanguage("cy", "Welsh"),
    DictationLanguage("yi", "Yiddish"),
    DictationLanguage("yo", "Yoruba"),
)

const val DEFAULT_LANGUAGE = "tr"

/**
 * The curated head of every picker. The rest of [SUPPORTED_LANGUAGES] follows
 * in English-name order, because the Superwhisper order after these is not
 * alphabetical in any language the user reads.
 */
val COMMON_LANGUAGE_CODES = listOf(
    "auto", "en", "en-GB", "fr", "de", "ja", "es", "zh", "zh-TW", "nl",
    "hi", "ru", "ko", "it", "uk", "pl", "pt", "tr",
)

/** The order the pickers render: common first, then everything else by name. */
fun displayLanguages(all: List<DictationLanguage> = SUPPORTED_LANGUAGES): List<DictationLanguage> {
    val common = COMMON_LANGUAGE_CODES.mapNotNull { code ->
        all.firstOrNull { it.code == code }
    }
    val rest = all
        .filterNot { it.code in COMMON_LANGUAGE_CODES }
        .sortedBy { it.name.lowercase() }
    return common + rest
}

private val LANGUAGE_CODE = Regex("^(auto|[A-Za-z]{2,3}(-[A-Za-z]{2})?)$")

fun isSupportedLanguage(code: String): Boolean = LANGUAGE_CODE.matches(code)

fun languageName(code: String): String =
    SUPPORTED_LANGUAGES.firstOrNull { it.code == code }?.name ?: code

fun languageShort(code: String): String =
    if (code == "auto") "AUTO" else code.uppercase()
