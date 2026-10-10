package com.naylinhtike.smarttranscriber

import okio.ByteString.Companion.toByteString
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class GeminiApiException(message: String, val retryable: Boolean) : IOException(message)

class GeminiTranscriber(
    apiKeys: List<String>,
    private val endpoint: String = "https://generativelanguage.googleapis.com/v1beta",
    initialModel: String = ""
) {
    private val keys = apiKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private var keyIndex = 0
    private val unavailableModels = mutableSetOf<Pair<Int, String>>()
    private var preferredModel = initialModel.takeIf { it in MODELS } ?: MODELS.first()
    internal var lastSuccessfulModel: String = ""
        private set
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    companion object {
        // Current audio-capable Flash, with a legacy option for existing accounts.
        private val MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-3.5-flash-lite")
    }

    suspend fun transcribeChunk(audioWavFile: File, languageCode: String): String = try {
        withTimeout(240_000L) { transcribeChunkRequest(audioWavFile, languageCode) }
    } catch (_: TimeoutCancellationException) {
        throw GeminiApiException("အသံအပိုင်း ပြောင်းရန် အချိန်ကျော်သွားသည်။ ပြီးထားသောအပိုင်းများမှ ဆက်ကြိုးစားမည်။", true)
    }

    private suspend fun transcribeChunkRequest(audioWavFile: File, languageCode: String): String = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) throw GeminiApiException("Settings တွင် Gemini API Key ထည့်ပါ။", false)
        check(audioWavFile.length() in 45..8_000_000) { "Invalid audio chunk size" }
        val audio = audioWavFile.readBytes().toByteString().base64()
        var requests = 0
        var lastError: GeminiApiException? = null
        val models = listOf(preferredModel) + MODELS.filter { it != preferredModel }
        for (offset in keys.indices) {
            val index = (keyIndex + offset) % keys.size
            for (model in models) {
                if (index to model in unavailableModels) continue
                // Bound total requests. A quota error must not cause an hours-long model loop.
                if (requests++ >= 4) throw lastError ?: GeminiApiException("Gemini ကို ခဏစောင့်ပြီး ပြန်စမ်းပါ။", true)
                val request = Request.Builder()
                    .url("$endpoint/models/$model:generateContent")
                    .header("x-goog-api-key", keys[index])
                    .post(buildRequestBody(audio, languageCode, model).toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                var rateLimited = false
                try {
                    client.newCall(request).awaitResponse().use { response ->
                        if (response.isSuccessful) {
                            val text = GeminiResponseParser.extract(response.body?.string().orEmpty())
                            preferredModel = model
                            lastSuccessfulModel = model
                            keyIndex = (index + 1) % keys.size
                            return@withContext text
                        }
                        when (response.code) {
                            404 -> {
                                unavailableModels += index to model
                                lastError = GeminiApiException("Gemini model အသုံးပြုမရပါ။ API Key ကို စစ်ဆေးပါ။", false)
                            }
                            401, 403 -> {
                                lastError = GeminiApiException("API Key သို့မဟုတ် model အသုံးပြုခွင့် မမှန်ပါ။ Settings တွင် စစ်ဆေးပါ။", false)
                            }
                            429 -> {
                                lastError = GeminiApiException("Gemini quota ပြည့်နေသည် (429)။ ပြီးထားသောအပိုင်းများကို သိမ်းထားပြီး ခဏစောင့်ကာ ပြန်ကြိုးစားမည်။", true)
                                rateLimited = true
                            }
                            408, 500, 502, 503, 504 -> lastError = GeminiApiException("Gemini server ယာယီမရပါ (HTTP ${response.code})။ ပြန်ကြိုးစားမည်။", true)
                            else -> throw GeminiApiException("Gemini request မအောင်မြင်ပါ (HTTP ${response.code})။ API Key နှင့် model အသုံးပြုခွင့်ကို စစ်ဆေးပါ။", false)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GeminiApiException) {
                    throw e
                } catch (e: IOException) {
                    // Never expose request URLs, response bodies, or credentials in the UI.
                    lastError = GeminiApiException("ကွန်ရက်ပြတ်ခြင်း သို့မဟုတ် စာသားမပြည့်စုံခြင်း ဖြစ်ပါသည်။ ပြီးထားသောအပိုင်းများမှ ဆက်ကြိုးစားမည်။", true)
                }
                if (rateLimited) break
            }
        }
        throw lastError ?: GeminiApiException("Gemini model အသုံးပြုမရပါ။", false)
    }

    private fun buildRequestBody(base64Audio: String, languageCode: String, model: String): String {
        val prompt = if (languageCode.startsWith("my", true)) {
            "သင်သည် မြန်မာဘာသာနှင့် ဗုဒ္ဓဘာသာတရားတော်များကို ကူးရေးသူဖြစ်သည်။ အသံထဲက စကားအားလုံးကို ကြားရသည့်အတိုင်း မြန်မာယူနီကုဒ်ဖြင့် အပြည့်အစုံရေးပါ။ ပါဠိ၊ ဓမ္မဝေါဟာရများကို အသံထွက်အတိုင်းရေးပါ။ အနှစ်ချုပ်၊ ရှင်းလင်းချက်၊ စိတ်ကူးဖြည့်စွက်ချက်၊ Markdown မထည့်ပါနှင့်။ စကားမကြားရလျှင် [no speech] ဟုသာရေးပါ။ မရှင်းသည့်နေရာတွင် [မရှင်းလင်း] ဟုရေးပြီး နောက်စကားကို ဆက်ရေးပါ။"
        } else {
            "Transcribe ALL speech verbatim in language $languageCode. Output only the transcript, without summary or commentary. Mark unclear speech briefly and continue. If there is no speech, output exactly [no speech]."
        }
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
            add(JsonObject().apply {
                add("inlineData", JsonObject().apply {
                    addProperty("mimeType", "audio/wav")
                    addProperty("data", base64Audio)
                })
            })
        }
        val root = JsonObject().apply {
            add("contents", JsonArray().apply { add(JsonObject().apply { add("parts", parts) }) })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.2)
                addProperty("maxOutputTokens", 16384)
                if (model == "gemini-3.8-flash") add("thinkingConfig", JsonObject().apply { addProperty("thinkingLevel", "low") })
                if (model == "gemini-2.5-flash") add("thinkingConfig", JsonObject().apply { addProperty("thinkingBudget", 0) })
            })
        }
        return Gson().toJson(root)
    }
}
