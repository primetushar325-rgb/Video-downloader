package com.vidx.core.platforms

import com.vidx.core.json.Json
import com.vidx.core.json.JsonValue
import com.vidx.core.model.Platform
import com.vidx.core.model.VideoFormat
import com.vidx.core.model.VideoMetadata
import com.vidx.core.transcript.TranscriptTrack
import com.vidx.core.url.ParsedUrl
import com.vidx.core.util.Outcome
import com.vidx.core.util.runCatchingOutcomeFlat

/**
 * PeerTube adapter — the fully-supported open platform.
 *
 * PeerTube publishes a complete public REST API (v1) including media file URLs
 * and caption files, and instances exist specifically to share public video.
 * This adapter uses only that public API:
 *   GET {origin}/api/v1/videos/{id}     → metadata, files, captions
 * Caption content is fetched from the caption file URL and parsed (VTT).
 */
object PeertubeAdapter : PlatformAdapter {

    override val platform = Platform.PEERTUBE

    private val idRegex = Regex("^/(w|videos/watch)/([A-Za-z0-9_-]{6,40})$")

    private fun parseTarget(url: String): Pair<String, String>? { // origin, videoId
        val p = ParsedUrl.parse(url) ?: return null
        val m = idRegex.find(p.path) ?: return null
        return ("${p.scheme}://${p.host}" + (if (p.port != 0) ":${p.port}" else "")) to m.groupValues[2]
    }

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val (origin, id) = parseTarget(url) ?: return Outcome.Err("invalid_url", "Not a valid PeerTube video URL.")
        return runCatchingOutcomeFlat("network") {
            val resp = PlatformAdapter.http.get("$origin/api/v1/videos/$id", maxBytes = 16L * 1024 * 1024)
            if (!resp.isOk()) {
                val e = resp.errorOrNull()
                return@runCatchingOutcomeFlat when (e?.code) {
                    "http_404" -> Outcome.Err("not_found", "This PeerTube video was not found on $origin.")
                    else -> Outcome.Err(e?.code ?: "network", e?.message ?: "Failed to reach the PeerTube instance.")
                }
            }
            val root = try { Json.parseObject((resp as com.vidx.core.util.Outcome.Ok).value.text()) } catch (ex: Exception) {
                return@runCatchingOutcomeFlat Outcome.Err("metadata_unavailable", "The instance returned invalid metadata.")
            }

            val title = root.str("name")
            val author = root.pathStr("channel", "displayName") ?: root.pathStr("account", "displayName")
            val thumbPath = root.str("thumbnailPath")
            val thumb = if (thumbPath != null) origin + thumbPath else root.str("previewPath")?.let { origin + it }
            val duration = root.long("duration")?.times(1000)
            val description = root.str("description")

            val formats = mutableListOf<VideoFormat>()
            val audioFormats = mutableListOf<VideoFormat>()

            fun addFile(f: JsonValue.Obj, streamType: String) {
                val fileUrl = f.str("fileDownloadUrl") ?: f.str("fileUrl") ?: return
                val abs = if (fileUrl.startsWith("http")) fileUrl else origin + fileUrl
                val res = f.obj("resolution")
                val label = res?.str("label") ?: "original"
                val isAudio = streamType == "audio" || (f.str("mimeType")?.startsWith("audio") == true)
                val container = f.str("ext") ?: abs.substringAfterLast('.', "?").substringBefore('?').lowercase()
                val fmt = VideoFormat(
                    id = f.str("id") ?: label,
                    qualityLabel = if (isAudio) "Audio ($label)" else label,
                    container = container,
                    mimeType = f.str("mimeType"),
                    url = abs,
                    width = res?.int("width"),
                    height = res?.int("height"),
                    fileSize = f.long("size"),
                    audioOnly = isAudio,
                )
                if (isAudio) audioFormats.add(fmt) else formats.add(fmt)
            }

            root.arr("files")?.items?.forEach { (it as? JsonValue.Obj)?.let { f -> addFile(f, "video") } }

            // streaming playlists (HLS): PeerTube serves fragmented HLS files there,
            // which are not a clean progressive download — they are deliberately not
            // offered. The `files[]` array above is PeerTube's documented download list.
            // (If a future PeerTube version exposes progressive mp4/webm in playlists,
            // they can be added here with the same honesty checks.)

            if (formats.isEmpty() && audioFormats.isEmpty()) {
                return@runCatchingOutcomeFlat Outcome.Ok(
                    VideoMetadata(
                        url = url, platform = Platform.PEERTUBE,
                        title = title, author = author, thumbnailUrl = thumb,
                        durationMillis = duration, description = description,
                        downloadNote = "This video does not expose a direct download file on the instance.",
                    )
                )
            }

            return@runCatchingOutcomeFlat Outcome.Ok(
                VideoMetadata(
                    url = url, platform = Platform.PEERTUBE,
                    title = title, author = author, thumbnailUrl = thumb,
                    durationMillis = duration, description = description,
                    videoFormats = formats.sortedWith(compareByDescending<VideoFormat> { it.height ?: 0 }.thenBy { it.id }),
                    audioFormats = audioFormats,
                )
            )
        }
    }

    override fun listTranscriptTracks(url: String, metadata: VideoMetadata?): Outcome<List<TranscriptTrack>> {
        val (origin, id) = parseTarget(url) ?: return Outcome.Err("invalid_url", "Not a valid PeerTube video URL.")
        return runCatchingOutcomeFlat("network") {
            val resp = PlatformAdapter.http.get("$origin/api/v1/videos/$id/captions")
            if (!resp.isOk()) {
                if (resp.errorOrNull()?.code == "http_404") {
                    return@runCatchingOutcomeFlat Outcome.Err("captions_unavailable", "No captions are available for this video.")
                }
                return@runCatchingOutcomeFlat Outcome.Err(resp.errorOrNull()?.code ?: "network", resp.errorOrNull()?.message ?: "Failed to reach the instance.")
            }
            val root = try { Json.parseObject((resp as com.vidx.core.util.Outcome.Ok).value.text()) } catch (ex: Exception) {
                return@runCatchingOutcomeFlat Outcome.Err("metadata_unavailable", "Invalid caption metadata.")
            }
            val data = root.arr("data") ?: return@runCatchingOutcomeFlat Outcome.Err("captions_unavailable", "No captions are available for this video.")
            val tracks = data.items.mapNotNull {
                val o = it as? JsonValue.Obj ?: return@mapNotNull null
                val langObj = o.obj("language")
                val lang = langObj?.str("id") ?: o.str("language") ?: return@mapNotNull null
                val langLabel = langObj?.str("label") ?: lang
                val urlPath = o.str("captionPath") ?: return@mapNotNull null
                TranscriptTrack(
                    id = urlPath,
                    langCode = lang,
                    name = langLabel,
                    autoGenerated = false,
                    fetchUrl = if (urlPath.startsWith("http")) urlPath else origin + urlPath,
                )
            }
            Outcome.Ok(tracks)
        }
    }
}
