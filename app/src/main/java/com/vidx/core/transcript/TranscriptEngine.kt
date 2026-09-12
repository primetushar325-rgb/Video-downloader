package com.vidx.core.transcript

import com.vidx.core.model.Platform
import com.vidx.core.net.HttpClient
import com.vidx.core.platforms.PlatformRegistry
import com.vidx.core.url.UrlValidator
import com.vidx.core.util.Outcome
import com.vidx.core.util.runCatchingOutcome

/**
 * Transcript engine — independent from the download engine by design.
 *
 * Process: URL → validate → platform adapter → caption track list → language
 * selection → fetch caption payload → parse into timestamped segments.
 *
 * Accuracy policy: captions come from the source itself. VIDX never claims
 * 100% accuracy and never invents words.
 */
object TranscriptEngine {

    fun listTracks(url: String): Outcome<List<TranscriptTrack>> {
        val validation = UrlValidator.validate(url)
        if (validation != null) {
            return Outcome.Err("invalid_url", validation.message)
        }
        val adapter = PlatformRegistry.forUrl(url) ?: return Outcome.Err("unsupported", "This platform does not support transcripts.")
        if (adapter.platform.transcript == Platform.Capability.NOT_AVAILABLE) {
            return Outcome.Err("unsupported", "Transcripts are not available for ${adapter.platform.displayName}.")
        }
        return adapter.listTranscriptTracks(url, null)
    }

    fun fetchTrack(track: TranscriptTrack): Outcome<TranscriptResult> {
        val resp = HttpClient.get(
            track.fetchUrl,
            headers = mapOf("Accept" to "application/json, text/vtt, application/x-subrip, text/plain"),
            maxBytes = 16L * 1024 * 1024,
        )
        if (!resp.isOk()) {
            val e = resp.errorOrNull()
            return Outcome.Err(e?.code ?: "network", e?.message ?: "Could not fetch the transcript.")
        }
        val body = (resp as com.vidx.core.util.Outcome.Ok).value.text()
        val parsed = TranscriptParsers.parse(body, formatHint(track))
        return when (parsed) {
            is Outcome.Ok -> {
                if (parsed.value.isEmpty()) {
                    Outcome.Err("captions_unavailable", "The caption file was empty.")
                } else {
                    Outcome.Ok(
                        TranscriptResult(
                            track = track,
                            segments = parsed.value,
                            source = "captions",
                            confidenceNote = "Transcript accuracy depends on audio quality, language, accents, background noise and available captions.",
                        )
                    )
                }
            }
            is Outcome.Err -> parsed
        }
    }

    private fun formatHint(track: TranscriptTrack): String? = when {
        track.fetchUrl.contains("fmt=json3") -> "json3"
        track.fetchUrl.contains(".vtt") || track.fetchUrl.contains("fmt=vtt") -> "vtt"
        track.fetchUrl.contains(".srt") -> "srt"
        else -> null
    }

    /**
     * Speech-to-text via a local/authorized transcription system. VIDX ships no
     * bundled STT model and never uploads audio anywhere, so this reports the
     * honest unavailable state unless a device STT provider is configured.
     */
    fun transcribe(url: String): Outcome<TranscriptResult> =
        Outcome.Err(
            "speech_recognition_unavailable",
            "Speech recognition is not available: VIDX ships no on-device speech model and never uploads audio to third parties. Captions are used when the platform provides them."
        )
}
