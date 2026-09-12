package com.vidx.core.platforms

import com.vidx.core.model.Platform
import com.vidx.core.model.VideoFormat
import com.vidx.core.model.VideoMetadata
import com.vidx.core.url.ParsedUrl
import com.vidx.core.util.Outcome
import com.vidx.core.util.runCatchingOutcomeFlat

/**
 * Direct media adapter — public direct links to a single media file
 * (.mp4, .webm, .mp3, …). Metadata comes from the file itself via a HEAD probe
 * (content type + size). This is a real, fully working download path.
 */
object DirectAdapter : PlatformAdapter {

    override val platform = Platform.DIRECT

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val parsed = ParsedUrl.parse(url) ?: return Outcome.Err("invalid_url", "Not a valid URL.")
        if (!com.vidx.core.url.PlatformDetector.isDirectMediaUrl(parsed)) {
            return Outcome.Err("invalid_url", "This URL does not point to a media file.")
        }

        val title = parsed.path.substringAfterLast('/').substringBefore('?')
            .replace(Regex("%20|\\+"), " ")
            .replace(Regex("\\.[A-Za-z0-9]{2,5}$"), "")
            .replace('_', ' ').replace('-', ' ').trim()
            .ifBlank { "media" }

        return runCatchingOutcomeFlat("network") {
            val head = PlatformAdapter.http.request(url, "HEAD")
            val headResp: com.vidx.core.net.HttpClient.Response = when {
                head.isOk() -> (head as Outcome.Ok).value
                head.errorOrNull()?.code == "http_405" -> {
                    // Some servers reject HEAD; fall back to a ranged GET.
                    val ranged = PlatformAdapter.http.request(url, "GET", headers = mapOf("Range" to "bytes=0-0"))
                    if (!ranged.isOk()) {
                        val e = ranged.errorOrNull()
                        return@runCatchingOutcomeFlat Outcome.Err(e?.code ?: "network", e?.message ?: "Could not reach the media file.")
                    }
                    (ranged as Outcome.Ok).value
                }
                else -> {
                    val e = head.errorOrNull()
                    return@runCatchingOutcomeFlat Outcome.Err(e?.code ?: "network", e?.message ?: "Could not reach the media file.")
                }
            }

            val base = buildMeta(headResp, url, title)
            val needSize = base is Outcome.Ok &&
                base.value.videoFormats.all { it.fileSize == null } &&
                base.value.audioFormats.all { it.fileSize == null }
            if (needSize) {
                // Some runtimes hide Content-Length on HEAD responses; resolve the
                // size via Content-Range from a ranged GET probe.
                val ranged = PlatformAdapter.http.request(url, "GET", headers = mapOf("Range" to "bytes=0-0"))
                if (ranged.isOk()) {
                    val r = (ranged as Outcome.Ok).value
                    val total = r.header("content-range")?.substringAfterLast('/')?.toLongOrNull()
                    val m = (base as Outcome.Ok).value
                    Outcome.Ok(
                        m.copy(
                            videoFormats = m.videoFormats.map { f -> f.copy(fileSize = total) },
                            audioFormats = m.audioFormats.map { f -> f.copy(fileSize = total) },
                        )
                    )
                } else base
            } else base
        }
    }

    private fun buildMeta(resp: com.vidx.core.net.HttpClient.Response, url: String, title: String): Outcome<VideoMetadata> {
        val contentType = resp.header("content-type")?.substringBefore(';')?.trim()
        val size = resp.contentLength()
        val isAudio = contentType?.startsWith("audio") == true
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        val label = if (isAudio) "Audio" else (contentType ?: ext.uppercase())
        val fmt = VideoFormat(
            id = "direct",
            qualityLabel = label,
            container = ext.ifBlank { contentType },
            mimeType = contentType,
            url = url,
            fileSize = size.takeIf { it > 0 },
            audioOnly = isAudio,
            estimatedSizeNote = if (size > 0) null else "Size unknown until download starts",
        )
        return Outcome.Ok(
            VideoMetadata(
                url = url, platform = Platform.DIRECT,
                title = title, author = null, thumbnailUrl = null, durationMillis = null,
                videoFormats = if (isAudio) emptyList() else listOf(fmt),
                audioFormats = if (isAudio) listOf(fmt) else emptyList(),
            )
        )
    }
}
