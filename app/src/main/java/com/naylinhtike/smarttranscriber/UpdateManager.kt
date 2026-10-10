package com.naylinhtike.smarttranscriber

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val fileSize: Long = 0L,
    val sha256: String? = null
) {
    fun formattedSize(): String = if (fileSize <= 0) "" else "%.1f MB".format(fileSize / (1024.0 * 1024.0))
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
        .callTimeout(15, TimeUnit.MINUTES)
        .build()
    private val gson = Gson()
    private val installPreferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    companion object {
        const val DEFAULT_GITHUB_REPO = "naylinhtike1356-cyber/SmartTranscriber"
        const val DEFAULT_RELEASE_API = "https://api.github.com/repos/naylinhtike1356-cyber/SmartTranscriber/releases/latest"
    }

    fun resetState() {
        if (_updateState.value !is UpdateUiState.Downloading) _updateState.value = UpdateUiState.Idle
    }

    suspend fun checkUpdate(currentVersion: String, customUrl: String? = null) {
        if (_updateState.value is UpdateUiState.Downloading ||
            _updateState.value is UpdateUiState.Checking ||
            _updateState.value is UpdateUiState.ReadyToInstall) return
        _updateState.value = UpdateUiState.Checking
        withContext(Dispatchers.IO) {
            try {
                val url = customUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_RELEASE_API
                require(Uri.parse(url).scheme == "https") { "Update URL must use HTTPS" }
                val request = Request.Builder().url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SmartTranscriber-App").build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Update စစ်ဆေးမှု မအောင်မြင်ပါ (HTTP ${response.code})")
                    if (_updateState.value !is UpdateUiState.Checking) return@withContext
                    val json = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                    val version = (json.get("tag_name")?.asString ?: json.get("version")?.asString.orEmpty())
                        .trim().trimStart('v', 'V')
                    if (!UpdateValidation.isNewerVersion(currentVersion, version)) {
                        _updateState.value = UpdateUiState.AlreadyUpToDate
                        return@withContext
                    }
                    var downloadUrl = json.get("downloadUrl")?.asString.orEmpty()
                    var size = json.get("fileSize")?.asLong ?: 0L
                    var digest = json.get("sha256")?.takeUnless { it.isJsonNull }?.asString
                    if (downloadUrl.isBlank()) {
                        val assets = json.getAsJsonArray("assets")?.map { it.asJsonObject }.orEmpty()
                        val apk = assets.firstOrNull {
                            it.get("name")?.asString?.startsWith("SmartTranscriber-") == true &&
                                it.get("name")?.asString?.endsWith(".apk", true) == true
                        } ?: assets.firstOrNull { it.get("name")?.asString?.endsWith(".apk", true) == true }
                        downloadUrl = apk?.get("browser_download_url")?.asString.orEmpty()
                        size = apk?.get("size")?.asLong ?: 0L
                        digest = apk?.get("digest")?.takeUnless { it.isJsonNull }?.asString?.removePrefix("sha256:")
                    }
                    require(Uri.parse(downloadUrl).scheme == "https") { "APK ဒေါင်းလုဒ်လင့်ခ် မရှိသေးပါ။" }
                    _updateState.value = UpdateUiState.UpdateAvailable(AppReleaseInfo(
                        version, json.get("name")?.asString ?: "ဗားရှင်းအသစ် $version",
                        json.get("body")?.asString ?: json.get("releaseNotes")?.asString.orEmpty(),
                        downloadUrl, size, digest
                    ))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (_updateState.value is UpdateUiState.Checking) _updateState.value = UpdateUiState.Error(e.message.orEmpty())
            }
        }
    }

    suspend fun downloadAndInstall(release: AppReleaseInfo) {
        if (_updateState.value is UpdateUiState.Downloading) return
        _updateState.value = UpdateUiState.Downloading(0f, 0, release.fileSize)
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val temporary = File(directory, "update.part.apk")
            try {
                require(Uri.parse(release.downloadUrl).scheme == "https") { "APK download must use HTTPS" }
                val request = Request.Builder().url(release.downloadUrl)
                    .header("User-Agent", "SmartTranscriber-App").build()
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "ဒေါင်းလုဒ် မအောင်မြင်ပါ (HTTP ${response.code})" }
                    val body = response.body ?: error("Empty APK response")
                    val total = if (release.fileSize > 0) release.fileSize else body.contentLength()
                    var downloaded = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count: Int
                            while (input.read(buffer).also { count = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, count)
                                downloaded += count
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 200) {
                                    val percent = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                    _updateState.value = UpdateUiState.Downloading(percent, downloaded, total)
                                    lastReport = now
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    check(total <= 0 || downloaded == total) { "APK ဒေါင်းလုဒ် မပြည့်စုံပါ။ ပြန်စမ်းပါ။" }
                    release.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                        check(UpdateValidation.sha256(temporary).equals(expected.removePrefix("sha256:"), true)) {
                            "APK checksum မကိုက်ညီပါ။"
                        }
                    }
                    validatePackage(temporary)
                    val apkFile = File(directory, "update.apk")
                    apkFile.delete()
                    check(temporary.renameTo(apkFile)) { "APK ဖိုင်ကို သိမ်းမရပါ။" }
                    _updateState.value = UpdateUiState.ReadyToInstall(apkFile)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _updateState.value = UpdateUiState.Error("ဒေါင်းလုဒ် အမှား: ${e.message}")
            } finally {
                temporary.delete()
            }
        }
    }

    fun promptInstall(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                installPreferences.edit().putBoolean("awaiting_permission", true).apply()
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            installPreferences.edit().putBoolean("awaiting_permission", false).apply()
        } catch (e: Exception) {
            _updateState.value = UpdateUiState.Error("Install အမှား: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("ဒေါင်းလုဒ်ဖိုင်သည် APK အမှန် မဟုတ်ပါ။")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName) { "APK သည် အခြား App အတွက် ဖြစ်နေပါသည်။" }
        val remoteCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(remoteCode > currentCode) { "APK ဗားရှင်းသည် လက်ရှိထက် မမြင့်ပါ။" }
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
        val expected = certificates(installed)
        check(expected.isNotEmpty() && certificates(archive) == expected) { "APK လက်မှတ်သည် လက်ရှိ App နှင့် မကိုက်ညီပါ။" }
    }

    suspend fun resumePendingInstall() {
        if (!installPreferences.getBoolean("awaiting_permission", false)) return
        val file = (_updateState.value as? UpdateUiState.ReadyToInstall)?.apkFile
            ?: File(context.cacheDir, "updates/update.apk")
        if (!file.exists()) {
            installPreferences.edit().putBoolean("awaiting_permission", false).apply()
            return
        }
        try {
            withContext(Dispatchers.IO) { validatePackage(file) }
            _updateState.value = UpdateUiState.ReadyToInstall(file)
            if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) promptInstall(file)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            installPreferences.edit().putBoolean("awaiting_permission", false).apply()
            _updateState.value = UpdateUiState.Error(e.message.orEmpty())
        }
    }
}
