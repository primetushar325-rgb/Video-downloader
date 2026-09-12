package com.vidx.app

import android.app.Activity
import android.os.Bundle

/**
 * Single-activity shell. The full VIDX UI (Home / Downloads / Transcript / History /
 * Settings + download service glue) is wired in as the application is completed;
 * this activity is the permanent host for all five screens.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Accept "share text" intents: remember the URL for the Home screen.
        val sharedText = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            PendingSharedText.url = sharedText.trim()
        }
    }

    /** Holds a URL shared into the app until the Home screen consumes it. */
    object PendingSharedText {
        @Volatile var url: String? = null
    }
}
