package com.naylinhtike.smarttranscriber

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotionExporter(
    private val apiKey: String,
    private val databaseId: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class ExportResult(
        val success: Boolean,
        val pageUrl: String = "",
        val errorMessage: String = ""
    )

    suspend fun exportTranscript(
        title: String,
        fullTranscript: String,
        metadata: Map<String, String> = emptyMap(),
        onProgress: (suspend (completedBlocks: Int, totalBlocks: Int) -> Unit)? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanDbId = databaseId.trim().replace("-", "")

        if (cleanKey.isBlank()) {
            return@withContext ExportResult(false, errorMessage = "Notion API Key မရှိပါ။ ကျေးဇူးပြု၍ Settings တွင် ထည့်ပါ။")
        }
        if (cleanDbId.isBlank()) {
            return@withContext ExportResult(false, errorMessage = "Notion Database ID မရှိပါ။ ကျေးဇူးပြု၍ Settings တွင် ထည့်ပါ။")
        }

        try {
            // Step 1: Create Page with initial metadata callout block
            val initialBlocks = mutableListOf<JsonObject>()

            // Header metadata callout
            val metaBuilder = StringBuilder()
            metaBuilder.append("🎙 Audio Transcription\n")
            metadata.forEach { (k, v) -> metaBuilder.append("• $k: $v\n") }
            val metaCallout = createCalloutBlock(metaBuilder.toString().trim())
            initialBlocks.add(metaCallout)

            // Step 2: Convert full transcript into safe paragraph blocks (max 1900 chars per block)
            val transcriptBlocks = prepareTranscriptBlocks(fullTranscript)

            // Take the first batch (up to 95 blocks) for page creation
            val firstBatch = (initialBlocks + transcriptBlocks.take(90)).take(100)
            val remainingBlocks = transcriptBlocks.drop(90)

            val createPagePayload = buildCreatePagePayload(cleanDbId, title, firstBatch)
            val pageResponse = executeNotionRequest(
                url = "https://api.notion.com/v1/pages",
                method = "POST",
                bodyJson = createPagePayload,
                apiKey = cleanKey
            )

            if (!pageResponse.isSuccessful) {
                val err = pageResponse.body?.string().orEmpty()
                return@withContext ExportResult(false, errorMessage = "Notion page create failed (${pageResponse.code}): ${err.take(250)}")
            }

            val pageJson = gson.fromJson(pageResponse.body?.string(), JsonObject::class.java)
            val pageId = pageJson.get("id")?.asString.orEmpty()
            val pageUrl = pageJson.get("url")?.asString.orEmpty()

            if (pageId.isBlank()) {
                return@withContext ExportResult(false, errorMessage = "Failed to obtain Notion page ID")
            }

            // Step 3: Append remaining blocks in batches of 100 with 350ms delay & 1900-char guarantee
            var appendedCount = firstBatch.size
            val totalBlocks = initialBlocks.size + transcriptBlocks.size
            onProgress?.invoke(appendedCount, totalBlocks)

            val chunks = remainingBlocks.chunked(100)
            for (chunk in chunks) {
                delay(350) // Notion 3 requests/sec rate limit buffer

                val appendPayload = buildAppendBlocksPayload(chunk)
                val appendResponse = executeWithRetry(
                    url = "https://api.notion.com/v1/blocks/$pageId/children",
                    method = "PATCH",
                    bodyJson = appendPayload,
                    apiKey = cleanKey
                )

                if (!appendResponse.isSuccessful) {
                    val err = appendResponse.body?.string().orEmpty()
                    return@withContext ExportResult(false, pageUrl = pageUrl, errorMessage = "Transcript partially synced, append failed (${appendResponse.code}): ${err.take(250)}")
                }

                appendedCount += chunk.size
                onProgress?.invoke(appendedCount, totalBlocks)
            }

            return@withContext ExportResult(true, pageUrl = pageUrl)
        } catch (e: Exception) {
            return@withContext ExportResult(false, errorMessage = e.message ?: "Sync error")
        }
    }

    private fun prepareTranscriptBlocks(text: String): List<JsonObject> {
        val blocks = mutableListOf<JsonObject>()
        val lines = text.split("\n")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            // Notion block limit is 2000 chars. Strictly split at 1900 to ensure zero rejection.
            if (trimmed.length > 1900) {
                trimmed.chunked(1900).forEach { part ->
                    blocks.add(createParagraphBlock(part))
                }
            } else {
                blocks.add(createParagraphBlock(trimmed))
            }
        }

        return blocks
    }

    private fun createParagraphBlock(text: String): JsonObject {
        val block = JsonObject()
        block.addProperty("object", "block")
        block.addProperty("type", "paragraph")

        val paragraph = JsonObject()
        val richTextArray = JsonArray()
        val textObj = JsonObject()
        textObj.addProperty("type", "text")

        val content = JsonObject()
        content.addProperty("content", text.take(1900))
        textObj.add("text", content)
        richTextArray.add(textObj)

        paragraph.add("rich_text", richTextArray)
        block.add("paragraph", paragraph)
        return block
    }

    private fun createCalloutBlock(text: String): JsonObject {
        val block = JsonObject()
        block.addProperty("object", "block")
        block.addProperty("type", "callout")

        val callout = JsonObject()
        val icon = JsonObject()
        icon.addProperty("type", "emoji")
        icon.addProperty("emoji", "🎙")
        callout.add("icon", icon)

        val richTextArray = JsonArray()
        val textObj = JsonObject()
        textObj.addProperty("type", "text")
        val content = JsonObject()
        content.addProperty("content", text.take(1900))
        textObj.add("text", content)
        richTextArray.add(textObj)

        callout.add("rich_text", richTextArray)
        block.add("callout", callout)
        return block
    }

    private fun buildCreatePagePayload(dbId: String, title: String, children: List<JsonObject>): String {
        val root = JsonObject()

        val parent = JsonObject()
        parent.addProperty("database_id", dbId)
        root.add("parent", parent)

        val properties = JsonObject()
        val nameProp = JsonObject()
        val titleArray = JsonArray()
        val textObj = JsonObject()
        textObj.addProperty("type", "text")
        val content = JsonObject()
        content.addProperty("content", title.take(200))
        textObj.add("text", content)
        titleArray.add(textObj)
        nameProp.add("title", titleArray)
        properties.add("Name", nameProp)
        root.add("properties", properties)

        val childrenArray = JsonArray()
        children.forEach { childrenArray.add(it) }
        root.add("children", childrenArray)

        return gson.toJson(root)
    }

    private fun buildAppendBlocksPayload(blocks: List<JsonObject>): String {
        val root = JsonObject()
        val childrenArray = JsonArray()
        blocks.forEach { childrenArray.add(it) }
        root.add("children", childrenArray)
        return gson.toJson(root)
    }

    private fun executeNotionRequest(url: String, method: String, bodyJson: String, apiKey: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Notion-Version", "2022-06-28")
            .addHeader("Content-Type", "application/json")
            .method(method, bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(request).execute()
    }

    private suspend fun executeWithRetry(url: String, method: String, bodyJson: String, apiKey: String): okhttp3.Response {
        var attempts = 0
        while (true) {
            attempts++
            val response = executeNotionRequest(url, method, bodyJson, apiKey)
            if (response.code == 429 && attempts <= 3) {
                delay(1000L * attempts)
                continue
            }
            return response
        }
    }
}
