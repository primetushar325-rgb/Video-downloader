package com.vidx.core.model

/**
 * Platforms VIDX understands, and — honestly — what it can actually do with each.
 *
 * Capability policy (see docs/PLATFORM-NOTES.md):
 *  - We never bypass DRM, auth, paywalls or access controls.
 *  - Metadata is resolved only through each platform's public, documented endpoints.
 *  - Downloads happen only where a platform exposes a public download URL.
 *  - Where a platform has no public download API, VIDX says so — it never fakes it.
 */
enum class Platform(
    val id: String,
    val displayName: String,
    /** Representative hosts used by the detector. */
    val hosts: List<String>,
    /** Canonical metadata endpoint class — drives what "Analyze" can show. */
    val metadata: Capability,
    /** Whether VIDX can legally/technically download media from this platform. */
    val download: Capability,
    /** Whether captions/transcripts can be retrieved. */
    val transcript: Capability,
) {
    YOUTUBE(
        "youtube", "YouTube",
        listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "youtube-nocookie.com"),
        Capability.PUBLIC_API, Capability.NOT_AVAILABLE, Capability.PUBLIC_API,
    ),
    TIKTOK(
        "tiktok", "TikTok",
        listOf("tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com"),
        Capability.PUBLIC_API, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    FACEBOOK(
        "facebook", "Facebook",
        listOf("facebook.com", "www.facebook.com", "m.facebook.com", "fb.watch", "fb.com", "web.facebook.com"),
        Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    INSTAGRAM(
        "instagram", "Instagram",
        listOf("instagram.com", "www.instagram.com", "instagr.am"),
        Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    REDNOTE(
        "rednote", "RedNote / Xiaohongshu",
        listOf("xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com"),
        Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    KUAISHOU(
        "kuaishou", "Kuaishou / Kwai",
        listOf("kuaishou.com", "www.kuaishou.com", "v.kuaishou.com", "kwai.com", "www.kwai.com"),
        Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    TWITTER_X(
        "x", "X / Twitter",
        listOf("x.com", "twitter.com", "mobile.twitter.com", "t.co", "vxtwitter.com", "fxtwitter.com"),
        Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    PINTEREST(
        "pinterest", "Pinterest",
        listOf("pinterest.com", "www.pinterest.com", "pin.it", "pinterest.co.uk", "pinterest.de", "pinterest.fr"),
        Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    VIMEO(
        "vimeo", "Vimeo",
        listOf("vimeo.com", "www.vimeo.com", "player.vimeo.com"),
        Capability.PUBLIC_API, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE,
    ),
    PEERTUBE(
        "peertube", "PeerTube",
        listOf("peertube.net"),
        Capability.PUBLIC_API, Capability.PUBLIC_API, Capability.PUBLIC_API,
    ),
    ARCHIVE_ORG(
        "archive", "Internet Archive",
        listOf("archive.org", "www.archive.org"),
        Capability.PUBLIC_API, Capability.PUBLIC_API, Capability.NOT_AVAILABLE,
    ),
    DIRECT(
        "direct", "Direct media",
        listOf(),
        Capability.HEAD_PROBE, Capability.PUBLIC_API, Capability.NOT_AVAILABLE,
    ),
    UNKNOWN("unknown", "Unknown", listOf(), Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE, Capability.NOT_AVAILABLE);

    enum class Capability {
        /** Fully implemented via a public/documented endpoint. */
        PUBLIC_API,
        /** Metadata obtained by probing the media file itself (HEAD requests). */
        HEAD_PROBE,
        /** Not available through any public, documented channel — reported honestly. */
        NOT_AVAILABLE,
    }

    companion object {
        fun fromId(id: String): Platform = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}
