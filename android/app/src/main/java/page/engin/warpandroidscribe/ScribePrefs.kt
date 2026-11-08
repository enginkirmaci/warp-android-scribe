package com.engin.warpandroidscribe

import android.content.Context
import org.json.JSONArray

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
        get() = if (prefs.getString(KEY_LANGUAGE, "tr") == "auto") "auto" else "tr"
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, if (value == "auto") "auto" else "tr").apply()
        }

    val endpoint: String
        get() = when (mode) {
            ConnectionMode.LOCAL -> "http://$LOCAL_HOST:$LOCAL_PORT"
            ConnectionMode.REMOTE -> "https://$REMOTE_HOST"
        }

    val isConfigured: Boolean
        get() = token.isNotBlank()

    /** A private address needs Android 17's ACCESS_LOCAL_NETWORK; remote does not. */
    val usesLocalNetwork: Boolean
        get() = mode == ConnectionMode.LOCAL

    /** One committed write for the settings screen; `apply()` can be lost
     *  when the activity is backgrounded immediately after the test. */
    fun saveSettings(
        mode: ConnectionMode,
        token: String,
        language: String,
    ): Boolean = prefs.edit()
        .putString(KEY_MODE, mode.key)
        .putString(KEY_TOKEN, token.trim())
        .putString(KEY_LANGUAGE, if (language == "auto") "auto" else "tr")
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

    companion object {
        const val LOCAL_HOST = "<nas-lan>"
        const val LOCAL_PORT = 8787
        const val REMOTE_HOST = "<nas-host>"

        private const val KEY_MODE = "mode"
        private const val KEY_TOKEN = "token"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TLS_LEGACY = "tls"
        private const val KEY_RECENT = "recent_transcripts"

    }
}

private val ConnectionMode.key: String
    get() = when (this) {
        ConnectionMode.LOCAL -> "local"
        ConnectionMode.REMOTE -> "remote"
    }
