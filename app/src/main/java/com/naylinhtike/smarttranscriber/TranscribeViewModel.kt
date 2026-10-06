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
    val defaultLanguage = settingsRepo.defaultLanguageFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "my-MM")

    fun setFilter(filter: JobFilter) {
        _currentFilter.value = filter
    }

    fun selectJob(job: TranscribeJob?) {
        _selectedJob.value = job
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
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

                val tempFile = File(app.cacheDir, "import_${System.currentTimeMillis()}_$fileName")
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val durationMs = readAudioDuration(tempFile)
                val lang = defaultLanguage.value

                repository.createJob(
                    sourceFile = tempFile,
                    displayName = fileName,
                    durationMs = durationMs,
                    language = lang
                )

                tempFile.delete()
                _snackBarMessages.emit("ဖိုင်ထည့်သွင်းပြီးပါပြီ: $fileName")
            } catch (e: Exception) {
                _snackBarMessages.emit("အသံဖိုင် ထည့်သွင်းရာတွင် အမှားဖြစ်ပါသည်: ${e.message}")
            }
        }
    }

    private fun readAudioDuration(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun pauseJob(jobId: String) {
        viewModelScope.launch {
            repository.pauseJob(jobId)
            _snackBarMessages.emit("ခေတ္တရပ်နားထားပါသည်")
        }
    }

    fun resumeJob(jobId: String) {
        viewModelScope.launch {
            repository.resumeJob(jobId)
            _snackBarMessages.emit("ပြန်လည်စတင်နေပါသည်")
        }
    }

    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            repository.deleteJob(jobId)
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
        language: String
    ) {
        viewModelScope.launch {
            settingsRepo.saveGeminiKeys(geminiKeys)
            settingsRepo.saveNotionApiKey(notionKey)
            settingsRepo.saveNotionDatabaseId(notionDbId)
            settingsRepo.saveDefaultLanguage(language)
            _snackBarMessages.emit("ချိန်ညှိချက်များ သိမ်းဆည်းပြီးပါပြီ")
        }
    }
}
