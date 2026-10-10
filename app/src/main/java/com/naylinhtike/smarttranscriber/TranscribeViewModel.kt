package com.naylinhtike.smarttranscriber

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CancellationException

enum class JobFilter {
    ALL,
    ACTIVE,
    COMPLETED
}

class TranscribeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TranscribeRepository(application)
    val settingsRepo = SettingsRepository(application)

    private val _currentFilter = MutableStateFlow(JobFilter.ALL)
    val currentFilter: StateFlow<JobFilter> = _currentFilter.asStateFlow()

    private val _selectedJob = MutableStateFlow<TranscribeJob?>(null)
    val selectedJob: StateFlow<TranscribeJob?> = _selectedJob.asStateFlow()

    private val _snackBarMessages = MutableSharedFlow<String>()
    val snackBarMessages: SharedFlow<String> = _snackBarMessages.asSharedFlow()

    val allJobs: StateFlow<List<TranscribeJob>> = repository.jobsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredJobs: StateFlow<List<TranscribeJob>> = combine(
        repository.jobsFlow,
        _currentFilter
    ) { jobs, filter ->
        when (filter) {
            JobFilter.ALL -> jobs
            JobFilter.ACTIVE -> jobs.filter { it.isActive || it.state == TranscribeJob.STATE_PAUSED }
            JobFilter.COMPLETED -> jobs.filter { it.state == TranscribeJob.STATE_COMPLETED }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawGeminiKeys = settingsRepo.geminiKeysRawFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val notionApiKey = settingsRepo.notionApiKeyFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val notionDatabaseId = settingsRepo.notionDatabaseIdFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val notionEnabled = settingsRepo.notionEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val notionAutoSync = settingsRepo.notionAutoSyncFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val defaultLanguage = settingsRepo.defaultLanguageFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "my-MM")
    val customUpdateUrl = settingsRepo.customUpdateUrlFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val updateManager = UpdateManager(application)
    val updateState = updateManager.updateState

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.recoverActiveJobs()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackBarMessages.emit("အလုပ်ကျန်များကို ဆက်လုပ်ရန် App ကို ပြန်ဖွင့်ပါ သို့မဟုတ် Resume ကို နှိပ်ပါ။")
            }
        }
        viewModelScope.launch {
            repository.jobsFlow.collect { jobs ->
                _selectedJob.value?.id?.let { id -> _selectedJob.value = jobs.firstOrNull { it.id == id } }
            }
        }
    }

    fun setFilter(filter: JobFilter) {
        _currentFilter.value = filter
    }

    fun selectJob(job: TranscribeJob?) {
        _selectedJob.value = job
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            var tempFile: File? = null
            try {
                val app = getApplication<Application>()
                var fileName = "audio_${System.currentTimeMillis()}.mp3"
                var fileSize = 0L

                app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                        if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                    }
                }

                val importedFile = File.createTempFile("import_", ".audio", app.cacheDir)
                tempFile = importedFile
                withContext(Dispatchers.IO) {
                    (app.contentResolver.openInputStream(uri) ?: error("အသံဖိုင်ကို ဖွင့်မရပါ။")).use { input ->
                        importedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val durationMs = withContext(Dispatchers.IO) { readAudioDuration(importedFile) }
                val lang = settingsRepo.defaultLanguageFlow.first()

                repository.createJob(
                    sourceFile = importedFile,
                    displayName = fileName,
                    durationMs = durationMs,
                    language = lang
                )

                _snackBarMessages.emit("ဖိုင်ထည့်သွင်းပြီးပါပြီ: $fileName")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackBarMessages.emit("အသံဖိုင် ထည့်သွင်းရာတွင် အမှားဖြစ်ပါသည်: ${e.message}")
            } finally {
                tempFile?.delete()
            }
        }
    }

    private fun readAudioDuration(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun pauseJob(jobId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.pauseJob(jobId) }
            _snackBarMessages.emit("ခေတ္တရပ်နားထားပါသည်")
        }
    }

    fun resumeJob(jobId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.resumeJob(jobId) }
            _snackBarMessages.emit("ပြန်လည်စတင်နေပါသည်")
        }
    }

    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteJob(jobId) }
            if (_selectedJob.value?.id == jobId) {
                _selectedJob.value = null
            }
            _snackBarMessages.emit("ဖျက်ပြီးပါပြီ")
        }
    }

    fun syncJobToNotion(jobId: String) {
        viewModelScope.launch {
            val job = repository.getJob(jobId) ?: return@launch
            if (job.transcript.isBlank()) {
                _snackBarMessages.emit("စာသားမှတ်တမ်း မရှိသေးပါ။")
                return@launch
            }

            val apiKey = settingsRepo.notionApiKeyFlow.first()
            val dbId = settingsRepo.notionDatabaseIdFlow.first()

            if (apiKey.isBlank() || dbId.isBlank()) {
                _snackBarMessages.emit("Settings ထဲတွင် Notion API Key နှင့် Database ID ဖြည့်ပေးပါ။")
                return@launch
            }

            repository.updateJob(jobId) {
                it.copy(notionSyncState = TranscribeJob.NOTION_SYNCING, notionError = "")
            }
            _snackBarMessages.emit("Notion သို့ ပို့နေပါသည်...")

            val exporter = NotionExporter(apiKey, dbId)
            val metadata = mapOf(
                "Source" to job.fileName,
                "Duration" to job.formattedDuration(),
                "Language" to job.language,
                "Chunks" to "${job.totalChunks} parts"
            )

            val result = exporter.exportTranscript(
                title = job.fileName.substringBeforeLast('.').take(100),
                fullTranscript = job.transcript,
                metadata = metadata
            )

            if (result.success) {
                repository.updateJob(jobId) {
                    it.copy(
                        notionSyncState = TranscribeJob.NOTION_SYNCED,
                        notionPageUrl = result.pageUrl,
                        notionError = ""
                    )
                }
                _snackBarMessages.emit("Notion သို့ အောင်မြင်စွာ ပို့ဆောင်ပြီးပါပြီ!")
            } else {
                repository.updateJob(jobId) {
                    it.copy(
                        notionSyncState = TranscribeJob.NOTION_FAILED,
                        notionError = result.errorMessage
                    )
                }
                _snackBarMessages.emit("Notion အမှား: ${result.errorMessage.take(100)}")
            }
        }
    }

    fun saveSettings(
        geminiKeys: String,
        notionKey: String,
        notionDbId: String,
        notionEnabled: Boolean,
        notionAutoSync: Boolean,
        language: String,
        customUpdateUrl: String
    ) {
        viewModelScope.launch {
            settingsRepo.saveGeminiKeys(geminiKeys)
            settingsRepo.saveNotionApiKey(notionKey)
            settingsRepo.saveNotionDatabaseId(notionDbId)
            settingsRepo.saveNotionEnabled(notionEnabled)
            settingsRepo.saveNotionAutoSync(notionAutoSync)
            settingsRepo.saveDefaultLanguage(language)
            settingsRepo.saveCustomUpdateUrl(customUpdateUrl)
            _snackBarMessages.emit("ချိန်ညှိချက်များ အောင်မြင်စွာ သိမ်းဆည်းပြီးပါပြီ")
        }
    }

    fun exportTranscriptToUri(uri: Uri, text: String) {
        val success = TranscriptFileManager.writeToSelectedUri(getApplication(), uri, text)
        viewModelScope.launch {
            if (success) {
                _snackBarMessages.emit("စာသားဖိုင် အောင်မြင်စွာ သိမ်းဆည်းပြီးပါပြီ (.txt)")
            } else {
                _snackBarMessages.emit("စာသားဖိုင် သိမ်းဆည်းရာတွင် အမှားဖြစ်ပါသည်")
            }
        }
    }

    fun checkAppUpdate(currentVersion: String, customUrl: String? = null) {
        viewModelScope.launch {
            updateManager.checkUpdate(currentVersion, customUrl ?: settingsRepo.customUpdateUrlFlow.first())
        }
    }

    fun downloadAndInstallUpdate(release: AppReleaseInfo) {
        viewModelScope.launch {
            updateManager.downloadAndInstall(release)
        }
    }

    fun installDownloadedApk(apkFile: java.io.File) {
        updateManager.promptInstall(apkFile)
    }

    fun resumePendingInstall() {
        viewModelScope.launch { updateManager.resumePendingInstall() }
    }

    fun resetUpdateState() {
        updateManager.resetState()
    }
}
