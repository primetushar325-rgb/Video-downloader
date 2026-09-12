package com.vidx.core.settings

/**
 * Settings model — pure data + defaults. The Android layer persists this in
 * SharedPreferences. Everything here is user-facing configuration for the
 * V2 feature set (queue / network / clipboard / notifications / appearance /
 * transcript / privacy).
 */
enum class AccentTheme(val id: String, val label: String) {
    RED("red", "Red"),
    BLUE("blue", "Blue"),
    PURPLE("purple", "Purple");

    companion object {
        fun fromId(id: String?): AccentTheme = entries.firstOrNull { it.id == id } ?: RED
    }
}

enum class DayNightMode(val id: String, val label: String) {
    DARK("dark", "Dark"),
    SYSTEM("system", "System");

    companion object {
        fun fromId(id: String?): DayNightMode = entries.firstOrNull { it.id == id } ?: DARK
    }
}

data class AppSettings(
    // Download
    val defaultQuality: String = "best",          // "best" | "1080p" | "720p" | …
    val defaultFormat: String = "mp4",            // preferred container where offered
    val autoStartQueue: Boolean = true,           // "auto download" — start queue after adding
    val maxConcurrent: Int = 1,                   // sequential by default (stability)
    val autoRetryCount: Int = 3,
    val retryFailedAfterAll: Boolean = true,
    // Network
    val wifiOnly: Boolean = false,
    val warnOnMobileData: Boolean = true,
    val allowCellular: Boolean = true,
    // Clipboard
    val clipboardDetect: Boolean = true,
    val clipboardAutoAdd: Boolean = false,        // false = always ask (privacy-safe default)
    val clipboardAskBeforeAdd: Boolean = true,
    // Notifications
    val notifyProgress: Boolean = true,
    val notifyCompleted: Boolean = true,
    val notifyFailed: Boolean = true,
    // Appearance
    val accentTheme: AccentTheme = AccentTheme.RED,
    val dayNight: DayNightMode = DayNightMode.DARK,
    // Transcript
    val transcriptPreferredLanguage: String = "", // "" = auto
    val transcriptAutoFetch: Boolean = true,
    // Privacy
    val shareDiagnostics: Boolean = false,
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
