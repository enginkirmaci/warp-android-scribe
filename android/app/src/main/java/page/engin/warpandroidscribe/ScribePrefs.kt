package com.engin.warpandroidscribe

import android.content.Context
import org.json.JSONArray

class ScribePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("scribe", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        set(value) {
            prefs.edit().putString(KEY_HOST, value.trim()).apply()
        }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
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

    var lastTranscript: String
        get() = prefs.getString(KEY_LAST, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST, value).apply()
        }

    val endpoint: String
        get() = "http://$host:$port"

    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()

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
        const val DEFAULT_HOST = "<nas-lan>"
        const val DEFAULT_PORT = 8787

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LAST = "last_transcript"
        private const val KEY_RECENT = "recent_transcripts"
    }
}
