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
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// WhisperService
//
// Speech-to-text via OpenAI Whisper API.
// Mirrors flutter/lib/data/services/whisper_service.dart.
// ---------------------------------------------------------------------------

/** Result of a transcription attempt. */
sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class RetryableError(val message: String) : TranscriptionResult()
    data class FatalError(val message: String) : TranscriptionResult()
}

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
     * @return [TranscriptionResult] with the transcribed text or error details.
     */
    suspend fun transcribe(audioFilePath: String, apiKey: String): TranscriptionResult =
        withContext(Dispatchers.IO) {
            try {
                val file = File(audioFilePath)
                if (!file.exists()) return@withContext TranscriptionResult.FatalError("Audio file not found")

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

                if (!response.isSuccessful) {
                    val code = response.code
                    val msg = response.body?.string()?.take(200) ?: "HTTP $code"
                    // Auth errors (401/403) are not retryable; server/network errors are
                    return@withContext if (code in 400..499 && code !in 500..599)
                        TranscriptionResult.FatalError("API error: $msg")
                    else
                        TranscriptionResult.RetryableError("Server error ($code): $msg")
                }

                val body = response.body?.string()
                    ?: return@withContext TranscriptionResult.RetryableError("Empty response from API")

                val json = JSONObject(body)
                val text = json.optString("text", "")
                if (text.isEmpty()) TranscriptionResult.Success("")
                else TranscriptionResult.Success(text)
            } catch (e: SocketTimeoutException) {
                TranscriptionResult.RetryableError("Request timed out: ${e.message}")
            } catch (e: IOException) {
                TranscriptionResult.RetryableError("Network error: ${e.message}")
            } catch (e: Exception) {
                TranscriptionResult.RetryableError("Unexpected error: ${e.message}")
            }
        }
}
