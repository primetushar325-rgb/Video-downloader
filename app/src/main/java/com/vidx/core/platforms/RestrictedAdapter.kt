package com.vidx.core.platforms

import com.vidx.core.model.Platform
import com.vidx.core.model.VideoMetadata
import com.vidx.core.util.Outcome

/**
 * Adapter for platforms VIDX detects but cannot legally/technically support
 * beyond detection (no public API for metadata or downloads).
 *
 * This is the honest path required by the project rules: analyze returns a
 * categorized, user-friendly explanation instead of pretending to work —
 * never a fabricated result, never a bypass attempt.
 */
class RestrictedAdapter(override val platform: Platform) : PlatformAdapter {

    override fun resolveMetadata(url: String): Outcome<VideoMetadata> {
        val note = when (platform) {
            Platform.INSTAGRAM ->
                "Instagram doesn't provide a public API for video downloads, and scraping it would violate its terms. Open the post in the Instagram app to save it if the author allows it."
            Platform.FACEBOOK ->
                "Facebook requires authentication for video access and offers no public download API. Use Facebook's own save options where available."
            Platform.REDNOTE ->
                "RedNote (Xiaohongshu) provides no public metadata or download API. Use the app's own save feature for content you may save."
            Platform.KUAISHOU ->
                "Kuaishou provides no public download API. Use the app's own save feature where the author allows it."
            Platform.TWITTER_X ->
                "X (Twitter) provides no public video download API. Use the X app's save/bookmark features."
            Platform.PINTEREST ->
                "Pinterest pins often link to the original source. No public download API exists; follow the pin to its source and download from there."
            else -> "This platform is detected but not supported for downloads."
        }
        return Outcome.Ok(
            VideoMetadata(
                url = url,
                platform = platform,
                title = null, author = null, thumbnailUrl = null, durationMillis = null,
                downloadNote = note,
            )
        )
    }
}
