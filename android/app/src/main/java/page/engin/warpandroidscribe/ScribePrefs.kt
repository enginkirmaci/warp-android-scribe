package com.engin.warpandroidscribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ScribePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("scribe", Context.MODE_PRIVATE)

    /**
     * The endpoint is one of two fixed paths; the token is shared. A stored
     * `tls` from the earlier host/port form maps to REMOTE on first read.
     */
    var mode: ConnectionMode
        get() = when (prefs.getString(KEY_MODE, null)) {
            ConnectionMode.LOCAL.key -> ConnectionMode.LOCAL
            ConnectionMode.REMOTE.key -> ConnectionMode.REMOTE
            else -> if (prefs.getBoolean(KEY_TLS_LEGACY, false)) {
                ConnectionMode.REMOTE
            } else {
                ConnectionMode.LOCAL
            }
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.key).commit()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
            ?.takeIf(::isSupportedLanguage)
            ?: DEFAULT_LANGUAGE
        set(value) {
            prefs.edit()
                .putString(KEY_LANGUAGE, if (isSupportedLanguage(value)) value else DEFAULT_LANGUAGE)
                .apply()
        }

    /**
     * The cleanup tone sent with every request. It lives on the phone, not in
     * the server profile, so the keyboard keeps the owner's choice even when
     * the NAS Superwhisper window changes it.
     */
    var tone: String
        get() = prefs.getString(KEY_TONE, DEFAULT_TONE)
            ?.takeIf(::isSupportedTone)
            ?: DEFAULT_TONE
        set(value) {
            prefs.edit()
                .putString(KEY_TONE, if (isSupportedTone(value)) value else DEFAULT_TONE)
                .apply()
        }

    /**
     * Superwhisper or Hex. The bridge holds both engines and picks one per
     * request, so switching does not need a server restart.
     */
    var engine: TranscriptionEngine
        get() = TranscriptionEngine.fromId(prefs.getString(KEY_ENGINE, null))
        set(value) {
            prefs.edit().putString(KEY_ENGINE, value.id).apply()
        }

    var localUrl: String
        get() = prefs.getString(KEY_LOCAL_URL, null) ?: DEFAULT_LOCAL_URL
        set(value) {
            prefs.edit().putString(KEY_LOCAL_URL, value.trim().trimEnd('/')).apply()
        }

    var remoteUrl: String
        get() = prefs.getString(KEY_REMOTE_URL, null) ?: DEFAULT_REMOTE_URL
        set(value) {
            prefs.edit().putString(KEY_REMOTE_URL, value.trim().trimEnd('/')).apply()
        }

    val endpoint: String
        get() = when (mode) {
            ConnectionMode.LOCAL -> localUrl.trim().trimEnd('/')
            ConnectionMode.REMOTE -> remoteUrl.trim().trimEnd('/')
        }

    val isConfigured: Boolean
        get() = token.isNotBlank() && endpoint.isNotBlank()

    /** A private address needs Android 17's ACCESS_LOCAL_NETWORK; remote does not. */
    val usesLocalNetwork: Boolean
        get() = mode == ConnectionMode.LOCAL

    /** Slash commands shown under the compact top bar; edited in Settings. */
    val shortcuts: List<String>
        get() = prefs.getString(KEY_SHORTCUTS, null)
            ?.split(',', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(MAX_SHORTCUTS)
            ?: DEFAULT_SHORTCUTS

    /** One committed write for the settings screen; `apply()` can be lost
     *  when the activity is backgrounded immediately after the test. */
    fun saveSettings(
        mode: ConnectionMode,
        token: String,
        language: String,
        tone: String,
        engine: TranscriptionEngine,
        shortcuts: String,
        localUrl: String,
        remoteUrl: String,
    ): Boolean = prefs.edit()
        .putString(KEY_MODE, mode.key)
        .putString(KEY_TOKEN, token.trim())
        .putString(KEY_LANGUAGE, if (isSupportedLanguage(language)) language else DEFAULT_LANGUAGE)
        .putString(KEY_TONE, if (isSupportedTone(tone)) tone else DEFAULT_TONE)
        .putString(KEY_ENGINE, engine.id)
        .putString(KEY_SHORTCUTS, shortcuts.trim())
        .putString(KEY_LOCAL_URL, localUrl.trim().trimEnd('/'))
        .putString(KEY_REMOTE_URL, remoteUrl.trim().trimEnd('/'))
        .commit()

    fun addRecentTranscript(text: String) {
        val next = (listOf(text) + recentTranscripts().filter { it != text }).take(5)
        prefs.edit().putString(KEY_RECENT, JSONArray(next).toString()).apply()
    }

    fun removeRecentTranscript(text: String) {
        val next = recentTranscripts().filter { it != text }
        prefs.edit().putString(KEY_RECENT, JSONArray(next).toString()).apply()
    }

    fun recentTranscripts(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** The emoji taps that earned a place in the panel's most-used row. */
    fun emojiUsage(): List<EmojiUse> {
        val raw = prefs.getString(KEY_EMOJI_USAGE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val emoji = item.optString("emoji")
                    if (emoji.isBlank()) continue
                    add(
                        EmojiUse(
                            emoji = emoji,
                            count = item.optInt("count", 0),
                            lastUsed = item.optLong("last_used", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Counts one tap and returns the row in display order: most taps first, the
     * more recently used emoji breaking a tie. Older entries fall off the cap.
     */
    fun recordEmojiUse(emoji: String): List<EmojiUse> {
        val current = emojiUsage()
        val now = System.currentTimeMillis()
        val taps = current.firstOrNull { it.emoji == emoji }?.count ?: 0
        val next = (current.filter { it.emoji != emoji } + EmojiUse(emoji, taps + 1, now))
            .sortedWith(
                compareByDescending<EmojiUse> { it.count }.thenByDescending { it.lastUsed },
            )
            .take(MOST_USED_EMOJI_LIMIT)
        val array = JSONArray()
        next.forEach { use ->
            array.put(
                JSONObject()
                    .put("emoji", use.emoji)
                    .put("count", use.count)
                    .put("last_used", use.lastUsed),
            )
        }
        prefs.edit().putString(KEY_EMOJI_USAGE, array.toString()).apply()
        return next
    }

    companion object {
        const val DEFAULT_LOCAL_URL = "http://<nas-lan>:8787"
        const val DEFAULT_REMOTE_URL = "https://<nas-host>"
        const val MAX_SHORTCUTS = 8

        val DEFAULT_SHORTCUTS = listOf("/goal", "/compact")

        private const val KEY_MODE = "mode"
        private const val KEY_TOKEN = "token"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TONE = "tone"
        private const val KEY_ENGINE = "engine"
        private const val KEY_TLS_LEGACY = "tls"
        private const val KEY_RECENT = "recent_transcripts"
        private const val KEY_SHORTCUTS = "shortcuts"
        private const val KEY_LOCAL_URL = "local_url"
        private const val KEY_REMOTE_URL = "remote_url"
        private const val KEY_EMOJI_USAGE = "emoji_usage"

    }
}

private val ConnectionMode.key: String
    get() = when (this) {
        ConnectionMode.LOCAL -> "local"
        ConnectionMode.REMOTE -> "remote"
    }
