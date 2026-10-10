package com.naylinhtike.smarttranscriber

import java.util.Locale

/** Times come from decoded PCM, never from model-generated timestamps. */
internal object TranscriptFormatter {
    const val SECTION_SECONDS = 600L

    fun timestamp(seconds: Long): String = String.format(
        Locale.ROOT, "[%02d:%02d:%02d]", seconds / 3600, seconds / 60 % 60, seconds % 60
    )

    fun assemble(spans: List<AudioChunkSpan>, read: (Int) -> String): String {
        val output = mutableListOf<String>()
        var lastSection = -1L
        for (span in spans.sortedBy { it.index }) {
            val text = read(span.index).trim()
            if (text.isBlank()) continue // A missing checkpoint must not invent transcript text.
            val seconds = span.startSample / PcmAudioWriter.SAMPLE_RATE
            val section = seconds / SECTION_SECONDS
            if (section != lastSection) {
                // Older jobs may have silence-based boundaries that are not exactly ten minutes.
                // Label their real audio position rather than pretending to know word-level timing.
                output += timestamp(seconds)
                lastSection = section
            }
            output += if (text == "[no speech]") "[စကားသံ မရှိပါ / No speech]" else text
        }
        return output.joinToString("\n\n")
    }

    fun document(job: TranscribeJob): String = buildString {
        appendLine(job.fileName.substringBeforeLast('.').ifBlank { "တရားတော်" })
        appendLine("အသံကြာချိန်: ${job.formattedDuration()}")
        if (job.state != TranscribeJob.STATE_COMPLETED) appendLine("[မပြီးသေးသော စာမူ]")
        appendLine()
        append(job.transcript)
    }
}
