package com.vidx.core.url

import com.vidx.core.model.Platform

/**
 * URL validation — the first line of defense for every input in VIDX.
 *
 * Rules:
 *  - http/https only (file:, javascript:, data:, intent: … are rejected).
 *  - Must parse as an absolute URL with a host.
 *  - Host must be syntactically plausible (no spaces/control chars — the parser
 *    already rejects those, but we also defensively black-list credentials "@".
 *    Actually userinfo is allowed by RFC; VIDX strips it during normalization).
 *  - The platform is detected; unknown hosts are rejected with "Unsupported platform"
 *    unless they look like a direct media file (Direct media platform).
 */
object UrlValidator {

    sealed class ValidationError(val code: String, val message: String) {
        object Empty : ValidationError("empty", "Enter a video URL")
        object NoScheme : ValidationError("no_scheme", "URL must start with https://")
        object BadScheme : ValidationError("bad_scheme", "Only http:// and https:// URLs are supported")
        object NoHost : ValidationError("no_host", "This does not look like a valid URL")
        object HostInvalid : ValidationError("host_invalid", "The URL contains an invalid host name")
        object UnsupportedPlatform : ValidationError("unsupported", "Unsupported platform or website")
        data class TooLong(val max: Int) : ValidationError("too_long", "URL is too long (max $max characters)")
    }

    const val MAX_URL_LENGTH = 4096

    /** Validates scheme/host only — used before platform detection. */
    fun validateShape(raw: String): ValidationError? {
        val url = raw.trim()
        if (url.isEmpty()) return ValidationError.Empty
        if (url.length > MAX_URL_LENGTH) return ValidationError.TooLong(MAX_URL_LENGTH)

        val schemeIdx = url.indexOf("://")
        if (schemeIdx <= 0) {
            // No "://" — but a leading word followed by ':' is an explicit scheme
            // (e.g. "javascript:alert(1)"), which must be rejected as bad scheme.
            val colonIdx = url.indexOf(':')
            if (colonIdx > 0 && url.substring(0, colonIdx).matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$"))) {
                return ValidationError.BadScheme
            }
            return ValidationError.NoScheme
        }
        val scheme = url.substring(0, schemeIdx).lowercase()
        if (scheme != "http" && scheme != "https") return ValidationError.BadScheme

        val rest = url.substring(schemeIdx + 3)
        val hostAndRest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostAndRest.substringAfterLast('@') // strip userinfo
        if (host.isEmpty()) return ValidationError.NoHost
        if (!hostLooksValid(host)) return ValidationError.HostInvalid
        return null
    }

    private fun hostLooksValid(host: String): Boolean {
        if (host.length > 253) return false
        val h = host.removeSuffix(".").lowercase()
        if (h.isEmpty()) return false
        // IP literal (v4 or [v6])
        if (h.startsWith("[") && h.endsWith("]")) return true
        if (h.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            return h.split('.').all { it.toIntOrNull() in 0..255 }
        }
        val labels = h.split('.')
        if (labels.size < 2 && h != "localhost") return false
        val labelRe = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        return labels.all { labelRe.matches(it) }
    }

    /** Full validation: shape + platform support. Returns null when the URL is usable. */
    fun validate(raw: String): ValidationError? {
        validateShape(raw)?.let { return it }
        val detected = PlatformDetector.detect(raw)
        if (detected == Platform.UNKNOWN) return ValidationError.UnsupportedPlatform
        return null
    }
}
