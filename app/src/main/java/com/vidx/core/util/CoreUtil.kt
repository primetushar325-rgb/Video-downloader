package com.vidx.core.util

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** VIDX core utilities — pure Kotlin, no Android imports (JVM-testable). */

fun newId(): String = UUID.randomUUID().toString()

fun sha1(text: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

object ByteFmt {
    fun human(bytes: Long): String {
        if (bytes < 0) return "—"
        var v = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var u = 0
        while (v >= 1000 && u < units.size - 1) { v /= 1000.0; u++ }
        return if (u == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[u])
    }
}

object TimeFmt {
    /** 75432 -> "1:15" ; 3732 -> "1:02:00" */
    fun clock(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Transcript timestamp: 00:05 or 01:02:03 */
    fun transcriptStamp(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun date(epochMillis: Long): String {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(epochMillis))
        return d
    }
}

object Eta {
    fun human(remainingBytes: Long, speedBps: Long): String {
        if (speedBps <= 0 || remainingBytes <= 0) return "—"
        val secs = remainingBytes / speedBps
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> String.format(Locale.US, "%ds", s)
        }
    }
}

/**
 * Sanitizes user/content-supplied text into a safe file name.
 * Blocks path traversal (/, \, .., leading dots) and control characters.
 */
object FileNames {
    private val unsafe = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    fun sanitize(raw: String, fallback: String = "video", maxLen: Int = 90): String {
        // Replace separators/control chars with spaces, then drop dangerous "." / ".."
        // segments entirely so traversal fragments can never survive into a file name.
        val words = raw
            .replace(unsafe, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        var name = words.joinToString(" ")
        if (name.isEmpty()) name = fallback
        if (name.length > maxLen) name = name.take(maxLen).trimEnd()
        return name
    }

    fun unique(baseName: String, extension: String, existing: Set<String>): String {
        val ext = extension.trimStart('.').lowercase()
        var candidate = "$baseName.$ext"
        var n = 2
        while (candidate in existing) {
            candidate = "$baseName ($n).$ext"
            n++
        }
        return candidate
    }
}

object RetryPolicy {
    /** Fixed linear backoff plan. */
    fun delays(maxAttempts: Int, baseMillis: Long = 800L): List<Long> =
        (1 until maxAttempts).map { baseMillis * it }
}

/** Simple typed result to avoid exception-driven control flow in adapters. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val code: String, val message: String, val retryable: Boolean = false) : Outcome<Nothing>()

    fun isOk() = this is Ok
    fun errorOrNull(): Err? = (this as? Err)
    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }
}

inline fun <T, R> T.runCatchingOutcome(code: String, crossinline block: (T) -> R): Outcome<R> =
    try {
        Outcome.Ok(block(this))
    } catch (e: Exception) {
        Outcome.Err(code, e.message ?: "unexpected error", retryable = true)
    }
