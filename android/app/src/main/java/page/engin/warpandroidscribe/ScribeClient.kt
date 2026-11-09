package com.engin.warpandroidscribe

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ScribeException(message: String) : IOException(message)

data class ToneOption(val value: String, val label: String)

data class Replacement(val original: String, val with: String)

data class Vocabulary(
    val terms: List<String>,
    val replacements: List<Replacement>,
)

data class ProcessingMode(
    val value: String,
    val label: String,
    val downloadBytes: Long,
    val missing: List<String>,
)

data class ServerSettings(
    val tone: String,
    val tones: List<ToneOption>,
    val languages: List<DictationLanguage>,
    val vocabulary: Vocabulary,
    val processing: String,
    val processingModes: List<ProcessingMode>,
    val processingTarget: String?,
    val processingError: String?,
    val downloadBytes: Long,
    val downloadTotal: Long,
    val downloadPercent: Int,
)

class ScribeClient(private val prefs: ScribePrefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    data class Result(val text: String, val language: String, val duration: Double)

    fun transcribe(file: File, language: String, tone: String): Result {
        val query = buildString {
            append("lang=").append(language)
            if (tone.isNotBlank()) append("&tone=").append(tone)
        }
        val request = Request.Builder()
            .url("${prefs.endpoint}/transcribe?$query")
            .post(file.asRequestBody("audio/wav".toMediaType()))
            .header("Authorization", "Bearer ${prefs.token}")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, payload)
            val json = runCatching { JSONObject(payload) }
                .getOrElse { throw ScribeException("The server did not return JSON.") }
            return Result(
                text = json.optString("text"),
                language = json.optString("language", language),
                duration = json.optDouble("duration", 0.0),
            )
        }
    }

    fun health(): String {
        val request = Request.Builder()
            .url("${prefs.endpoint}/health")
            .header("Authorization", "Bearer ${prefs.token}")
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, payload)
            return runCatching { JSONObject(payload).optString("version", "?") }.getOrDefault("?")
        }
    }

    fun settings(): ServerSettings {
        val request = Request.Builder()
            .url("${prefs.endpoint}/settings")
            .header("Authorization", "Bearer ${prefs.token}")
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, payload)
            return parseSettings(payload)
        }
    }

    fun saveVocabulary(vocabulary: Vocabulary): ServerSettings {
        val body = JSONObject().apply {
            put("terms", JSONArray(vocabulary.terms))
            put(
                "replacements",
                JSONArray().apply {
                    vocabulary.replacements.forEach { replacement ->
                        put(
                            JSONObject()
                                .put("original", replacement.original)
                                .put("with", replacement.with),
                        )
                    }
                },
            )
        }
        return putJson("/settings/vocabulary", body)
    }

    fun setProcessing(mode: String): ServerSettings {
        return putJson("/settings/processing", JSONObject().put("mode", mode), method = "POST")
    }

    private fun putJson(path: String, body: JSONObject, method: String = "PUT"): ServerSettings {
        val request = Request.Builder()
            .url("${prefs.endpoint}$path")
            .method(
                method,
                body.toString().toRequestBody("application/json".toMediaType()),
            )
            .header("Authorization", "Bearer ${prefs.token}")
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, payload)
            return parseSettings(payload)
        }
    }

    private fun parseSettings(payload: String): ServerSettings {
        val json = runCatching { JSONObject(payload) }
            .getOrElse { throw ScribeException("The server did not return settings.") }
        val vocabulary = json.optJSONObject("vocabulary")
        val terms = vocabulary?.optJSONArray("terms").toStringList()
        val replacements = vocabulary?.optJSONArray("replacements")
            .toStringListOfObjects()
            .mapNotNull { item ->
                val original = item.optString("original")
                val with = item.optString("with")
                if (original.isBlank() || with.isBlank()) null
                else Replacement(original, with)
            }
        val modes = json.optJSONArray("processing_modes").toStringListOfObjects()
            .mapNotNull { item ->
                val value = item.optString("value")
                if (value.isBlank()) null
                else ProcessingMode(
                    value = value,
                    label = item.optString("label", value),
                    downloadBytes = item.optLong("download_bytes", 0),
                    missing = item.optJSONArray("missing").toStringList(),
                )
            }
        return ServerSettings(
            tone = json.optString("tone", DEFAULT_TONE),
            tones = json.optJSONArray("tones").toStringListOfObjects().mapNotNull { item ->
                val value = item.optString("value")
                if (value.isBlank()) null else ToneOption(value, item.optString("label", value))
            },
            languages = json.optJSONArray("languages").toStringListOfObjects().mapNotNull { item ->
                val value = item.optString("value")
                if (value.isBlank()) null else DictationLanguage(value, item.optString("label", value))
            },
            vocabulary = Vocabulary(terms, replacements),
            processing = json.optString("processing", "cloud"),
            processingModes = modes,
            processingTarget = if (json.isNull("processing_target")) {
                null
            } else {
                json.optString("processing_target").takeIf { it.isNotBlank() }
            },
            processingError = if (json.isNull("processing_error")) {
                null
            } else {
                json.optString("processing_error").takeIf { it.isNotBlank() }
            },
            downloadBytes = json.optLong("download_bytes", 0),
            downloadTotal = json.optLong("download_total", 0),
            downloadPercent = json.optInt("download_percent", 0),
        )
    }

    private fun failure(code: Int, payload: String): ScribeException {
        val detail = runCatching { JSONObject(payload).optString("error") }.getOrDefault("")
        val message = when (code) {
            401 -> "The server rejected the token (401)."
            400 -> "The server rejected a setting (400)."
            409 -> "A processing change is already running (409)."
            413 -> "The audio is too long or too large (413)."
            502 -> "The server could not reach Superwhisper (502)."
            504 -> "The server timed out (504)."
            else -> "Server error ($code)."
        }
        return ScribeException(if (detail.isBlank()) message else "$message $detail")
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optString(index).takeIf { it.isNotBlank() }
    }
}

private fun JSONArray?.toStringListOfObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index) }
}
