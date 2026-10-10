package com.naylinhtike.smarttranscriber

import android.util.AtomicFile
import java.io.File

internal fun writeAtomicText(file: File, text: String) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        output.write(text.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(output)
    } catch (e: Exception) {
        atomic.failWrite(output)
        throw e
    }
}

internal class ChunkCheckpoints(
    private val directory: File,
    private val reader: (File) -> String = { file -> AtomicFile(file).openRead().bufferedReader().use { it.readText() } },
    private val writer: (File, String) -> Unit = ::writeAtomicText
) {
    fun read(index: Int): String = runCatching {
        reader(File(directory, "$index.txt")).trim()
    }.getOrDefault("")

    fun completed(total: Int): Int = (0 until total).count { read(it).isNotBlank() }

    fun transcript(total: Int): String = (0 until total).map { read(it) }
        .filter { it.isNotBlank() && it != "[no speech]" }.joinToString("\n\n")

    fun save(index: Int, transcript: String) {
        require(transcript.isNotBlank())
        writer(File(directory, "$index.txt"), transcript)
    }
}
