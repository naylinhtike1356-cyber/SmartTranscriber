package com.naylinhtike.smarttranscriber

import com.google.gson.JsonParser
import java.io.IOException

/** A successful HTTP response is not necessarily a complete transcript. */
internal object GeminiResponseParser {
    fun extract(jsonString: String): String {
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject
            val candidate = root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                ?: throw IOException("Gemini က စာသားမပေးပါ။ အသံအပိုင်းကို ပြန်စမ်းပါ။")
            val finishReason = candidate.get("finishReason")?.asString
            if (finishReason != "STOP") {
                throw IOException("စာသား မပြည့်စုံပါ ($finishReason)။ ဤအပိုင်းကို ပြန်စမ်းပါ။")
            }
            val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts")
                ?: throw IOException("Gemini response has no transcript")
            val text = parts.mapNotNull { element ->
                val part = element.asJsonObject
                if (part.get("thought")?.asBoolean == true) null else part.get("text")?.asString
            }.joinToString("").trim()
            if (text.isBlank()) throw IOException("Gemini က စာသားအလွတ်ပေးပါသည်။ ပြန်စမ်းပါ။")
            return text
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gemini response could not be read", e)
        }
    }
}
