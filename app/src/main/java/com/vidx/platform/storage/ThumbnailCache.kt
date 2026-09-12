package com.vidx.platform.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.vidx.core.net.HttpClient
import com.vidx.core.util.sha1
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Two-level thumbnail cache: memory (LruCache) + disk (cacheDir/vidx-thumbs),
 * fetched on a single-thread executor so image loading can never flood the
 * network or starve the download workers.
 */
class ThumbnailCache(context: Context) {

    private val dir = File(context.cacheDir, "vidx-thumbs").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(32) // entries
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vidx-thumbs").apply { isDaemon = true }
    }
    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun get(url: String?, listener: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) { listener(null); return }
        val key = sha1(url)
        memory.get(key)?.let { listener(it); return }
        executor.execute {
            try {
                val file = File(dir, key)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                        memory.put(key, bmp)
                        listener(bmp)
                        return@execute
                    }
                }
                if (!inflight.add(key)) return@execute
                try {
                    val res = HttpClient.get(url, maxBytes = 6L * 1024 * 1024)
                    if (res is com.vidx.core.util.Outcome.Ok) {
                        val body = res.value.body
                        val bmp = BitmapFactory.decodeByteArray(body, 0, body.size)
                        if (bmp != null) {
                            file.writeBytes(body)
                            memory.put(key, bmp)
                            listener(bmp)
                            return@execute
                        }
                    }
                } finally {
                    inflight.remove(key)
                }
                listener(null)
            } catch (e: Exception) {
                listener(null)
            }
        }
    }

    fun clearDisk() {
        dir.listFiles()?.forEach { it.delete() }
        memory.evictAll()
    }
}
