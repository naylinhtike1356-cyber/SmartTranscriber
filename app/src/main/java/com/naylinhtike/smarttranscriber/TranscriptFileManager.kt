package com.naylinhtike.smarttranscriber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object TranscriptFileManager {

    /**
     * Prepares a text file in cache and launches an ACTION_VIEW chooser intent
     * so that any text reader or notes app on the phone can open it immediately.
     */
    fun openInExternalReader(context: Context, fileName: String, transcriptText: String) {
        try {
            val safeBaseName = fileName.substringBeforeLast('.').ifBlank { "transcript" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val targetFileName = "${safeBaseName}.txt"

            val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
            val file = File(dir, targetFileName)
            file.writeText(transcriptText, Charsets.UTF_8)

            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooserIntent = Intent.createChooser(viewIntent, "စာဖတ်သည့် အက်ပ်ဖြင့် ဖွင့်ပါ (Open with Reader)")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "စာဖတ်အက်ပ် ဖွင့်ရာတွင် အမှားဖြစ်ပါသည်: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Writes the transcript content directly to a user-selected SAF Uri
     */
    fun writeToSelectedUri(context: Context, uri: Uri, transcriptText: String): Boolean {
        return try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            stream.use { output ->
                output.write(transcriptText.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
