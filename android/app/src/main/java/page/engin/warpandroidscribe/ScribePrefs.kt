package com.engin.warpandroidscribe

import android.content.Context
import org.json.JSONArray

class ScribePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("scribe", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        set(value) {
            prefs.edit().putString(KEY_HOST, normalizeHost(value)).apply()
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

    var tls: Boolean
        get() = prefs.getBoolean(KEY_TLS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TLS, value).apply()
        }

    var lastTranscript: String
        get() = prefs.getString(KEY_LAST, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST, value).apply()
        }

    val endpoint: String
        get() = "${if (tls) "https" else "http"}://$host:$port"

    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()

    /** A private address needs Android 17's ACCESS_LOCAL_NETWORK; a domain does not. */
    val usesLocalNetwork: Boolean
        get() = isLocalHost(host)

    /**
     * One committed edit for the whole server form. `apply()` is asynchronous,
     * and a test that immediately backgrounds the app can lose the write; the
     * settings screen calls this before its health request.
     */
    fun saveConnection(
        host: String,
        port: Int,
        token: String,
        language: String,
        tls: Boolean,
    ): Boolean = prefs.edit()
        .putString(KEY_HOST, normalizeHost(host))
        .putInt(KEY_PORT, port)
        .putString(KEY_TOKEN, token.trim())
        .putString(KEY_LANGUAGE, if (language == "auto") "auto" else "tr")
        .putBoolean(KEY_TLS, tls)
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
        const val DEFAULT_HOST = "<nas-lan>"
        const val DEFAULT_PORT = 8787
        const val PUBLIC_HOST = "<nas-host>"
        const val PUBLIC_PORT = 443

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TLS = "tls"
        private const val KEY_LAST = "last_transcript"
        private const val KEY_RECENT = "recent_transcripts"

        fun isLocalHost(host: String): Boolean {
            val value = host.trim().lowercase()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
            if (value == "localhost" || value.endsWith(".local")) return true
            if (value.startsWith("10.") || value.startsWith("192.168.") ||
                value.startsWith("127.")
            ) {
                return true
            }
            if (value.startsWith("172.")) {
                return value.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } ?: false
            }
            return false
        }

        private fun normalizeHost(value: String): String =
            value.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
    }
}
