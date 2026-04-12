package com.agentshell.data.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// WhisperService
//
// Speech-to-text via OpenAI Whisper API.
// Mirrors flutter/lib/data/services/whisper_service.dart.
// ---------------------------------------------------------------------------
@Singleton
class WhisperService @Inject constructor() {

    private companion object {
        const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val WHISPER_MODEL = "gpt-4o-mini-transcribe"
    }

    // Dedicated OkHttp client with longer timeouts for audio uploads
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe the audio file at [audioFilePath] using the OpenAI Whisper API.
     *
     * @param audioFilePath Absolute path to the recorded .m4a file.
     * @param apiKey        OpenAI API key (Bearer token).
     * @return The transcribed text string, or null on error.
     */
    suspend fun transcribe(audioFilePath: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(audioFilePath)
                if (!file.exists()) return@withContext null

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "audio.m4a",
                        file.asRequestBody("audio/m4a".toMediaTypeOrNull()),
                    )
                    .addFormDataPart("model", WHISPER_MODEL)
                    .build()

                val request = Request.Builder()
                    .url(WHISPER_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                json.optString("text").takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
}
