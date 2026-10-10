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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
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
        val checkpoints = ChunkCheckpoints(chunksDir)
        val sourceFile = File(jobDir, job.sourceName)

        if (!sourceFile.exists()) {
            repository.updateJob(jobId) {
                it.copy(state = TranscribeJob.STATE_ERROR, error = "Source file missing")
            }
            return Result.failure()
        }

        try {
            val sliceStarted = System.nanoTime()
            val geminiKeys = settings.geminiKeysFlow.first()
            if (geminiKeys.isEmpty()) throw GeminiApiException("Gemini API Key မရှိပါ။ Settings တွင် API Key ထည့်ပြီး ပြန်စမ်းပါ။", false)
            // Update notification
            setForeground(createNotification(job, "စတင်ပြင်ဆင်နေပါသည်..."))

            // Phase 1: Decoding audio to 16kHz WAV chunks
            val countManifest = File(chunksDir, "count.txt")
            val storedCount = runCatching {
                android.util.AtomicFile(countManifest).openRead().bufferedReader().use { it.readText().trim().toInt() }
            }.getOrDefault(0).takeIf { it in 1..10_000 } ?: 0
            val needsDecoding = storedCount <= 0 || (0 until storedCount).any {
                checkpoints.read(it).isBlank() && !isValidWavChunk(File(chunksDir, "$it.wav"))
            }
            if (needsDecoding) {
                repository.updateJob(jobId) { if (!it.isActive) it else it.copy(state = TranscribeJob.STATE_PREPARING, error = "") }
                setForeground(createNotification(job, "အသံဖိုင် ခွဲခြမ်းနေပါသည်..."))

                // Preserve completed text even if preparation was interrupted or a WAV went missing.
                chunksDir.listFiles()?.filter { it.extension == "wav" }?.forEach { it.delete() }
                val spans = AudioDecoder().decodeToWavChunks(sourceFile, chunksDir) { position, duration ->
                    currentCoroutineContext().ensureActive()
                    val percent = if (duration > 0) (position * 100 / duration).coerceIn(0, 100) else 0
                    val status = "အသံဖိုင် ခွဲခြမ်းနေသည် ($percent%)..."
                    repository.updateJob(jobId) { if (!it.isActive) it else it.copy(statusMessage = status) }
                    setForeground(createNotification(job, status))
                }
                check(spans.isNotEmpty()) { "No valid audio frames decoded" }

                writeAtomicText(File(chunksDir, "spans.json"), Gson().toJson(spans))
                writeAtomicText(countManifest, spans.size.toString())
            }

            val totalChunks = android.util.AtomicFile(countManifest).openRead().bufferedReader().use { it.readText().trim().toInt() }
            check(totalChunks in 1..10_000) { "Invalid chunk count" }

            repository.updateJob(jobId) {
                it.copy(
                    state = if (it.isActive) TranscribeJob.STATE_PROCESSING else it.state,
                    totalChunks = totalChunks,
                    completedChunks = checkpoints.completed(totalChunks),
                    transcript = checkpoints.transcript(totalChunks)
                )
            }

            // Phase 2: Transcribe each chunk with Gemini
            val transcriber = GeminiTranscriber(geminiKeys, initialModel = job.geminiModel)

            for (index in 0 until totalChunks) {
                currentCoroutineContext().ensureActive()
                val currentJob = repository.getJob(jobId)
                if (currentJob == null || !currentJob.isActive) {
                    return Result.success() // Stopped gracefully
                }

                if (checkpoints.read(index).isNotBlank()) {
                    File(chunksDir, "$index.wav").delete()
                    continue
                }
                // Release the foreground worker regularly; WorkManager resumes from durable checkpoints.
                if (System.nanoTime() - sliceStarted >= 300_000_000_000L) {
                    repository.updateJob(jobId) { if (!it.isActive) it else it.copy(
                        state = TranscribeJob.STATE_QUEUED,
                        statusMessage = "ပြီးထားသောအပိုင်းများကို သိမ်းထားသည်။ ဆက်လုပ်ရန် ခဏစောင့်နေသည်..."
                    ) }
                    currentCoroutineContext().ensureActive()
                    repository.enqueueWork(jobId, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE)
                    return Result.success()
                }

                val wavFile = File(chunksDir, "$index.wav")
                check(isValidWavChunk(wavFile)) {
                    "Missing audio chunk $index"
                }

                val progressText = "အပိုင်း ${index + 1}/$totalChunks ကို စာသားပြောင်းနေသည်..."
                repository.updateJob(jobId) { if (!it.isActive) it else it.copy(statusMessage = progressText, error = "") }
                setForeground(createNotification(job.copy(completedChunks = index, totalChunks = totalChunks), progressText))

                val chunkTranscript = transcriber.transcribeChunk(wavFile, job.language).trim()
                currentCoroutineContext().ensureActive()
                checkpoints.save(index, chunkTranscript)
                wavFile.delete() // Clean up disk immediately

                val completed = checkpoints.completed(totalChunks)
                repository.updateJob(jobId) {
                    if (!it.isActive) it else it.copy(
                        state = TranscribeJob.STATE_PROCESSING,
                        completedChunks = completed,
                        totalChunks = totalChunks,
                        transcript = checkpoints.transcript(totalChunks),
                        consecutiveFailures = 0,
                        geminiModel = transcriber.lastSuccessfulModel
                    )
                }
                setProgress(workDataOf("completed" to completed, "total" to totalChunks))

                // Avoid bursting consecutive uploads; quotas depend on the account and model.
                if (index < totalChunks - 1) {
                    delay(1500L)
                }
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
            currentCoroutineContext().ensureActive()
            writeAtomicText(File(jobDir, "transcript.txt"), finalTranscript)

            repository.updateJob(jobId) {
                if (!it.isActive) it else it.copy(
                    state = TranscribeJob.STATE_COMPLETED,
                    completedChunks = totalChunks,
                    totalChunks = totalChunks,
                    transcript = finalTranscript,
                    error = "",
                    statusMessage = "စာသားပြောင်းပြီးပါပြီ",
                    consecutiveFailures = 0
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
                    if (ne is CancellationException) throw ne
                    repository.updateJob(jobId) {
                        it.copy(notionSyncState = TranscribeJob.NOTION_FAILED, notionError = ne.message.orEmpty())
                    }
                }
            }

            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val updated = repository.updateJob(jobId) {
                if (!it.isActive) it else {
                    val failures = it.consecutiveFailures + 1
                    val retry = e is GeminiApiException && e.retryable && failures < 5
                    it.copy(
                        state = if (retry) TranscribeJob.STATE_QUEUED else TranscribeJob.STATE_ERROR,
                        error = e.message.orEmpty().take(300),
                        statusMessage = if (retry) "ကွန်ရက် / quota ပြဿနာ။ ခဏစောင့်ပြီး အလိုအလျောက် ဆက်လုပ်မည် ($failures/5)။" else "မပြီးသေးသောအပိုင်းမှ ပြန်ဆက်နိုင်ပါသည်။",
                        consecutiveFailures = failures
                    )
                }
            }
            return if (updated?.state == TranscribeJob.STATE_QUEUED) Result.retry() else Result.failure()
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
