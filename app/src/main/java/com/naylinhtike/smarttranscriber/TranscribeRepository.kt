package com.naylinhtike.smarttranscriber

import android.content.Context
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class TranscribeRepository(private val context: Context) {

    companion object {
        const val WORK_TAG = "transcribe_work"
        private val lock = Any()
        private val sharedJobs = MutableStateFlow<List<TranscribeJob>>(emptyList())
    }

    private val rootDir = File(context.filesDir, "transcriptions").apply { mkdirs() }
    private val gson = Gson()
    private val workManager = WorkManager.getInstance(context)

    private val _jobsFlow = sharedJobs
    val jobsFlow: Flow<List<TranscribeJob>> = _jobsFlow.asStateFlow()

    init {
        reloadJobs()
    }

    fun reloadJobs() {
        _jobsFlow.value = listJobs()
    }

    fun getJobDir(id: String): File {
        return File(rootDir, id).apply { mkdirs() }
    }

    fun listJobs(): List<TranscribeJob> = synchronized(lock) {
        val dirs = rootDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        dirs.mapNotNull { dir ->
            val jobFile = File(dir, "job.json")
            if (jobFile.exists()) {
                try {
                    AtomicFile(jobFile).openRead().use { stream ->
                        val json = stream.bufferedReader().readText()
                        gson.fromJson(json, TranscribeJob::class.java)
                    }
                } catch (e: Exception) {
                    null
                }
            } else null
        }.sortedByDescending { it.createdAt }
    }

    fun getJob(id: String): TranscribeJob? = synchronized(lock) {
        val jobFile = File(File(rootDir, id), "job.json")
        if (!jobFile.exists()) return null
        try {
            AtomicFile(jobFile).openRead().use { stream ->
                gson.fromJson(stream.bufferedReader().readText(), TranscribeJob::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun updateJob(id: String, transform: (TranscribeJob) -> TranscribeJob): TranscribeJob? = synchronized(lock) {
        val current = getJob(id) ?: return null
        val updated = transform(current)
        saveJob(updated)
        reloadJobs()
        return updated
    }

    private fun saveJob(job: TranscribeJob) = synchronized(lock) {
        val dir = getJobDir(job.id)
        val file = File(dir, "job.json")
        val atomic = AtomicFile(file)
        val json = gson.toJson(job)
        val out = atomic.startWrite()
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    suspend fun createJob(
        sourceFile: File,
        displayName: String,
        durationMs: Long,
        language: String
    ): TranscribeJob = withContext(Dispatchers.IO) {
        val id = generateJobId(sourceFile, language)
        val jobDir = getJobDir(id)

        // If job already exists, return existing
        getJob(id)?.let { return@withContext it }

        // Copy source file into job directory
        val targetSource = File(jobDir, "source_${sourceFile.name}")
        sourceFile.inputStream().use { input ->
            targetSource.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val job = TranscribeJob(
            id = id,
            fileName = displayName,
            sourceName = targetSource.name,
            fileSizeBytes = targetSource.length(),
            durationMs = durationMs,
            language = language,
            state = TranscribeJob.STATE_QUEUED,
            completedChunks = 0,
            totalChunks = 0
        )

        saveJob(job)
        reloadJobs()
        enqueueWork(id)
        return@withContext job
    }

    fun enqueueWork(jobId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag("job_$jobId")
            .setInputData(workDataOf("job_id" to jobId))
            .build()

        workManager.enqueueUniqueWork("transcribe_$jobId", policy, request)
    }

    fun pauseJob(jobId: String) {
        updateJob(jobId) { it.copy(state = TranscribeJob.STATE_PAUSED) }
        workManager.cancelUniqueWork("transcribe_$jobId")
    }

    fun resumeJob(jobId: String) {
        updateJob(jobId) { it.copy(state = TranscribeJob.STATE_QUEUED, error = "", consecutiveFailures = 0, statusMessage = "ပြန်လည်ဆက်လုပ်ရန် စောင့်နေသည်...") }
        enqueueWork(jobId, ExistingWorkPolicy.REPLACE)
    }

    fun deleteJob(jobId: String) {
        updateJob(jobId) { it.copy(state = TranscribeJob.STATE_PAUSED) }
        workManager.cancelUniqueWork("transcribe_$jobId")
        val dir = getJobDir(jobId)
        dir.deleteRecursively()
        reloadJobs()
    }

    private fun generateJobId(file: File, language: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                digest.update(buf, 0, n)
            }
        }
        digest.update(language.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
