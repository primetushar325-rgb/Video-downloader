package com.vidx.core.model

/**
 * Video metadata resolved through platform public APIs.
 * Every field is nullable unless the platform guarantees it — the UI shows
 * "Metadata unavailable" for missing values instead of inventing them.
 */
data class VideoMetadata(
    val url: String,
    val platform: Platform,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val durationMillis: Long?,
    val description: String? = null,
    val isLive: Boolean? = null,
    /** Downloadable qualities actually offered by the source (empty = not available). */
    val videoFormats: List<VideoFormat> = emptyList(),
    /** Audio-only streams actually offered by the source (empty = not available). */
    val audioFormats: List<VideoFormat> = emptyList(),
    /** Why downloads are unavailable on this platform (user-facing, honest). */
    val downloadNote: String? = null,
)

data class VideoFormat(
    val id: String,
    val qualityLabel: String,      // "360p", "720p", "Audio", …
    val container: String?,        // "mp4", "webm", …
    val mimeType: String?,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val fileSize: Long? = null,
    val audioOnly: Boolean = false,
    val estimatedSizeNote: String? = null,
)
