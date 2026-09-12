package com.vidx.core.url

import com.vidx.core.model.Platform

/**
 * Platform detection — maps a URL to a [Platform] via explicit host rules.
 *
 * Design: ordered, table-driven rules. Adding a new platform = adding an enum entry
 * (+ its hosts) and an adapter file — nothing else in the app needs to change.
 */
object PlatformDetector {

    private data class Rule(val hosts: List<String>, val platform: Platform)

    // Order matters: most specific domains first.
    private val rules: List<Rule> = listOf(
        Rule(listOf("youtu.be", "youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com", "youtube-nocookie.com"), Platform.YOUTUBE),
        Rule(listOf("tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com"), Platform.TIKTOK),
        Rule(listOf("vimeo.com", "www.vimeo.com", "player.vimeo.com"), Platform.VIMEO),
        Rule(listOf("archive.org", "www.archive.org"), Platform.ARCHIVE_ORG),
        Rule(listOf("instagram.com", "www.instagram.com", "instagr.am"), Platform.INSTAGRAM),
        Rule(listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.watch", "fb.com"), Platform.FACEBOOK),
        Rule(listOf("xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com"), Platform.REDNOTE),
        Rule(listOf("kuaishou.com", "www.kuaishou.com", "v.kuaishou.com", "kwai.com", "www.kwai.com"), Platform.KUAISHOU),
        Rule(listOf("x.com", "twitter.com", "mobile.twitter.com", "t.co", "vxtwitter.com", "fxtwitter.com"), Platform.TWITTER_X),
        Rule(listOf("pinterest.com", "www.pinterest.com", "pin.it", "pinterest.co.uk", "pinterest.de", "pinterest.fr"), Platform.PINTEREST),
    )

    private val pathRules: List<Pair<(List<String>) -> Platform?, Platform>> = listOf()

    /**
     * PeerTube: any host, but only when the path matches a PeerTube video route —
     * avoids false-positives on random sites (see "Never incorrectly block two
     * genuinely different videos" and "unknown hosts" policy).
     */
    private val peertubePathRe = Regex("^/(w|videos/watch)/[A-Za-z0-9_-]{6,40}$")

    fun detect(rawUrl: String): Platform {
        val url = rawUrl.trim()
        if (!url.contains("://")) {
            // No scheme: apply the same rules to the bare host so "youtu.be/xyz" works.
            val bare = "https://$url"
            return detect(bare)
        }

        val parsed = ParsedUrl.parse(url) ?: return Platform.UNKNOWN
        val host = parsed.host.lowercase().removeSuffix(".")

        for (rule in rules) {
            for (h in rule.hosts) {
                if (host == h || host.endsWith(".$h")) return rule.platform
            }
        }

        // PeerTube instances: arbitrary domains with a PeerTube video path.
        val path = parsed.path.trimEnd('/')
        if (peertubePathRe.matches(path)) return Platform.PEERTUBE

        // Direct media files.
        if (isDirectMediaUrl(parsed)) return Platform.DIRECT

        return Platform.UNKNOWN
    }

    /** True when the URL clearly points at a single public media file. */
    fun isDirectMediaUrl(parsed: ParsedUrl): Boolean {
        val path = parsed.path.lowercase()
        val exts = listOf(
            ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".mp3", ".m4a", ".aac",
            ".ogg", ".opus", ".wav", ".flac", ".3gp", ".avi", ".ts",
        )
        return exts.any { path.endsWith(it) } || path.contains(Regex("\\.mp4(\\?|$)|\\.webm(\\?|$)"))
    }
}

/** Minimal URL decomposition (scheme, host, port, path, query, fragment). */
data class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    companion object {
        fun parse(raw: String): ParsedUrl? {
            val trimmed = raw.trim()
            val schemeIdx = trimmed.indexOf("://")
            if (schemeIdx <= 0) return null
            val scheme = trimmed.substring(0, schemeIdx).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = trimmed.substring(schemeIdx + 3)

            val fragmentIdx = rest.indexOf('#')
            val withoutFragment = if (fragmentIdx >= 0) rest.substring(0, fragmentIdx) else rest
            val queryIdx = withoutFragment.indexOf('?')
            val withoutQuery = if (queryIdx >= 0) withoutFragment.substring(0, queryIdx) else withoutFragment
            val query = if (queryIdx >= 0) withoutFragment.substring(queryIdx + 1) else null
            val fragment = if (fragmentIdx >= 0) rest.substring(fragmentIdx + 1) else null

            val authority = withoutQuery.substringBefore('/')
            val path = withoutQuery.substring(authority.length)

            val userInfoStripped = authority.substringAfterLast('@')
            val hostAndPort = userInfoStripped.removePrefix("[").let { if (it.contains("]")) it.substringAfter(']') else it }
            val colonIdx = hostAndPort.lastIndexOf(':')
            val host: String
            val port: Int
            if (colonIdx > 0 && hostAndPort.substring(colonIdx + 1).all { it.isDigit() }) {
                host = if (userInfoStripped.startsWith("[")) userInfoStripped.substring(1, colonIdx - 1) else userInfoStripped.substring(0, colonIdx)
                port = hostAndPort.substring(colonIdx + 1).toIntOrNull() ?: 0
            } else {
                host = if (userInfoStripped.startsWith("[")) userInfoStripped.substring(1, userInfoStripped.length - 1) else userInfoStripped
                port = 0
            }
            if (host.isEmpty()) return null
            return ParsedUrl(scheme, host, port, path.ifEmpty { "/" }, query, fragment)
        }
    }
}
