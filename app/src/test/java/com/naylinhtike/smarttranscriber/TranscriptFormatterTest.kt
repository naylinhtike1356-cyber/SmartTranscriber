package com.naylinhtike.smarttranscriber

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class TranscriptFormatterTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun span(index: Int, startSeconds: Long, endSeconds: Long) = AudioChunkSpan(
        index, startSeconds * PcmAudioWriter.SAMPLE_RATE, endSeconds * PcmAudioWriter.SAMPLE_RATE
    )

    @Test fun tenMinuteMarkersPreserveParagraphsVersesAndSilentIntervals() {
        val spans = listOf(span(0, 0, 120), span(1, 120, 240), span(2, 600, 720), span(3, 1200, 1320))
        val text = listOf("တရားတော်။\n\nဂါထာ၁\nဂါထာ၂", "ဆက်လက်။", "[no speech]", "နောက်ပိုဒ်။")
        val output = TranscriptFormatter.assemble(spans) { text[it] }
        assertEquals("[00:00:00]\n\nတရားတော်။\n\nဂါထာ၁\nဂါထာ၂\n\nဆက်လက်။\n\n[00:10:00]\n\n[စကားသံ မရှိပါ / No speech]\n\n[00:20:00]\n\nနောက်ပိုဒ်။", output)
    }

    @Test fun resumeIsDeterministicAndMissingCheckpointsDoNotInventTiming() {
        val spans = listOf(span(0, 0, 120), span(1, 120, 240), span(2, 600, 720))
        val saved = mutableMapOf(0 to "အစ။", 2 to "နောက်။")
        val partial = TranscriptFormatter.assemble(spans) { saved[it].orEmpty() }
        assertEquals(1, Regex("\\[00:10:00]").findAll(partial).count())
        assertEquals(partial, TranscriptFormatter.assemble(spans) { saved[it].orEmpty() })
        saved[1] = "အလယ်။"
        assertEquals("[00:00:00]\n\nအစ။\n\nအလယ်။\n\n[00:10:00]\n\nနောက်။",
            TranscriptFormatter.assemble(spans) { saved[it].orEmpty() })
        assertEquals("", TranscriptFormatter.assemble(spans) { "" })
    }

    @Test fun legacySpansUseActualTimeAndLongRecordingsIncludeHours() {
        val output = TranscriptFormatter.assemble(listOf(span(0, 0, 95), span(1, 665, 760))) { "စာ။" }
        assertTrue(output.contains("[00:11:05]"))
        assertFalse(output.contains("[00:10:00]"))
        assertEquals("[01:10:00]", TranscriptFormatter.timestamp(4200))
    }

    @Test fun silenceSplitsStillHaveExactTenMinuteBoundaryAndRetainSamples() {
        val samples = 601L * PcmAudioWriter.SAMPLE_RATE
        val directory = temporary.newFolder()
        val buffer = ByteArray(64 * 1024)
        val spans = PcmAudioWriter(directory).use { writer ->
            var remaining = samples * 2
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                writer.write(ByteBuffer.wrap(buffer, 0, count))
                remaining -= count
            }
            writer.complete()
        }
        val boundary = 600L * PcmAudioWriter.SAMPLE_RATE
        assertTrue(spans.any { it.endSample == boundary })
        assertTrue(spans.any { it.startSample == boundary })
        assertEquals(samples, spans.sumOf { it.endSample - it.startSample })
        spans.zipWithNext().forEach { (a, b) -> assertEquals(a.endSample, b.startSample) }
        // Re-decoding a pre-upgrade job must keep its old checkpoint indices.
        val legacy = PcmAudioWriter(temporary.newFolder(), exactTenMinuteBoundaries = false).use { writer ->
            var remaining = samples * 2
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                writer.write(ByteBuffer.wrap(buffer, 0, count))
                remaining -= count
            }
            writer.complete()
        }
        assertFalse(legacy.any { it.startSample == boundary || it.endSample == boundary })
        assertEquals(samples, legacy.sumOf { it.endSample - it.startSample })
    }

    @Test fun exportedDocumentIncludesTitleDurationAndDraftStatus() {
        val job = TranscribeJob(fileName = "တရားတော်.mp3", durationMs = 600_000, transcript = "[00:00:00]\n\nစာ။")
        val document = TranscriptFormatter.document(job)
        assertTrue(document.startsWith("တရားတော်\nအသံကြာချိန်: 10:00\n"))
        assertTrue(document.contains("[မပြီးသေးသော စာမူ]"))
        assertTrue(document.endsWith(job.transcript))
        assertFalse(TranscriptFormatter.document(job.copy(state = TranscribeJob.STATE_COMPLETED)).contains("[မပြီးသေးသော စာမူ]"))
    }
}
