package com.vidx.core.transcript

import com.vidx.core.util.Outcome

/**
 * Transcript translation port.
 *
 * VIDX intentionally ships with [UnavailableTranslator]: it bundles no
 * translation service and no API keys (secrets never belong in the client).
 * The UI therefore shows the honest "translation unavailable" state with a
 * clear explanation, and this interface documents exactly where a legitimate,
 * configured translation backend would plug in later.
 */
interface Translator {
    fun translate(text: String, fromLang: String, toLang: String): Outcome<String>
}

object UnavailableTranslator : Translator {
    override fun translate(text: String, fromLang: String, toLang: String): Outcome<String> =
        Outcome.Err(
            "translation_unavailable",
            "Translation is not available: VIDX bundles no translation service and never uploads your transcript to third parties. " +
                "The original transcript is shown unchanged."
        )
}
