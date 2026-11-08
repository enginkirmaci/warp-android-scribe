package com.engin.warpandroidscribe

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

class ScribeException(message: String) : IOException(message)

class ScribeClient(private val prefs: ScribePrefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    data class Result(val text: String, val language: String, val duration: Double)

    fun transcribe(file: File, language: String): Result {
        val request = Request.Builder()
            .url("${prefs.endpoint}/transcribe?lang=$language")
            .post(file.asRequestBody("audio/wav".toMediaType()))
            .header("Authorization", "Bearer ${prefs.token}")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw failure(response.code, payload)
            val json = runCatching { JSONObject(payload) }
                .getOrElse { throw ScribeException("Sunucu JSON döndürmedi.") }
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

    private fun failure(code: Int, payload: String): ScribeException {
        val detail = runCatching { JSONObject(payload).optString("error") }.getOrDefault("")
        val message = when (code) {
            401 -> "Sunucu token'ı reddetti (401)."
            413 -> "Ses çok uzun veya büyük (413)."
            502 -> "Sunucu Superwhisper'a ulaşamadı (502)."
            504 -> "Sunucu zaman aşımına uğradı (504)."
            else -> "Sunucu hatası ($code)."
        }
        return ScribeException(if (detail.isBlank()) message else "$message $detail")
    }
}
