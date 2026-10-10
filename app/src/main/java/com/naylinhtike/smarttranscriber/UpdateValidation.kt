package com.naylinhtike.smarttranscriber

import java.io.File
import java.security.MessageDigest

internal object UpdateValidation {
    fun isNewerVersion(current: String, remote: String): Boolean {
        fun parse(value: String): List<Long>? {
            val clean = value.trim().removePrefix("v").removePrefix("V")
            if (!clean.matches(Regex("[0-9]+(\\.[0-9]+){0,3}"))) return null
            return clean.split('.').map { it.toLongOrNull() ?: return null }
        }
        val installed = parse(current) ?: return false
        val available = parse(remote) ?: return false
        for (index in 0 until maxOf(installed.size, available.size)) {
            val left = installed.getOrElse(index) { 0 }
            val right = available.getOrElse(index) { 0 }
            if (left != right) return right > left
        }
        return false
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
