package com.naylinhtike.smarttranscriber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
    val version: String,
    val title: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long = 0L
) {
    fun formattedSize(): String {
        if (fileSize <= 0) return ""
        val mb = fileSize / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }
}

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpdateAvailable(val release: AppReleaseInfo) : UpdateUiState()
    object AlreadyUpToDate : UpdateUiState()
    data class Downloading(val progressPercent: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateUiState()
    data class ReadyToInstall(val apkFile: File) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

class UpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    companion object {
        const val DEFAULT_GITHUB_REPO = "naylinhtike1356-cyber/SmartTranscriber"
        const val DEFAULT_RELEASE_API = "https://api.github.com/repos/naylinhtike1356-cyber/SmartTranscriber/releases/latest"
    }

    fun resetState() {
        _updateState.value = UpdateUiState.Idle
    }

    suspend fun checkUpdate(currentVersion: String, customUrl: String? = null) {
        _updateState.value = UpdateUiState.Checking

        withContext(Dispatchers.IO) {
            try {
                val url = if (!customUrl.isNullOrBlank()) customUrl.trim() else DEFAULT_RELEASE_API
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "SmartTranscriber-App")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _updateState.value = UpdateUiState.Error("စစ်ဆေးမှု မအောင်မြင်ပါ (HTTP ${response.code})")
                        return@withContext
                    }

                    val body = response.body?.string().orEmpty()
                    val json = gson.fromJson(body, JsonObject::class.java)

                    // Parse GitHub release JSON or Generic release JSON
                    val tagName = json.get("tag_name")?.asString
                        ?: json.get("version")?.asString
                        ?: ""
                    val remoteVersion = tagName.trimStart('v', 'V').trim()

                    val title = json.get("name")?.asString ?: "ဗားရှင်းအသစ် $tagName"
                    val notes = json.get("body")?.asString
                        ?: json.get("releaseNotes")?.asString
                        ?: "လုပ်ဆောင်ချက်အသစ်များနှင့် တိုးတက်မှုများ ပါဝင်ပါသည်"

                    var downloadUrl = json.get("downloadUrl")?.asString.orEmpty()
                    var fileSize = 0L

                    if (downloadUrl.isBlank() && json.has("assets")) {
                        val assets = json.getAsJsonArray("assets")
                        for (element in assets) {
                            val assetObj = element.asJsonObject
                            val name = assetObj.get("name")?.asString.orEmpty()
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = assetObj.get("browser_download_url")?.asString.orEmpty()
                                fileSize = assetObj.get("size")?.asLong ?: 0L
                                break
                            }
                        }
                    }

                    if (remoteVersion.isNotBlank() && isNewerVersion(currentVersion, remoteVersion)) {
                        val release = AppReleaseInfo(
                            version = remoteVersion,
                            title = title,
                            releaseNotes = notes,
                            downloadUrl = downloadUrl,
                            fileSize = fileSize
                        )
                        _updateState.value = UpdateUiState.UpdateAvailable(release)
                    } else {
                        _updateState.value = UpdateUiState.AlreadyUpToDate
                    }
                }
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Error("ကွန်ရက်ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${e.message ?: "Unknown error"}")
            }
        }
    }

    suspend fun downloadAndInstall(release: AppReleaseInfo) {
        if (release.downloadUrl.isBlank()) {
            _updateState.value = UpdateUiState.Error("ဒေါင်းလုဒ်လင့်ခ် မရှိသေးပါ")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "SmartTranscriber_v${release.version}.apk")

                val request = Request.Builder()
                    .url(release.downloadUrl)
                    .header("User-Agent", "SmartTranscriber-App")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _updateState.value = UpdateUiState.Error("ဒေါင်းလုဒ် မအောင်မြင်ပါ (HTTP ${response.code})")
                        return@withContext
                    }

                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val totalBytes = if (release.fileSize > 0) release.fileSize else body.contentLength()
                    var downloadedBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            var lastProgressReport = 0L

                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastProgressReport > 200 || downloadedBytes == totalBytes) {
                                    val percent = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f
                                    _updateState.value = UpdateUiState.Downloading(percent, downloadedBytes, totalBytes)
                                    lastProgressReport = now
                                }
                            }
                            output.flush()
                        }
                    }

                    _updateState.value = UpdateUiState.ReadyToInstall(apkFile)
                }
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Error("ဒေါင်းလုဒ် အမှား: ${e.message}")
            }
        }
    }

    fun promptInstall(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            _updateState.value = UpdateUiState.Error("Install လုပ်ရာတွင် အမှားဖြစ်ပါသည်: ${e.message}")
        }
    }

    private fun isNewerVersion(current: String, remote: String): Boolean {
        val cleanCurrent = current.trimStart('v', 'V').trim()
        val cleanRemote = remote.trimStart('v', 'V').trim()

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
