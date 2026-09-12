package com.vidx.core.platforms

import com.vidx.core.model.Platform
import com.vidx.core.model.VideoMetadata
import com.vidx.core.net.HttpClient
import com.vidx.core.url.PlatformDetector
import com.vidx.core.url.UrlNormalizer
import com.vidx.core.util.Outcome

/**
 * Registry of platform adapters. The single wiring point: to support a new
 * platform, implement [PlatformAdapter] and add it here.
 */
object PlatformRegistry {

    val adapters: List<PlatformAdapter> = listOf(
        YouTubeAdapter,
        TikTokAdapter,
        VimeoAdapter,
        PeertubeAdapter,
        ArchiveOrgAdapter,
        DirectAdapter,
        RestrictedAdapter(Platform.INSTAGRAM),
        RestrictedAdapter(Platform.FACEBOOK),
        RestrictedAdapter(Platform.REDNOTE),
        RestrictedAdapter(Platform.KUAISHOU),
        RestrictedAdapter(Platform.TWITTER_X),
        RestrictedAdapter(Platform.PINTEREST),
    )

    private val byPlatform: Map<Platform, PlatformAdapter> = adapters.associateBy { it.platform }

    fun forPlatform(platform: Platform): PlatformAdapter? = byPlatform[platform]

    /** Platforms the app recognises (detection works for all of them). */
    fun supportedPlatforms(): List<Platform> = adapters.map { it.platform }

    fun forUrl(url: String): PlatformAdapter? = forPlatform(PlatformDetector.detect(url))

    /** Analyzes a URL through the right adapter, with unified error mapping. */
    fun analyze(url: String): Outcome<VideoMetadata> {
        val validator = com.vidx.core.url.UrlValidator.validate(url)
        if (validator != null) {
            return when (validator) {
                is com.vidx.core.url.UrlValidator.ValidationError.UnsupportedPlatform ->
                    Outcome.Err("unsupported", "This website is not a supported video platform.")
                else -> Outcome.Err("invalid_url", validator.message)
            }
        }
        val adapter = forUrl(url) ?: return Outcome.Err("unsupported", "This website is not a supported video platform.")
        return adapter.resolveMetadata(url)
    }

    /**
     * Normalizes + analyzes in one step; used by the queue when a task is added
     * before analysis has happened.
     */
    fun normalized(url: String) = UrlNormalizer.normalize(url)
}
