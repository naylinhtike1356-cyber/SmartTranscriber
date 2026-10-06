package com.naylinhtike.smarttranscriber

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecoder {

    @OptIn(UnstableApi::class)
    suspend fun decodeToWavChunks(source: File, targetDirectory: File): List<AudioChunkSpan> {
        check(targetDirectory.exists() || targetDirectory.mkdirs()) { "Cannot create output directory" }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var channels = 0
        var sampleRate = 0
        var monoBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        val sonic = SonicAudioProcessor()
        var resampling = false

        val writer = PcmAudioWriter(targetDirectory) {
            val available = android.os.StatFs(targetDirectory.absolutePath).availableBytes
            check(available > 10L * 1024 * 1024) { "Not enough storage to decode audio. Please free storage space." }
        }

        fun drainSonic() {
            writer.write(sonic.output)
        }

        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No valid audio track found in file.")

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mimeType = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio mime type")
            val decoder = MediaCodec.createDecoderByType(mimeType)
            codec = decoder

            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastProgressTime = System.nanoTime()

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() - lastProgressTime < 90_000_000_000L) { "Audio decoding timed out." }

                var advanced = false
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(0)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgressTime = System.nanoTime()
                        advanced = true
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decodedFormat = decoder.outputFormat
                        val newRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = newRate
                        channels = newChannels
                        check(sampleRate > 0 && channels > 0)

                        sonic.setOutputSampleRateHz(PcmAudioWriter.SAMPLE_RATE)
                        sonic.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
                        sonic.flush()
                        resampling = sonic.isActive
                        lastProgressTime = System.nanoTime()
                        advanced = true
                    }
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(channels > 0 && info.size % (channels * 2) == 0)
                                val buffer = decoder.getOutputBuffer(outputIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)

                                if (channels == 1) {
                                    if (resampling) {
                                        sonic.queueInput(buffer)
                                        drainSonic()
                                    } else {
                                        writer.write(buffer)
                                    }
                                } else {
                                    val size = info.size / channels
                                    if (monoBuffer.capacity() < size) {
                                        monoBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                                    }
                                    monoBuffer.clear()
                                    monoBuffer.limit(size)
                                    while (buffer.hasRemaining()) {
                                        var sum = 0
                                        repeat(channels) { sum += buffer.short.toInt() }
                                        monoBuffer.putShort((sum / channels).toShort())
                                    }
                                    monoBuffer.flip()
                                    if (resampling) {
                                        sonic.queueInput(monoBuffer)
                                        drainSonic()
                                    } else {
                                        writer.write(monoBuffer)
                                    }
                                }
                            }
                            outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            lastProgressTime = System.nanoTime()
                            advanced = true
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                if (!advanced && !outputEnded) {
                    delay(1)
                }
            }

            if (resampling) {
                sonic.queueEndOfStream()
                while (!sonic.isEnded) {
                    currentCoroutineContext().ensureActive()
                    drainSonic()
                }
            }

            return writer.complete()
        } finally {
            writer.close()
            sonic.reset()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
