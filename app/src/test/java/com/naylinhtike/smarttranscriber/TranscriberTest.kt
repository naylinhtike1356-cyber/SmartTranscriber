package com.naylinhtike.smarttranscriber

import org.junit.Assert.*
import org.junit.Test

class TranscriberTest {

    @Test
    fun testJobProgressCalculation() {
        val job1 = TranscribeJob(state = TranscribeJob.STATE_PREPARING, totalChunks = 0, completedChunks = 0)
        assertEquals(0.05f, job1.progressPercent, 0.01f)

        val job2 = TranscribeJob(state = TranscribeJob.STATE_PROCESSING, totalChunks = 10, completedChunks = 3)
        assertEquals(0.3f, job2.progressPercent, 0.01f)

        val job3 = TranscribeJob(state = TranscribeJob.STATE_COMPLETED, totalChunks = 10, completedChunks = 10)
        assertEquals(1.0f, job3.progressPercent, 0.01f)
    }

    @Test
    fun testJobFormatters() {
        val job = TranscribeJob(
            fileSizeBytes = 15 * 1024 * 1024L,
            durationMs = (12 * 60 + 34) * 1000L
        )
        assertEquals("15.0 MB", job.formattedSize())
        assertEquals("12:34", job.formattedDuration())
    }

    @Test
    fun testLongTextChunkingForNotion() {
        // Notion paragraph blocks reject any block over 2000 chars.
        // We chunk at 1900 chars.
        val longText = "က".repeat(5000)
        val chunks = longText.chunked(1900)
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].length <= 1900)
        assertTrue(chunks[1].length <= 1900)
        assertTrue(chunks[2].length <= 1900)
        assertEquals(longText, chunks.joinToString(""))
    }
}
