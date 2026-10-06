package com.naylinhtike.smarttranscriber

data class TranscribeJob(
    val id: String = "",
    val fileName: String = "",
    val sourceName: String = "",
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val language: String = "my-MM",
    val state: String = STATE_QUEUED, // queued, preparing, processing, paused, completed, error
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val transcript: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val notionSyncState: String = NOTION_IDLE, // idle, syncing, synced, failed
    val notionPageUrl: String = "",
    val notionError: String = ""
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_PREPARING = "preparing"
        const val STATE_PROCESSING = "processing"
        const val STATE_PAUSED = "paused"
        const val STATE_COMPLETED = "completed"
        const val STATE_ERROR = "error"

        const val NOTION_IDLE = "idle"
        const val NOTION_SYNCING = "syncing"
        const val NOTION_SYNCED = "synced"
        const val NOTION_FAILED = "failed"
    }

    val isActive: Boolean
        get() = state in setOf(STATE_QUEUED, STATE_PREPARING, STATE_PROCESSING)

    val progressPercent: Float
        get() = when {
            state == STATE_COMPLETED -> 1.0f
            totalChunks > 0 -> completedChunks.toFloat() / totalChunks.toFloat()
            state == STATE_PREPARING -> 0.05f
            else -> 0f
        }

    fun formattedSize(): String {
        val mb = fileSizeBytes.toDouble() / (1024 * 1024)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(fileSizeBytes.toDouble() / 1024)
    }

    fun formattedDuration(): String {
        if (durationMs <= 0) return "--:--"
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val hr = min / 60
        return if (hr > 0) "%d:%02d:%02d".format(hr, min % 60, sec) else "%02d:%02d".format(min, sec)
    }
}
