package com.vidx.core.clipboard

import com.vidx.core.model.Platform
import com.vidx.core.url.PlatformDetector
import com.vidx.core.url.UrlValidator

/**
 * Clipboard classification — decides whether a clipboard snippet is a video link
 * worth offering, or sensitive content that must NEVER be imported.
 *
 * Privacy policy (enforced by the UI layer):
 *  - only URLs matching supported public video domains are recognized
 *  - PINs / passwords / OTPs / card numbers / random text are rejected
 *  - the clipboard is only read while the app is in the foreground
 */
object ClipboardClassifier {

    sealed class Verdict {
        data class VideoLink(val url: String, val platform: Platform) : Verdict()
        object NotVideo : Verdict()
        data class Sensitive(val why: String) : Verdict()
    }

    fun classify(raw: String?): Verdict {
        if (raw == null) return Verdict.NotVideo
        val text = raw.trim()
        if (text.isEmpty() || text.length > UrlValidator.MAX_URL_LENGTH) return Verdict.NotVideo

        // URL-shaped content is classified by its platform FIRST. Sensitive-data
        // filters only apply to non-URL clipboard content (a long video ID inside
        // a URL must never be mistaken for a card number).
        val isUrlShaped = text.startsWith("http://") || text.startsWith("https://") || looksLikeBareUrl(text)
        if (isUrlShaped) {
            val platform = PlatformDetector.detect(text)
            return if (platform == Platform.UNKNOWN) Verdict.NotVideo else Verdict.VideoLink(text, platform)
        }

        // Sensitive-data filters run BEFORE the "no dot" fast-reject so that
        // PINs / OTPs / card numbers (which contain no '.') are still caught.
        sensitive(text)?.let { return Verdict.Sensitive(it) }
        if (!text.contains(".") && !text.contains("://")) return Verdict.NotVideo
        return Verdict.NotVideo
    }

    private fun looksLikeBareUrl(text: String): Boolean {
        if (text.contains(' ') || text.contains('\n')) return false
        val firstSlash = text.indexOf('/')
        if (firstSlash <= 0) return false
        val host = text.substring(0, firstSlash)
        return host.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,10}$"))
    }

    /** Returns a human reason when the clipboard content must be ignored. */
    private fun sensitive(text: String): String? {
        val t = text.replace(" ", "")
        val digitCount = t.count { it.isDigit() }
        when {
            digitCount in 13..19 && Regex("(\\d[\\s-]?){13,19}").containsMatchIn(text) ->
                return "Looks like a card number — not imported."
            t.length in 4..8 && digitCount == t.length ->
                return "Looks like a PIN or one-time code — not imported."
            Regex("password|passwd|pwd|otp|token|secret", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                return "Looks like a credential — not imported."
            t.matches(Regex("\\d{10,}")) -> return "Looks like a phone number or code — not imported."
            t.contains("@") && !t.contains("/") && !t.startsWith("http") ->
                return "Looks like an email address — not imported."
        }
        return null
    }
}
