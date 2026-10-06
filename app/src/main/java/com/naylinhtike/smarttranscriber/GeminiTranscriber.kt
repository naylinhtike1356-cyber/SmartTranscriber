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
import java.util.concurrent.atomic.AtomicInteger

class GeminiTranscriber(
    private val apiKeys: List<String>
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val currentKeyIndex = AtomicInteger(0)

    companion object {
        // High-speed, high-accuracy Google Gemini audio models
        private val MODELS = listOf(
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-pro"
        )
    }

    suspend fun transcribeChunk(audioWavFile: File, languageCode: String): String = withContext(Dispatchers.IO) {
        val keys = apiKeys.filter { it.isNotBlank() }
        if (keys.isEmpty()) {
            throw IllegalStateException("Gemini API Key မရှိပါ။ ကျေးဇူးပြု၍ Settings ထဲတွင် API Key ထည့်သွင်းပါ။")
        }

        val base64Audio = Base64.encodeToString(audioWavFile.readBytes(), Base64.NO_WRAP)
        val promptText = if (languageCode.startsWith("my", ignoreCase = true)) {
            """
            သင်သည် မြန်မာဘာသာစကားနှင့် ဗုဒ္ဓဘာသာ တရားတော်များ (Dhamma sermons) ကို အထူးကျွမ်းကျင်သော Audio Transcriber ဖြစ်သည်။
            ဤ အသံဖိုင် (တရားတော်/စကားပြော) ကို ကြားရသည့်အတိုင်း တိကျမှန်ကန်သော မြန်မာယူနီကုဒ် (Myanmar Unicode) စာသားအဖြစ် အပြည့်အစုံ ကူးရေးပေးပါ။
            
            အရေးကြီးသော ညွှန်ကြားချက်များ:
            ၁။ ပါဠိတော်များ၊ ဗုဒ္ဓဒေသနာတော် အခေါ်အဝေါ်များ၊ ဓမ္မဝေါဟာရများကို အသံထွက်အတိုင်း အမှန်ဆုံး ရေးသားပါ။
            ၂။ အသံဖိုင်ထဲတွင် မပါရှိသော ရှင်းလင်းချက်၊ အနှစ်ချုပ်၊ မှတ်ချက်၊ Markdown quotation အပိုများကို လုံးဝ မထည့်ပါနှင့်။
            ၃။ အသံဖိုင်ထဲမှ စကားလုံးများကိုသာ စကားလုံးအပြည့်အစုံ စာသားထုတ်ပေးပါ။
            """.trimIndent()
        } else {
            "Please transcribe the following audio accurately word-for-word. Output ONLY the raw transcribed text. Do not summarize or add commentary."
        }

        val requestBodyJson = buildRequestBody(promptText, base64Audio)
        var lastException: Exception? = null

        // Support unlimited duration audio (e.g. 1-3 hour Dhamma talks) with automatic key rotation and backoff retries
        for (attempt in 1..4) {
            val keyCount = keys.size
            for (offset in 0 until keyCount) {
                val keyIdx = (currentKeyIndex.get() + offset) % keyCount
                val apiKey = keys[keyIdx]

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
                            // Advance key index so load is shared fairly across keys
                            currentKeyIndex.incrementAndGet()
                            return@withContext if (parsedText.isNotBlank()) parsedText else "[no speech]"
                        }

                        if (code == 429) {
                            // Rate limit on this key, switch to next key
                            lastException = IOException("Gemini API Rate Limit (429) hit. Rotating key...")
                            break
                        } else if (code == 404) {
                            // Model not supported on this endpoint, try next fallback model
                            continue
                        } else {
                            lastException = IOException("Gemini API Error (HTTP $code): ${responseText.take(200)}")
                        }
                    } catch (e: Exception) {
                        lastException = e
                    }
                }
            }

            // If all keys hit 429 rate limit, wait gracefully and retry instead of failing
            if (attempt < 4) {
                delay(attempt * 4000L) // Wait 4s, 8s, 12s backoff
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
