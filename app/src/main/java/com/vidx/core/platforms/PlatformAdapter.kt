package com.vidx.core.platforms

import com.vidx.core.model.Platform
import com.vidx.core.model.VideoMetadata
import com.vidx.core.net.HttpClient
import com.vidx.core.transcript.TranscriptTrack
import com.vidx.core.util.Outcome

/**
 * Platform adapter — the extension point of VIDX.
 *
 * Every platform is an independent adapter behind this one interface.
 * Adding a platform later = one new file + one registry entry; nothing else changes.
 *
 * Adapters only use public, documented endpoints and never bypass DRM / auth /
 * paywalls. An adapter reports [Platform.Capability.NOT_AVAILABLE] when a feature
 * cannot be legally or technically provided.
 */
interface PlatformAdapter {
    val platform: Platform

    /**
     * Resolves metadata for a public video URL.
     * Errors use stable codes: invalid_url, unsupported, private_content,
     * not_found, network, timeout, rate_limited, server_error, metadata_unavailable.
     */
    fun resolveMetadata(url: String): Outcome<VideoMetadata>

    /**
     * Lists available caption/transcript tracks. Return an empty list when the
     * platform exposes none (never fabricate).
     */
    fun listTranscriptTracks(url: String, metadata: VideoMetadata?): Outcome<List<TranscriptTrack>> = Outcome.Ok(emptyList())

    companion object {
        /** Standard UA + client injected by the registry (testable with local servers). */
        val http = HttpClient
    }
}

/** Adapts a generic error into a user-friendly, categorized message. */
fun Outcome.Err.toUserMessage(default: String = "Something went wrong."): String = when (code) {
    "invalid_url" -> "This doesn't look like a valid video URL."
    "unsupported" -> "This platform is not supported for this action."
    "private_content" -> "This video is private or requires sign-in. VIDX never bypasses access controls."
    "age_restricted" -> "This video is age-restricted and requires sign-in."
    "not_found" -> "The video was not found — it may have been removed."
    "rate_limited" -> "The platform asked us to slow down. Please wait a moment and retry."
    "network" -> "Network error — check your connection and retry."
    "timeout" -> "The request timed out. Try again."
    "metadata_unavailable" -> "Metadata unavailable for this video."
    else -> message.ifBlank { default }
}
