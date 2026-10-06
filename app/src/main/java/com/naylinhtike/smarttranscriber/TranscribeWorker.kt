package com.naylinhtike.smarttranscriber

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.sync.Mutex

internal object TranscribeWorkerLock {
    val mutex = Mutex()
}

class TranscribeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!TranscribeWorkerLock.mutex.tryLock()) {
            return@withContext Result.retry()
        }
        try {
            processTranscription()
        } finally {
            TranscribeWorkerLock.mutex.unlock()
        }
    }

    private suspend fun processTranscription(): Result {
        val jobId = inputData.getString("job_id") ?: return Result.failure()
        val repository = TranscribeRepository(applicationContext)
        val settings = SettingsRepository(applicationContext)

        val job = repository.getJob(jobId) ?: return Result.failure()
        if (!job.isActive) return Result.success()

        val jobDir = repository.getJobDir(jobId)
        val chunksDir = File(jobDir, "chunks").apply { mkdirs() }
        val sourceFile = File(jobDir, job.sourceName)

        if (!sourceFile.exists()) {
            repository.updateJob(jobId) {
                it.copy(state = TranscribeJob.STATE_ERROR, error = "Source file missing")
            }
            return Result.failure()
        }

        try {
            // Update notification
            setForeground(createNotification(job, "စတင်ပြင်ဆင်နေပါသည်..."))

            // Phase 1: Decoding audio to 16kHz WAV chunks
            val countManifest = File(chunksDir, "count.txt")
            if (!countManifest.exists()) {
                repository.updateJob(jobId) { it.copy(state = TranscribeJob.STATE_PREPARING, error = "") }
                setForeground(createNotification(job, "အသံဖိုင် ခွဲခြမ်းနေပါသည်..."))

                chunksDir.listFiles()?.forEach { it.delete() }
                val spans = AudioDecoder().decodeToWavChunks(sourceFile, chunksDir)
                check(spans.isNotEmpty()) { "No valid audio frames decoded" }

                File(chunksDir, "spans.json").writeText(Gson().toJson(spans))
                File(chunksDir, "count.txt").writeText(spans.size.toString())
            }

            val totalChunks = File(chunksDir, "count.txt").readText().trim().toInt()
            check(totalChunks > 0) { "Invalid chunk count" }

            repository.updateJob(jobId) {
                it.copy(
                    state = TranscribeJob.STATE_PROCESSING,
                    totalChunks = totalChunks,
                    completedChunks = countCompletedChunks(chunksDir, totalChunks)
                )
            }

            // Phase 2: Transcribe each chunk with Gemini
            val geminiKeys = settings.geminiKeysFlow.first()
            if (geminiKeys.isEmpty()) {
                repository.updateJob(jobId) {
                    it.copy(
                        state = TranscribeJob.STATE_ERROR,
                        error = "Gemini API Key မရှိပါ။ Settings တွင် API Key ထည့်ပြီး ပြန်စမ်းပါ။"
                    )
                }
                return Result.failure()
            }

            val transcriber = GeminiTranscriber(geminiKeys)

            for (index in 0 until totalChunks) {
                val currentJob = repository.getJob(jobId)
                if (currentJob?.state == TranscribeJob.STATE_PAUSED) {
                    return Result.success() // Stopped gracefully
                }

                val checkpoint = File(chunksDir, "$index.txt")
                if (checkpoint.exists() && checkpoint.readText().isNotBlank()) {
                    File(chunksDir, "$index.wav").delete()
                    continue
                }

                val wavFile = File(chunksDir, "$index.wav")
                check(wavFile.exists() && wavFile.length() > 44) {
                    "Missing audio chunk $index"
                }

                val progressText = "အပိုင်း ${index + 1}/$totalChunks ကို စာသားပြောင်းနေသည်..."
                setForeground(createNotification(job.copy(completedChunks = index, totalChunks = totalChunks), progressText))

                val chunkTranscript = transcriber.transcribeChunk(wavFile, job.language).trim()
                checkpoint.writeText(chunkTranscript)
                wavFile.delete() // Clean up disk immediately

                val completed = countCompletedChunks(chunksDir, totalChunks)
                repository.updateJob(jobId) {
                    it.copy(
                        state = TranscribeJob.STATE_PROCESSING,
                        completedChunks = completed,
                        totalChunks = totalChunks
                    )
                }
                setProgress(workDataOf("completed" to completed, "total" to totalChunks))
            }

            // Phase 3: Combine all chunks
            val fullTranscriptBuilder = StringBuilder()
            for (index in 0 until totalChunks) {
                val chunkText = File(chunksDir, "$index.txt").readText().trim()
                if (chunkText != "[no speech]" && chunkText.isNotBlank()) {
                    if (fullTranscriptBuilder.isNotEmpty()) {
                        fullTranscriptBuilder.append("\n\n")
                    }
                    fullTranscriptBuilder.append(chunkText)
                }
            }

            val finalTranscript = fullTranscriptBuilder.toString().ifBlank { "[အသံ မကြားရပါ / No speech detected]" }
            File(jobDir, "transcript.txt").writeText(finalTranscript)

            repository.updateJob(jobId) {
                it.copy(
                    state = TranscribeJob.STATE_COMPLETED,
                    completedChunks = totalChunks,
                    totalChunks = totalChunks,
                    transcript = finalTranscript,
                    error = ""
                )
            }

            // Optional auto-export to Notion if enabled in settings
            val notionEnabled = settings.notionEnabledFlow.first()
            val notionAutoSync = settings.notionAutoSyncFlow.first()
            val notionKey = settings.notionApiKeyFlow.first()
            val notionDbId = settings.notionDatabaseIdFlow.first()

            if (notionEnabled && notionAutoSync && notionKey.isNotBlank() && notionDbId.isNotBlank() && finalTranscript.isNotBlank()) {
                try {
                    repository.updateJob(jobId) { it.copy(notionSyncState = TranscribeJob.NOTION_SYNCING) }
                    val exporter = NotionExporter(notionKey, notionDbId)
                    val metadata = mapOf(
                        "Source" to job.fileName,
                        "Duration" to job.formattedDuration(),
                        "Language" to job.language,
                        "Chunks" to "$totalChunks parts"
                    )
                    val result = exporter.exportTranscript(
                        title = job.fileName.substringBeforeLast('.').take(100),
                        fullTranscript = finalTranscript,
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
                    } else {
                        repository.updateJob(jobId) {
                            it.copy(
                                notionSyncState = TranscribeJob.NOTION_FAILED,
                                notionError = result.errorMessage
                            )
                        }
                    }
                } catch (ne: Exception) {
                    repository.updateJob(jobId) {
                        it.copy(notionSyncState = TranscribeJob.NOTION_FAILED, notionError = ne.message.orEmpty())
                    }
                }
            }

            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.updateJob(jobId) {
                if (it.state == TranscribeJob.STATE_PAUSED) it
                else it.copy(state = TranscribeJob.STATE_ERROR, error = e.message.orEmpty().take(300))
            }
            return Result.failure()
        }
    }

    private fun countCompletedChunks(chunksDir: File, total: Int): Int {
        return (0 until total).count {
            val f = File(chunksDir, "$it.txt")
            f.exists() && f.readText().isNotBlank()
        }
    }

    private fun createNotification(job: TranscribeJob, statusMessage: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, SmartTranscriberApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(job.fileName)
            .setContentText(statusMessage)
            .setProgress(
                if (job.totalChunks > 0) job.totalChunks else 100,
                if (job.totalChunks > 0) job.completedChunks else 0,
                job.totalChunks == 0
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val notificationId = 20_000 + (job.id.hashCode() and 0x7fff)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
