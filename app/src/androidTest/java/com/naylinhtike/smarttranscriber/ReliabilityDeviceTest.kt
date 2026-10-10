package com.naylinhtike.smarttranscriber

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class ReliabilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun decodeSixtyFiveMinuteStereoMp3WithoutMissingBoundaries() = runBlocking {
        val source = File(context.getExternalFilesDir(null), "transcriber-65min.mp3")
        assertTrue("Push the generated 65-minute MP3 fixture first", source.exists())
        val directory = File(context.cacheDir, "device_decode_test").apply { mkdirs() }
        try {
            var reportedPosition = 0L
            val spans = AudioDecoder().decodeToWavChunks(source, directory) { position, _ ->
                reportedPosition = maxOf(reportedPosition, position)
            }
            assertTrue(spans.size in 32..44) // Supports both hard and silence boundaries.
            assertTrue(kotlin.math.abs(spans.last().endSample - 65L * 60 * 16000) < 16000)
            assertTrue(reportedPosition > 64L * 60 * 1000)
            var end = 0L
            for (span in spans) {
                assertEquals(end, span.startSample)
                end = span.endSample
                assertTrue(span.endSample > span.startSample)
                val wav = File(directory, "${span.index}.wav")
                assertEquals(44 + (span.endSample - span.startSample) * 2, wav.length())
                val header = ByteArray(44)
                wav.inputStream().use { assertEquals(44, it.read(header)) }
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(16000, buffer.getInt(24))
                assertEquals(1, buffer.getShort(22).toInt())
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun interruptedCheckpointWriteKeepsPreviousTranscript() {
        val directory = File(context.cacheDir, "device_checkpoint_test").apply { mkdirs() }
        try {
            val store = ChunkCheckpoints(directory)
            store.save(0, "ပြီးထားသောစာသား")
            store.save(2, "နောက်ပိုင်းစာသား")
            val atomic = AtomicFile(File(directory, "0.txt"))
            atomic.startWrite().use { it.write("unfinished".toByteArray()) } // Simulate process death before commit.
            assertEquals("ပြီးထားသောစာသား", ChunkCheckpoints(directory).read(0))
            assertEquals(2, store.completed(3))
            store.save(1, "[no speech]")
            assertEquals(3, store.completed(3))
            assertEquals("ပြီးထားသောစာသား\n\nနောက်ပိုင်းစာသား", store.transcript(3))
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun upgradeRecoveryReplacesOldDelayOnceAndPreservesProgress() = runBlocking {
        val network = context.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Disable Wi-Fi and mobile data in the test emulator first", network.activeNetwork)
        val preferences = context.getSharedPreferences("transcription_runtime", android.content.Context.MODE_PRIVATE)
        val previousVersion = preferences.getLong("scheduled_version", -1L)
        val source = File(context.cacheDir, "recovery_fixture.audio").apply { writeText(java.util.UUID.randomUUID().toString()) }
        val repository = TranscribeRepository(context)
        val workManager = androidx.work.WorkManager.getInstance(context)
        val job = repository.createJob(source, "Worker upgrade recovery test", 1000L, "my-MM")
        try {
            repository.enqueueWork(job.id).result.get(30, java.util.concurrent.TimeUnit.SECONDS)
            fun activeWork() = workManager.getWorkInfosForUniqueWork("transcribe_${job.id}")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).single { !it.state.isFinished }
            val oldId = activeWork().id
            repository.updateJob(job.id) { it.copy(state = TranscribeJob.STATE_PROCESSING, completedChunks = 1, totalChunks = 3, transcript = "saved partial transcript") }
            val checkpoints = ChunkCheckpoints(File(repository.getJobDir(job.id), "chunks").apply { mkdirs() })
            checkpoints.save(0, "saved partial transcript")
            preferences.edit().putLong("scheduled_version", -1L).commit()
            repository.recoverActiveJobs()
            val newWork = activeWork()
            assertNotEquals(oldId, newWork.id)
            assertEquals(0, newWork.runAttemptCount)
            assertEquals("saved partial transcript", repository.getJob(job.id)!!.transcript)
            assertEquals("saved partial transcript", checkpoints.read(0))
            repository.recoverActiveJobs()
            assertEquals(newWork.id, activeWork().id) // Ordinary startup does not restart a live request.
        } finally {
            repository.deleteJob(job.id)
            source.delete()
            preferences.edit().putLong("scheduled_version", previousVersion).commit()
        }
    }

    @Test fun legacyJobRemainsReadableAfterInPlaceUpgrade() {
        val job = TranscribeRepository(context).listJobs().firstOrNull { it.fileName == "Upgrade persistence test" }
        assertNotNull("Install v1.1, seed the legacy job, then install v1.2 over it", job)
        assertEquals("saved transcript before update", job!!.transcript)
        assertEquals(TranscribeJob.STATE_COMPLETED, job.state)
        assertEquals("", job.statusMessage)
    }
}
