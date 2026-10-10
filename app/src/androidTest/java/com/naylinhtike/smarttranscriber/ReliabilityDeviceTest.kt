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

    @Test fun legacyJobRemainsReadableAfterInPlaceUpgrade() {
        val job = TranscribeRepository(context).listJobs().firstOrNull { it.fileName == "Upgrade persistence test" }
        assertNotNull("Install v1.1, seed the legacy job, then install v1.2 over it", job)
        assertEquals("saved transcript before update", job!!.transcript)
        assertEquals(TranscribeJob.STATE_COMPLETED, job.state)
        assertEquals("", job.statusMessage)
    }
}
