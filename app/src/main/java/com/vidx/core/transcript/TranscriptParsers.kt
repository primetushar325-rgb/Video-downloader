package com.vidx.core.transcript

import com.vidx.core.json.Json
import com.vidx.core.json.JsonValue
import com.vidx.core.util.Outcome

/**
 * Caption payload parsers: YouTube json3 (primary), WebVTT and SRT (fallback /
 * other platforms). All parsing is defensive: malformed lines are skipped,
 * never crashes.
 */
object TranscriptParsers {

    fun parse(body: String, formatHint: String? = null): Outcome<List<TranscriptSegment>> {
        val hint = formatHint?.lowercase()
        val trimmed = body.trim()
        val result = when {
            hint == "json" || hint == "json3" || trimmed.startsWith("{") ->
                kotlin.runCatching { parseJson3(trimmed) }.getOrNull()
                    ?: kotlin.runCatching { parseVtt(trimmed) }.getOrNull()
                    ?: return Outcome.Err("parse_error", "Could not read this caption file.")
            hint == "srt" || (trimmed.contains("-->") && !trimmed.startsWith("WEBVTT")) ->
                kotlin.runCatching { parseSrt(trimmed) }.getOrNull()
                    ?: return Outcome.Err("parse_error", "Could not read this caption file.")
            else ->
                kotlin.runCatching { parseVtt(trimmed) }.getOrNull()
                    ?: kotlin.runCatching { parseSrt(trimmed) }.getOrNull()
                    ?: return Outcome.Err("parse_error", "Could not read this caption file.")
        }
        return if (result.isEmpty()) {
            Outcome.Err("captions_unavailable", "The caption file contained no readable cues.")
        } else {
            Outcome.Ok(result)
        }
    }

    /** YouTube json3 timedtext: {"events":[{"tStartMs":..,"dDurationMs":..,"segs":[{"utf8":".."}]}]} */
    fun parseJson3(body: String): List<TranscriptSegment> {
        val root = Json.parseObject(body)
        val events = root.arr("events") ?: return emptyList()
        val out = mutableListOf<TranscriptSegment>()
        var lastEnd = 0L
        for (e in events.items) {
            val o = e as? JsonValue.Obj ?: continue
            val start = o.long("tStartMs") ?: lastEnd
            val dur = o.long("dDurationMs")
            val text = o.arr("segs")?.items?.mapNotNull {
                (it as? JsonValue.Obj)?.str("utf8")?.let { s -> decodeEntities(s) }
            }?.joinToString("") ?: continue
            val cleaned = cleanText(text)
            if (cleaned.isEmpty()) { lastEnd = start + (dur ?: 0); continue }
            out.add(TranscriptSegment(start, start + (dur ?: (cleaned.length * 60L + 300L)), cleaned))
            lastEnd = start + (dur ?: 0)
        }
        return out
    }

    /** WebVTT: cue timestamps `00:00.000 --> 00:04.000` followed by text lines. */
    fun parseVtt(body: String): List<TranscriptSegment> {
        val lines = body.lineSequence().toList()
        val out = mutableListOf<TranscriptSegment>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val m = Regex("(\\d{1,2}:)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,2}:)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})").find(line)
            if (m != null) {
                val start = tsToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
                val end = tsToMs(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
                val textLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && lines[j].trim().isNotEmpty()) {
                    textLines.add(stripVttTags(lines[j]))
                    j++
                }
                val text = cleanText(decodeEntities(textLines.joinToString(" ")))
                if (text.isNotEmpty()) out.add(TranscriptSegment(start, end, text))
                i = j
            }
            i++
        }
        return out
    }

    /** SRT: `00:00:00,000 --> 00:00:04,000` with an index line above. */
    fun parseSrt(body: String): List<TranscriptSegment> {
        val lines = body.lineSequence().toList()
        val out = mutableListOf<TranscriptSegment>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val m = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})").find(line)
            if (m != null) {
                val start = srtTsToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
                val end = srtTsToMs(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
                val textLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && lines[j].trim().isNotEmpty()) {
                    textLines.add(lines[j].trim())
                    j++
                }
                val text = cleanText(decodeEntities(textLines.joinToString(" ")))
                if (text.isNotEmpty()) out.add(TranscriptSegment(start, end, text))
                i = j
            }
            i++
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun tsToMs(h: String, m: String, s: String, ms: String): Long {
        val hours = h.removeSuffix(":").toLongOrNull() ?: 0
        return (hours * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000 +
            (ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
    }

    private fun srtTsToMs(h: String, m: String, s: String, ms: String): Long {
        return ((h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000 +
            (ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
    }

    private fun stripVttTags(line: String): String =
        line.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .trim()

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    private fun cleanText(s: String): String =
        s.replace(Regex("\\s+"), " ")
            .replace(Regex("^\\[.*?\\]\\s*"), "") // [Music], [Applause] markers stay visible but leading noise removed
            .trim()
}
