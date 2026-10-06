package com.naylinhtike.smarttranscriber

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiTranscriber(
    private val apiKeys: List<String>
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private val MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )
    }

    suspend fun transcribeChunk(audioWavFile: File, languageCode: String): String = withContext(Dispatchers.IO) {
        val keys = apiKeys.filter { it.isNotBlank() }
        if (keys.isEmpty()) {
            throw IllegalStateException("Gemini API Key မရှိပါ။ ကျေးဇူးပြု၍ Settings ထဲတွင် API Key ထည့်သွင်းပါ။")
        }

        val base64Audio = Base64.encodeToString(audioWavFile.readBytes(), Base64.NO_WRAP)
        val promptText = if (languageCode.startsWith("my", ignoreCase = true)) {
            "Please transcribe the following Burmese audio accurately into written Myanmar script (Unicode). Output ONLY the transcribed text. Do not summarize, explain, or translate."
        } else {
            "Please transcribe the following audio accurately. Output ONLY the transcribed text. Do not summarize or add markdown formatting."
        }

        val requestBodyJson = buildRequestBody(promptText, base64Audio)
        var lastException: Exception? = null

        for (apiKey in keys) {
            for (model in MODELS) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    val code = response.code
                    val responseText = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        val parsedText = extractText(responseText)
                        if (parsedText.isNotBlank()) {
                            return@withContext parsedText
                        } else {
                            // If audio is completely silent or no speech detected
                            return@withContext "[no speech]"
                        }
                    }

                    if (code == 429) {
                        // Rate limit exceeded on this key, break to next key
                        delay(500)
                        break
                    } else if (code == 404) {
                        // Model not supported, try next model
                        continue
                    } else {
                        lastException = IOException("Gemini API Error (HTTP $code): ${responseText.take(200)}")
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }
        }

        throw lastException ?: IOException("Failed to transcribe audio chunk with available Gemini API keys.")
    }

    private fun buildRequestBody(prompt: String, base64Audio: String): String {
        val root = JsonObject()
        val contents = com.google.gson.JsonArray()
        val content = JsonObject()
        val parts = com.google.gson.JsonArray()

        // Text prompt part
        val textPart = JsonObject()
        textPart.addProperty("text", prompt)
        parts.add(textPart)

        // Audio inline data part
        val audioPart = JsonObject()
        val inlineData = JsonObject()
        inlineData.addProperty("mimeType", "audio/wav")
        inlineData.addProperty("data", base64Audio)
        audioPart.add("inlineData", inlineData)
        parts.add(audioPart)

        content.add("parts", parts)
        contents.add(content)
        root.add("contents", contents)

        return gson.toJson(root)
    }

    private fun extractText(jsonString: String): String {
        return try {
            val json = gson.fromJson(jsonString, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates") ?: return ""
            if (candidates.size() == 0) return ""
            val first = candidates.get(0).asJsonObject
            val content = first.getAsJsonObject("content") ?: return ""
            val parts = content.getAsJsonArray("parts") ?: return ""
            val textBuilder = StringBuilder()
            for (i in 0 until parts.size()) {
                val part = parts.get(i).asJsonObject
                if (part.has("text")) {
                    textBuilder.append(part.get("text").asString)
                }
            }
            textBuilder.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }
}
