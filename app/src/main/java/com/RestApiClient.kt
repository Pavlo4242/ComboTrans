package com.ComboTrans

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class RestApiClient {

    private val client = OkHttpClient()
    private val gson = Gson()

    companion object {
        private const val TAG = "RestApiClient"
        private const val API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/%s/models/%s:generateContent"
        private const val SYSTEM_PROMPT_TEXT = "You are an expert translator. The user will provide audio input. Transcribe the audio and translate it to English. Provide only the translated text as your response."
        private const val RECORDED_AUDIO_MIMETYPE = "audio/wav"
    }

    suspend fun generateContent(
        context: Context,
        apiKey: String,
        apiVersion: String,
        modelName: String,
        audioData: ByteArray
    ): Result<RestGenerateContentResponse> = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivityManager.activeNetworkInfo?.isConnectedOrConnecting != true) {
            return@withContext Result.failure(IOException("No internet connection."))
        }

        val systemInstruction = RestContent(
            parts = listOf(Part(text = SYSTEM_PROMPT_TEXT)),
            role = "system"
        )
        
        val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val userContent = RestContent(
            parts = listOf(
                Part(text = "Please translate this audio."),
                Part(inlineData = InlineData(mimeType = RECORDED_AUDIO_MIMETYPE, data = audioBase64))
            )
        )

        val requestBody = RestGenerateContentRequest(
            contents = listOf(userContent),
            systemInstruction = systemInstruction
        )

        val url = String.format(API_URL_TEMPLATE, apiVersion, modelName)
        val jsonBody = gson.toJson(requestBody)
        val request = Request.Builder()
            .url(url)
            .header("X-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "Request URL: $url")
        Log.d(TAG, "Request Body: ${jsonBody.take(200)}...") // Log only the start

        try {
            val response = client.newCall(request).execute()
            val responseBodyString = response.body?.string()

            if (response.isSuccessful && responseBodyString != null) {
                Log.d(TAG, "Response Success: ${response.code}")
                val parsedResponse = gson.fromJson(responseBodyString, RestGenerateContentResponse::class.java)
                Result.success(parsedResponse)
            } else {
                val errorMsg = "API call failed with code ${response.code}: $responseBodyString"
                Log.e(TAG, errorMsg)
                Result.failure(IOException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network request failed", e)
            Result.failure(e)
        }
    }
}
