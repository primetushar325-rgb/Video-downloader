package com.vidx.platform.clipboard

import android.content.ClipboardManager
import android.content.Context
import com.vidx.core.clipboard.ClipboardClassifier
import com.vidx.core.model.Platform
import com.vidx.core.util.sha1

/**
 * Clipboard watcher — foreground-only, privacy-first.
 *
 *  - Listens to clipboard change events only while the app is in the foreground
 *    (the listener is registered in onResume and removed in onPause).
 *  - Classifies content with [ClipboardClassifier] (sensitive data is never
 *    surfaced or imported).
 *  - De-duplicates repeated copies of the same link ("Already in queue" /
 *    "Already downloaded" is decided by the UI layer against the engine).
 */
class ClipboardMonitor(private val context: Context) {

    interface Listener {
        /** A supported public video URL appeared on the clipboard. */
        fun onVideoLinkDetected(url: String, platform: Platform, repeat: Boolean)
    }

    private val cm = context.getSystemService(ClipboardManager::class.java)
    private var listener: Listener? = null
    private var lastKey: String? = null

    @Volatile
    private var active = false

    private val changeListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!active) return@OnPrimaryClipChangedListener
        dispatchRead()
    }

    fun start(l: Listener) {
        listener = l
        active = true
        try { cm.addPrimaryClipChangedListener(changeListener) } catch (e: Exception) {}
        dispatchRead()
    }

    fun stop() {
        active = false
        try { cm.removePrimaryClipChangedListener(changeListener) } catch (e: Exception) {}
    }

    private fun dispatchRead() {
        try {
            if (cm.primaryClip == null || cm.primaryClip!!.itemCount == 0) return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            when (val v = ClipboardClassifier.classify(text)) {
                is ClipboardClassifier.Verdict.VideoLink -> {
                    val key = sha1(v.url)
                    val repeat = key == lastKey
                    lastKey = key
                    listener?.onVideoLinkDetected(v.url, v.platform, repeat)
                }
                else -> {}
            }
        } catch (e: Exception) {
            // Clipboard read failures (rare, e.g. empty item) are ignored.
        }
    }
}
