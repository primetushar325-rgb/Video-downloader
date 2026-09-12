package com.vidx.core.platforms

import com.vidx.core.json.Json
import com.vidx.core.json.JsonValue
import com.vidx.core.model.Platform
import com.vidx.core.model.VideoFormat
import com.vidx.core.model.VideoMetadata
import com.vidx.core.url.ParsedUrl
import com.vidx.core.util.Outcome
import com.vidx.core.util.runCatchingOutcomeFlat

/**
 * Internet Archive adapter — uses the fully public metadata API and the public
 * file area (archive.org is explicitly an open, downloadable media library).
 *
 *   GET https://archive.org/metadata/{identifier}
 *   files → https://archive.org/download/{identifier}/{fileName}
 */
object ArchiveOrgAdapter : PlatformAdapter {

    override val platform = Platform.ARCHIVE_ORG

    private val videoFormats = setOf("mp4", "m4v", "webm", "ogv", "avi", "mkv", "mov")
    private val audioFormats = setOf("mp3", "ogg", "opus", "flac", "m4a", "wav")
    private val identityRegex = Regex("/details/([A-Za-z0-9_.-]+)")

    private fun identifier(url: String): String? {
        val p = ParsedUrl.parse(url) ?: return null
        return identityRegex.find(p.path)?.groupValues?.get(1)
    }

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val id = identifier(url) ?: return Outcome.Err("invalid_url", "Not a valid archive.org item URL.")
        return runCatchingOutcomeFlat("network") {
            val resp = PlatformAdapter.http.get("https://archive.org/metadata/$id", maxBytes = 64L * 1024 * 1024)
            if (!resp.isOk()) {
                val e = resp.errorOrNull()
                return@runCatchingOutcomeFlat when (e?.code) {
                    "http_404" -> Outcome.Err("not_found", "This archive.org item was not found.")
                    else -> Outcome.Err(e?.code ?: "network", e?.message ?: "Failed to reach archive.org.")
                }
            }
            val root = try { Json.parseObject((resp as com.vidx.core.util.Outcome.Ok).value.text()) } catch (ex: Exception) {
                return@runCatchingOutcomeFlat Outcome.Err("metadata_unavailable", "archive.org returned invalid metadata.")
            }
            val meta = root.obj("metadata") ?: JsonValue.Obj(emptyMap())
            val title = meta.str("title")
            val author = meta.str("creator") ?: meta.arr("creator")?.str(0)
            val durationStr = meta.str("runtime") ?: meta.str("duration")
            val durationMillis = durationStr?.let { parseDuration(it) }

            val video = mutableListOf<VideoFormat>()
            val audio = mutableListOf<VideoFormat>()
            root.arr("files")?.items?.forEach {
                val f = it as? JsonValue.Obj ?: return@forEach
                val name = f.str("name") ?: return@forEach
                val format = f.str("format")?.lowercase() ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                val fileUrl = "https://archive.org/download/$id/" + name.split('/').joinToString("/") { seg ->
                    java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                }
                val size = f.long("size")
                val fmt = VideoFormat(
                    id = name,
                    qualityLabel = format.ifBlank { ext.uppercase() },
                    container = ext,
                    mimeType = null,
                    url = fileUrl,
                    fileSize = size,
                    audioOnly = false,
                )
                when {
                    ext in videoFormats || format in videoFormats -> video.add(fmt)
                    ext in audioFormats || format in audioFormats -> audio.add(fmt.copy(audioOnly = true))
                }
            }

            Outcome.Ok(
                VideoMetadata(
                    url = url, platform = Platform.ARCHIVE_ORG,
                    title = title ?: id,
                    author = author,
                    thumbnailUrl = null,
                    durationMillis = durationMillis,
                    videoFormats = video,
                    audioFormats = audio,
                    downloadNote = if (video.isEmpty() && audio.isEmpty()) "This item has no downloadable media files." else null,
                )
            )
        }
    }

    private fun parseDuration(s: String): Long? {
        // "1:23:45", "01:23:45.6", "45", "45.5 sec", "3 min 4 sec"…
        val mm = Regex("(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*min)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*sec)?", RegexOption.IGNORE_CASE).find(s.trim())
        if (mm != null && mm.groupValues.drop(1).any { it.isNotEmpty() }) {
            val h = mm.groupValues[1].toLongOrNull() ?: 0
            val m = mm.groupValues[2].toLongOrNull() ?: 0
            val sec = mm.groupValues[3].toDoubleOrNull() ?: 0.0
            if (h > 0 || m > 0 || sec > 0) return ((h * 3600 + m * 60) * 1000 + (sec * 1000).toLong())
        }
        val clock = Regex("(?:(\\d+):)?(\\d+):(\\d+)(?:\\.\\d+)?").find(s.trim())
        if (clock != null) {
            val h = clock.groupValues[1].toLongOrNull() ?: 0
            val m = clock.groupValues[2].toLongOrNull() ?: 0
            val sec = clock.groupValues[3].toLongOrNull() ?: 0
            return (h * 3600 + m * 60 + sec) * 1000
        }
        val plain = s.trim().toLongOrNull()
        return plain?.times(1000)
    }
}
