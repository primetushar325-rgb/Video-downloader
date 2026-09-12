package com.vidx.app

/**
 * VIDX — single source of truth for app branding.
 *
 * Renaming the product (or changing the tagline / palette) is done HERE, in one file.
 * The launcher label string in res/values/strings.xml mirrors [appName]; a release
 * checklist in docs/RELEASING.md covers keeping them in sync.
 *
 * No copyrighted or third-party logos are used anywhere in VIDX's own branding.
 * Platform names shown in the UI (YouTube, TikTok, …) are text-only references
 * rendered with neutral colours, never their logos.
 */
object BrandConfig {

    const val appName = "VIDX"
    const val tagline = "Video Downloader & Media Utility"
    const val versionName = "1.0.0"
    const val versionCode = 1
    const val appDescription = "A production-ready Android video downloader & media utility: smart queue, background downloads, transcripts and history."

    /** Short namespace used for on-device storage folders (VIDX/Videos, …). */
    const val storageRootName = "VIDX"

    const val homePage = "https://github.com/primetushar325-rgb/Video-downloader"

    // ------------------------------------------------------------------ palette
    // Base surface palette (dark, premium).
    const val colorBackground = 0xFF0A0B10.toInt()
    const val colorSurface = 0xFF13141D.toInt()
    const val colorSurfaceHigh = 0xFF1A1C28.toInt()
    const val colorGlass = 0x141A1C28        // translucent glass surfaces
    const val colorTextPrimary = 0xFFF2F3F8.toInt()
    const val colorTextSecondary = 0xFF9DA2B3.toInt()
    const val colorTextTertiary = 0xFF6B7085.toInt()
    const val colorOutline = 0x1FFFFFFF       // subtle glass borders
    const val colorDivider = 0x14FFFFFF

    const val colorRed = 0xFFFF2D55.toInt()
    const val colorBlue = 0xFF2E7CFF.toInt()
    const val colorPurple = 0xFF8E4DFF.toInt()
    const val colorGreen = 0xFF22C55E.toInt()
    const val colorAmber = 0xFFF59E0B.toInt()
    const val colorDanger = 0xFFEF4444.toInt()

    /** Gradient used for primary CTAs: red → red-purple. */
    val primaryGradient = intArrayOf(0xFFFF2D55.toInt(), 0xFFC026D3.toInt())
    /** Gradient used for secondary CTAs: blue → purple. */
    val secondaryGradient = intArrayOf(0xFF2E7CFF.toInt(), 0xFF8E4DFF.toInt())
}
