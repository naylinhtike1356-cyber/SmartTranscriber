package com.naylinhtike.smarttranscriber

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reject an interrupted WAV write even when it already contains PCM bytes. */
internal fun isValidWavChunk(file: File): Boolean = runCatching {
    val size = file.length()
    if (size !in 46L..8_000_000L || (size - 44) % 2 != 0L) return@runCatching false
    val header = ByteArray(44)
    DataInputStream(file.inputStream()).use { it.readFully(header) }
    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(offset: Int, value: String) = String(header, offset, 4, Charsets.US_ASCII) == value
    tag(0, "RIFF") && tag(8, "WAVE") && tag(12, "fmt ") && tag(36, "data") &&
        buffer.getInt(4).toLong() + 8 == size && buffer.getInt(40).toLong() + 44 == size &&
        buffer.getInt(16) == 16 && buffer.getShort(20).toInt() == 1 && buffer.getShort(22).toInt() == 1 &&
        buffer.getInt(24) == PcmAudioWriter.SAMPLE_RATE && buffer.getInt(28) == PcmAudioWriter.SAMPLE_RATE * 2 &&
        buffer.getShort(32).toInt() == 2 && buffer.getShort(34).toInt() == 16
}.getOrDefault(false)
