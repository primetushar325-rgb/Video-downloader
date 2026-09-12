package com.vidx.core.url

import com.vidx.core.model.Platform

/**
 * URL normalization + canonical video identity.
 *
 * Two forms of identity are computed:
 *  1. [NormalizedUrl.display] — a cleaned, human-friendly URL (tracking params stripped).
 *  2. [NormalizedUrl.canonicalKey] — the underlying *video* identity when it can be
 *     reliably determined from the URL structure (e.g. YouTube video id). Duplicate
 *     detection compares canonical keys FIRST and falls back to normalized URLs.
 *
 * Policy: a canonical key is produced only when the identifier is unambiguous.
 * URLs that cannot be reduced to a proven video identity keep their full normalized
 * URL as key, so two genuinely different videos are never blocked.
 */
data class NormalizedUrl(
    val original: String,
    val display: String,
    val canonicalKey: String,
    val platform: Platform,
    val videoId: String?,
)

object UrlNormalizer {

    private val trackingParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "mc_cid", "mc_eid", "igshid",
        "igsh", "si", "feature", "ref", "ref_src", "source", "sharesheet", "app",
        "from", "spm", "scm", "tracking_source", "share_source", "share_medium",
        "share_plat", "share_tag", "share_time", "timestamp", "s",
    )

    /** Always-kept parameters per platform (identity-bearing). */
    private val keepParams = setOf(
        "v", "list", "t", "start", "end", "p", "playlist", "itemId", "video_id",
        "id", "lang", "hl", "theme", "sharing", "w", "h",
    )

    fun normalize(raw: String): NormalizedUrl {
        val platform = PlatformDetector.detect(raw)
        val parsed = ParsedUrl.parse(raw)

        if (parsed == null) {
            val t = raw.trim()
            return NormalizedUrl(raw, t, "raw:$t", platform, null)
        }

        val host = parsed.host.lowercase().removeSuffix(".")
        val defaultPortStripped = if ((parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        ) 0 else parsed.port

        // Rebuild query, dropping tracking params, preserving order of the rest.
        val keptQuery = parsed.query?.let { q ->
            val parts = q.split('&').filter { it.isNotEmpty() }
            val kept = parts.filter { p ->
                val name = p.substringBefore('=').lowercase()
                name in keepParams || name !in trackingParams
            }
            if (kept.isEmpty()) null else kept.joinToString("&")
        }

        val portPart = if (defaultPortStripped != 0) ":$defaultPortStripped" else ""
        val display = buildString {
            append(parsed.scheme).append("://").append(host).append(portPart).append(parsed.path)
            keptQuery?.let { append('?').append(it) }
        }

        val videoId = extractVideoId(platform, parsed)
        val canonicalKey = if (videoId != null) "${platform.id}:$videoId" else "url:$display"

        return NormalizedUrl(raw, display, canonicalKey, platform, videoId)
    }

    /** Per-platform canonical video identifiers. Returns null when not determinable. */
    fun extractVideoId(platform: Platform, parsed: ParsedUrl): String? {
        val path = parsed.path.trimEnd('/')
        val query = parsed.query ?: ""
        val params = parseQuery(query)

        return when (platform) {
            Platform.YOUTUBE -> {
                when {
                    params["v"]?.matches(Regex("[A-Za-z0-9_-]{6,20}")) == true -> params["v"]
                    path.startsWith("/shorts/") -> path.substringAfter("/shorts/").substringBefore('/').takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
                    path.startsWith("/live/") -> path.substringAfter("/live/").substringBefore('/').takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
                    path.startsWith("/embed/") -> path.substringAfter("/embed/").substringBefore('/').takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
                    // youtu.be/{id}
                    parsed.host == "youtu.be" -> path.substringAfter('/').substringBefore('/').takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
                    else -> null
                }
            }
            Platform.TIKTOK -> {
                val m = Regex("/video/(\\d{10,21})").find(path)
                m?.groupValues?.get(1)
            }
            Platform.INSTAGRAM -> {
                val m = Regex("/(p|reel|reels|tv)/([A-Za-z0-9_-]{5,15})").find(path)
                m?.groupValues?.get(2)
            }
            Platform.FACEBOOK -> {
                when {
                    params["v"]?.matches(Regex("\\d{5,20}")) == true -> params["v"]
                    else -> Regex("/(reel|videos)/(\\d{5,20})").find(path)?.groupValues?.get(2)
                }
            }
            Platform.REDNOTE -> {
                when {
                    params["itemId"] != null -> params["itemId"]
                    else -> Regex("/(explore|discovery/item)/([A-Za-z0-9]+)").find(path)?.groupValues?.get(2)
                }
            }
            Platform.KUAISHOU -> {
                Regex("/(short-video|fw/photo|fw/video)/([A-Za-z0-9]+)").find(path)?.groupValues?.get(2)
            }
            Platform.TWITTER_X -> {
                Regex("/status/(\\d{5,30})").find(path)?.groupValues?.get(1)
            }
            Platform.PINTEREST -> {
                Regex("/pin/(\\d{5,25})").find(path)?.groupValues?.get(1)
            }
            Platform.VIMEO -> {
                path.split('/').firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.takeIf { it.length in 6..12 }
            }
            Platform.PEERTUBE -> {
                Regex("^/(w|videos/watch)/([A-Za-z0-9_-]{6,40})$").find(path)?.groupValues?.get(2)?.let {
                    "${parsed.host}:$it"
                }
            }
            Platform.ARCHIVE_ORG -> {
                Regex("/details/([A-Za-z0-9_.-]+)").find(path)?.groupValues?.get(1)
            }
            Platform.DIRECT -> null // file identity = the URL itself
            else -> null
        }
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = (if (idx >= 0) pair.substring(0, idx) else pair)
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }
}
