package com.vidx.core.platforms

import com.vidx.core.json.Json
import com.vidx.core.json.JsonValue
import com.vidx.core.json.JsonWriter
import com.vidx.core.model.Platform
import com.vidx.core.model.VideoMetadata
import com.vidx.core.transcript.TranscriptTrack
import com.vidx.core.util.Outcome
import com.vidx.core.util.runCatchingOutcomeFlat
import java.net.URLEncoder

/**
 * YouTube adapter.
 *
 * Policy (see docs/PLATFORM-NOTES.md):
 *  - Metadata: public oEmbed endpoint (title, author, thumbnail) — the endpoint
 *    YouTube documents for embedding.
 *  - Captions/transcript: the standard public caption track listing for public
 *    videos (same data YouTube's own player uses). Private / age-gated /
 *    members-only videos are reported as such — never bypassed.
 *  - Download: YouTube's Terms do not permit downloading without a provided
 *    download link, so VIDX does not expose YouTube stream URLs. The UI shows an
 *    honest "not available" note with an [Open in YouTube] action.
 */
object YouTubeAdapter : PlatformAdapter {

    override val platform = Platform.YOUTUBE

    private const val OEMBED = "https://www.youtube.com/oembed?format=json&url="

    private val videoIdRegex = Regex("[A-Za-z0-9_-]{6,20}")

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val videoId = com.vidx.core.url.UrlNormalizer.extractVideoId(
            Platform.YOUTUBE, com.vidx.core.url.ParsedUrl.parse(url) ?: return Outcome.Err("invalid_url", "Not a valid YouTube URL.")
        ) ?: return Outcome.Err("invalid_url", "Could not find a video ID in this YouTube URL.")

        val oembedUrl = OEMBED + URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
        return runCatchingOutcomeFlat("network") {
            val resp = PlatformAdapter.http.get(oembedUrl)
            when {
                resp.isOk() -> parseOEmbed((resp as com.vidx.core.util.Outcome.Ok).value.text(), url)
                resp.errorOrNull()?.code == "http_404" -> Outcome.Err("not_found", "This video is unavailable or does not exist.")
                else -> Outcome.Err(resp.errorOrNull()?.code ?: "network", resp.errorOrNull()?.message ?: "Failed to reach YouTube.")
            }
        }
    }

    private fun parseOEmbed(json: String, originalUrl: String): Outcome<VideoMetadata> {
        val obj = try { Json.parseObject(json) } catch (e: Exception) {
            return Outcome.Err("metadata_unavailable", "YouTube returned invalid metadata.")
        }
        val title = obj.str("title")
        val author = obj.str("author_name")
        val thumb = obj.str("thumbnail_url")
        if (title == null && author == null && thumb == null) {
            return Outcome.Err("metadata_unavailable", "Metadata unavailable for this video.")
        }
        return Outcome.Ok(
            VideoMetadata(
                url = originalUrl,
                platform = Platform.YOUTUBE,
                title = title,
                author = author,
                thumbnailUrl = thumb,
                durationMillis = null,
                downloadNote = "YouTube's Terms of Service do not permit downloading videos without an official download link. You can watch or save this video in the YouTube app.",
            )
        )
    }

    // ---------------------------------------------------------------- transcript

    private const val INNERTUBE = "https://www.youtube.com/youtubei/v1/player"

    /** Player response for public videos — used ONLY for caption track listing. */
    private fun fetchPlayer(videoId: String, clientName: String, clientVersion: String, hl: String): Outcome<JsonValue.Obj> {
        val body = JsonWriter.obj(
            "context" to JsonWriter.obj(
                "client" to JsonWriter.obj(
                    "clientName" to JsonWriter.string(clientName),
                    "clientVersion" to JsonWriter.string(clientVersion),
                    "hl" to JsonWriter.string(hl),
                    "gl" to JsonWriter.string("US"),
                ),
            ),
            "videoId" to JsonWriter.string(videoId),
        )
        val resp = PlatformAdapter.http.postJson("$INNERTUBE?prettyPrint=false", body)
        if (!resp.isOk()) {
            return Outcome.Err(resp.errorOrNull()?.code ?: "network", resp.errorOrNull()?.message ?: "Failed to reach YouTube.")
        }
        val obj = try { Json.parseObject((resp as com.vidx.core.util.Outcome.Ok).value.text()) } catch (e: Exception) {
            return Outcome.Err("metadata_unavailable", "YouTube returned an unreadable response.")
        }
        return Outcome.Ok(obj)
    }

    override fun listTranscriptTracks(url: String, metadata: VideoMetadata?): Outcome<List<TranscriptTrack>> {
        val videoId = com.vidx.core.url.UrlNormalizer.extractVideoId(
            Platform.YOUTUBE, com.vidx.core.url.ParsedUrl.parse(url) ?: return Outcome.Err("invalid_url", "Not a valid YouTube URL.")
        ) ?: return Outcome.Err("invalid_url", "Could not find a video ID in this YouTube URL.")

        val obj = when (val r = fetchPlayer(videoId, "ANDROID", "19.09.37", "en")) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }

        val playability = obj.obj("playabilityStatus")
        val status = playability?.str("status") ?: "UNKNOWN"
        when (status) {
            "LOGIN_REQUIRED" -> return Outcome.Err(
                if ((playability?.str("reason") ?: "").contains("age", ignoreCase = true)) "age_restricted" else "private_content",
                playability?.str("reason") ?: "This video requires sign-in."
            )
            "UNPLAYABLE", "ERROR" -> return Outcome.Err(
                "unavailable",
                playability?.str("reason") ?: "This video cannot be played publicly."
            )
            "OK" -> Unit
            else -> return Outcome.Err("unavailable", "Captions are not available for this video.")
        }

        val tracksJson = obj.path("captions", "playerCaptionsTracklistRenderer", "captionTracks") as? JsonValue.Arr
        if (tracksJson == null || tracksJson.items.isEmpty()) {
            return Outcome.Err("captions_unavailable", "No captions or transcript are available for this video.")
        }

        val tracks = tracksJson.items.mapNotNull { t ->
            val tObj = t as? JsonValue.Obj ?: return@mapNotNull null
            val baseUrl = tObj.str("baseUrl") ?: return@mapNotNull null
            val lang = tObj.str("languageCode") ?: "und"
            val name = tObj.pathStr("name", "simpleText") ?: tObj.pathStr("name", "runs", "0", "text") ?: lang
            val kind = tObj.str("kind") ?: ""
            val auto = kind == "asr" || (tObj.obj("name")?.str("simpleText") ?: "").contains("auto", ignoreCase = true)
            TranscriptTrack(
                id = baseUrl,
                langCode = lang,
                name = name,
                autoGenerated = auto,
                fetchUrl = baseUrl,
            )
        }
        if (tracks.isEmpty()) return Outcome.Err("captions_unavailable", "No captions or transcript are available for this video.")
        return Outcome.Ok(tracks)
    }
}
