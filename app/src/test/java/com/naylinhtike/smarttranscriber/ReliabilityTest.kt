package com.naylinhtike.smarttranscriber

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class ReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectsMissingBlockedTruncatedAndMalformedResponses() {
        val invalid = listOf(
            "{}",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"partial"}]}}]}""",
            """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[{"text":"partial"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":""}]}}]}""",
            "invalid-json"
        )
        for (response in invalid) {
            try {
                GeminiResponseParser.extract(response)
                fail("Incomplete response was accepted: $response")
            } catch (_: IOException) { }
        }
    }

    @Test fun acceptsOnlyFinishedTranscriptAndExcludesThoughts() {
        assertEquals("မြန်မာတရားစာသား", GeminiResponseParser.extract(
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"internal","thought":true},{"text":"မြန်မာ"},{"text":"တရားစာသား"}]}}]}"""
        ))
        assertEquals("[no speech]", GeminiResponseParser.extract(
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"[no speech]"}]}}]}"""
        ))
    }

    @Test fun resumesSparseCheckpointsInAudioOrderAndKeepsSilenceCompletion() {
        val dir = temporary.newFolder()
        val store = ChunkCheckpoints(dir, { it.readText() }, { file, text -> file.writeText(text) })
        store.save(2, "အပိုင်း၃")
        store.save(0, "အပိုင်း၁")
        store.save(1, "[no speech]")
        File(dir, "3.txt").writeText("")
        assertEquals(3, store.completed(5))
        assertEquals("အပိုင်း၁\n\nအပိုင်း၃", store.transcript(5))
        assertEquals("", store.read(4))
        // Re-opening does not discard completed text.
        val reopened = ChunkCheckpoints(dir, { it.readText() }, { file, text -> file.writeText(text) })
        assertEquals(3, reopened.completed(5))
    }

    @Test fun sixtyFiveMinutesOfPcmRetainsEverySampleAcrossChunks() {
        val dir = temporary.newFolder()
        val sampleCount = 65L * 60 * PcmAudioWriter.SAMPLE_RATE
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        val pattern = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Above the silence threshold, and distinctive enough to detect dropped boundaries.
        while (pattern.hasRemaining()) pattern.putShort((1000 + pattern.position() % 30000).toShort())
        var remaining = sampleCount * 2
        val spans = PcmAudioWriter(dir).use { writer ->
            while (remaining > 0) {
                val count = minOf(bytes.size.toLong(), remaining).toInt()
                expectedDigest.update(bytes, 0, count)
                writer.write(ByteBuffer.wrap(bytes, 0, count))
                remaining -= count
            }
            assertEquals(sampleCount, writer.totalSamples)
            writer.complete()
        }
        assertEquals(33, spans.size)
        assertEquals(0L, spans.first().startSample)
        assertEquals(sampleCount, spans.last().endSample)
        val actualDigest = MessageDigest.getInstance("SHA-256")
        var previousEnd = 0L
        for (span in spans) {
            assertEquals(previousEnd, span.startSample)
            previousEnd = span.endSample
            val file = File(dir, "${span.index}.wav")
            assertEquals(44 + (span.endSample - span.startSample) * 2, file.length())
            assertTrue(isValidWavChunk(file))
            file.inputStream().use { input ->
                val header = ByteArray(44)
                assertEquals(44, input.read(header))
                assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
                assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
                assertEquals(16000, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
                var count: Int
                while (input.read(bytes).also { count = it } != -1) actualDigest.update(bytes, 0, count)
            }
        }
        assertArrayEquals(expectedDigest.digest(), actualDigest.digest())
    }

    @Test fun rejectsInterruptedOrTruncatedWavBeforeResumeUpload() {
        val directory = temporary.newFolder()
        val unfinished = File(directory, "unfinished.wav").apply { writeBytes(ByteArray(32_044)) }
        assertFalse(isValidWavChunk(unfinished)) // PCM exists but the header was never committed.
        PcmAudioWriter(directory).use { writer ->
            writer.write(ByteBuffer.wrap(ByteArray(32_000)))
            writer.complete()
        }
        val valid = File(directory, "0.wav")
        assertTrue(isValidWavChunk(valid))
        valid.appendBytes(byteArrayOf(0, 0))
        assertFalse(isValidWavChunk(valid))
        assertFalse(isValidWavChunk(File(directory, "missing.wav")))
    }

    @Test fun versionAndChecksumRejectInvalidUpdates() {
        assertTrue(UpdateValidation.isNewerVersion("1.1.0", "v1.2.0"))
        assertTrue(UpdateValidation.isNewerVersion("1.9", "1.10"))
        assertFalse(UpdateValidation.isNewerVersion("1.2.0", "1.2"))
        assertFalse(UpdateValidation.isNewerVersion("1.2.0", "1.1.99"))
        assertFalse(UpdateValidation.isNewerVersion("1.2.0", "invalid.99"))
        val file = temporary.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateValidation.sha256(file))
    }

    @Test fun cancellationStopsAnInFlightNetworkCall() = runBlocking {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val responder = Thread {
            runCatching { server.accept().use { socket ->
                accepted.countDown()
                // Keep the response pending until the test cancels the call.
                socket.getInputStream().readBytes()
            } }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient.Builder().build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
        try {
            val request = launch { call.awaitResponse().close() }
            withTimeout(5000) {
                while (!accepted.await(10, TimeUnit.MILLISECONDS)) kotlinx.coroutines.delay(10)
            }
            request.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            responder.join(1000)
        }
    }

    @Test(timeout = 20000) fun providerServerFallbackAndQuotaAreBounded() = runBlocking {
        val server = MockWebServer()
        val complete = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"complete transcript\"}]}}]}"
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(MockResponse().setBody(complete))
        server.enqueue(MockResponse().setBody(complete))
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        server.start()
        try {
            val wav = temporary.newFile().apply { writeBytes(ByteArray(128)) }
            val transcriber = GeminiTranscriber(listOf("test-key"), server.url("/v1beta").toString().trimEnd('/'))
            assertEquals("complete transcript", transcriber.transcribeChunk(wav, "en-US"))
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            assertNull(first.requestUrl?.query)
            assertEquals("test-key", first.getHeader("x-goog-api-key"))
            val fallback = server.takeRequest().path
            assertEquals("gemini-2.5-flash", transcriber.lastSuccessfulModel)
            assertEquals("complete transcript", transcriber.transcribeChunk(wav, "en-US"))
            assertEquals(fallback, server.takeRequest().path)
            try {
                transcriber.transcribeChunk(wav, "en-US")
                fail("Quota error was accepted")
            } catch (e: GeminiApiException) { assertTrue(e.retryable) }
            assertEquals(4, server.requestCount)
            server.enqueue(MockResponse().setBody(complete))
            val resumed = GeminiTranscriber(listOf("test-key"), server.url("/v1beta").toString().trimEnd('/'), transcriber.lastSuccessfulModel)
            assertEquals("complete transcript", resumed.transcribeChunk(wav, "en-US"))
            server.takeRequest() // quota response request
            assertEquals(fallback, server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
