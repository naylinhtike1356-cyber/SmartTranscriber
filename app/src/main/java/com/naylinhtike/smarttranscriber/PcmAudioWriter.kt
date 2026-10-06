package com.naylinhtike.smarttranscriber

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioChunkSpan(val index: Int, val startSample: Long, val endSample: Long)

/**
 * Continuous 16 kHz mono PCM writer that chunks audio into ~3-minute WAV segments
 * without dropping or duplicating boundary samples.
 */
class PcmAudioWriter(
    private val directory: File,
    private val checkStorage: () -> Unit = {}
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MAX_SECONDS = 180 // 3 minutes per chunk
        const val CHUNK_MIN_SECONDS = 150 // Split on silence after 2.5 minutes
    }

    val spans = mutableListOf<AudioChunkSpan>()
    var totalSamples = 0L
        private set

    private var chunkStart = 0L
    private var silenceSamples = 0
    private var output: RandomAccessFile? = null
    private val scratch = ByteArray(64 * 1024)
    private var buffered = 0

    fun write(input: ByteBuffer) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() % 2 == 0) { "Input buffer must be 16-bit aligned" }

        while (input.hasRemaining()) {
            if (output == null) {
                checkStorage()
                output = RandomAccessFile(File(directory, "${spans.size}.wav"), "rw").apply {
                    setLength(0)
                    seek(44) // Leave space for 44-byte WAV header
                }
                chunkStart = totalSamples
                silenceSamples = 0
            }

            val sample = input.short.toInt()
            scratch[buffered++] = sample.toByte()
            scratch[buffered++] = (sample shr 8).toByte()

            if (buffered == scratch.size) {
                flush()
            }

            totalSamples++
            silenceSamples = if (kotlin.math.abs(sample) < 200) silenceSamples + 1 else 0

            val currentChunkSamples = totalSamples - chunkStart
            if (currentChunkSamples >= SAMPLE_RATE.toLong() * CHUNK_MAX_SECONDS ||
                (currentChunkSamples >= SAMPLE_RATE.toLong() * CHUNK_MIN_SECONDS && silenceSamples >= SAMPLE_RATE / 2)
            ) {
                finishChunk()
            }
        }
    }

    private fun flush() {
        if (buffered > 0) {
            output?.write(scratch, 0, buffered)
            buffered = 0
        }
    }

    private fun finishChunk() {
        val file = output ?: return
        flush()

        val sampleBytes = ((totalSamples - chunkStart) * 2).toInt()
        file.seek(0)

        fun writeLe(value: Int, length: Int) {
            repeat(length) { file.write(value ushr (it * 8) and 255) }
        }

        // Write standard 44-byte WAV header
        file.writeBytes("RIFF")
        writeLe(sampleBytes + 36, 4)
        file.writeBytes("WAVEfmt ")
        writeLe(16, 4) // subchunk1size (16 for PCM)
        writeLe(1, 2)  // audioFormat (1 for PCM)
        writeLe(1, 2)  // numChannels (1 for mono)
        writeLe(SAMPLE_RATE, 4) // sampleRate
        writeLe(SAMPLE_RATE * 2, 4) // byteRate
        writeLe(2, 2)  // blockAlign
        writeLe(16, 2) // bitsPerSample
        file.writeBytes("data")
        writeLe(sampleBytes, 4)

        file.fd.sync()
        file.close()

        spans += AudioChunkSpan(spans.size, chunkStart, totalSamples)
        output = null
    }

    fun complete(): List<AudioChunkSpan> {
        finishChunk()
        return spans.toList()
    }

    override fun close() {
        output?.close()
        output = null
    }
}
