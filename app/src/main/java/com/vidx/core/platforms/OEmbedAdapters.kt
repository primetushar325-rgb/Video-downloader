package com.vidx.core.platforms

import com.vidx.core.json.Json
import com.vidx.core.model.Platform
import com.vidx.core.model.VideoMetadata
import com.vidx.core.util.Outcome
import com.vidx.core.util.runCatchingOutcomeFlat
import java.net.URLEncoder

/**
 * oEmbed-based adapters for platforms whose only public metadata endpoint is
 * their documented oEmbed API. None of these platforms exposes a public download
 * endpoint, so downloads stay honestly unavailable.
 */
object TikTokAdapter : PlatformAdapter {
    override val platform = Platform.TIKTOK
    private const val OEMBED = "https://www.tiktok.com/oembed?url="

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val u = URLEncoder.encode(url, "UTF-8")
        return runCatchingOutcomeFlat("network") {
            val resp = PlatformAdapter.http.get(OEMBED + u)
            if (!resp.isOk()) {
                val e = resp.errorOrNull()
                return@runCatchingOutcomeFlat when (e?.code) {
                    "http_404" -> Outcome.Err("not_found", "This TikTok video is unavailable or does not exist.")
                    "http_403" -> Outcome.Err("private_content", "This TikTok video is private or restricted.")
                    else -> Outcome.Err(e?.code ?: "network", e?.message ?: "Failed to reach TikTok.")
                }
            }
            val o = Json.parseObject((resp as com.vidx.core.util.Outcome.Ok).value.text())
            Outcome.Ok(
                VideoMetadata(
                    url = url,
                    platform = Platform.TIKTOK,
                    title = o.str("title") ?: "TikTok video",
                    author = o.str("author_name"),
                    thumbnailUrl = o.str("thumbnail_url"),
                    durationMillis = null,
                    downloadNote = "TikTok does not provide a public download API. Use the TikTok app's official save button.",
                )
            )
        }
    }
}

object VimeoAdapter : PlatformAdapter {
    override val platform = Platform.VIMEO
    private const val OEMBED = "https://vimeo.com/api/oembed.json?url="

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val u = URLEncoder.encode(url, "UTF-8")
        return runCatchingOutcomeFlat("network") {
            val resp = PlatformAdapter.http.get(OEMBED + u)
            if (!resp.isOk()) {
                val e = resp.errorOrNull()
                return@runCatchingOutcomeFlat when (e?.code) {
                    "http_404" -> Outcome.Err("not_found", "This Vimeo video is unavailable or does not exist.")
                    "http_403" -> Outcome.Err("private_content", "This Vimeo video is private.")
                    else -> Outcome.Err(e?.code ?: "network", e?.message ?: "Failed to reach Vimeo.")
                }
            }
            val o = Json.parseObject((resp as com.vidx.core.util.Outcome.Ok).value.text())
            Outcome.Ok(
                VideoMetadata(
                    url = url,
                    platform = Platform.VIMEO,
                    title = o.str("title"),
                    author = o.str("author_name"),
                    thumbnailUrl = o.str("thumbnail_url"),
                    durationMillis = o.int("duration")?.let { it * 1000L },
                    downloadNote = "Vimeo does not expose a public download endpoint. Download may be offered on the video's Vimeo page.",
                )
            )
        }
    }
}
