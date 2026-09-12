package com.vidx.platform.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vidx.app.BrandConfig
import com.vidx.core.download.Destination
import java.io.File
import java.io.FileOutputStream

/**
 * Storage layer.
 *
 *  - API 29+: MediaStore (no storage permission needed) into
 *      Movies/VIDX, Music/VIDX, Download/VIDX.
 *  - API 26–28: legacy direct files under /storage/emulated/0/VIDX/<Videos|Audio|Downloads>
 *      using WRITE_EXTERNAL_STORAGE (requested at runtime, declared maxSdkVersion=28).
 *  - Partial downloads live in the app-private tmp dir and are removed after
 *    finalize() — failed temp files are cleaned up on startup and after failures.
 */
class AndroidStorage(private val context: Context) {

    data class StoredFile(val uri: Uri?, val path: String?, val name: String, val size: Long)

    fun isLegacy(): Boolean = Build.VERSION.SDK_INT < 29

    fun createTemp(taskId: String, ext: String): File {
        val dir = File(context.cacheDir, "vidx-tmp")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "task-${taskId.take(32)}.$ext")
    }

    fun cleanupTemp() {
        val dir = File(context.cacheDir, "vidx-tmp")
        dir.listFiles()?.forEach { it.delete() }
    }

    fun cleanupTempFor(taskId: String) {
        val dir = File(context.cacheDir, "vidx-tmp")
        dir.listFiles()?.filter { it.name.contains(taskId.take(32)) }?.forEach { it.delete() }
    }

    fun freeBytes(): Long = File(context.cacheDir.absolutePath).usableSpace

    /** Total bytes used by the app (temp files + thumbnail cache). */
    fun usage(): Long {
        var total = 0L
        fun dirSize(dir: File): Long {
            var sum = 0L
            dir.listFiles()?.forEach { f -> sum += if (f.isDirectory) dirSize(f) else f.length() }
            return sum
        }
        total += dirSize(File(context.cacheDir, "vidx-tmp"))
        total += dirSize(File(context.cacheDir, "vidx-thumbs"))
        return total
    }

    /** Human description of where finished files live, for Settings. */
    fun baseDescription(): String =
        if (isLegacy()) {
            "${android.os.Environment.getExternalStorageDirectory().absolutePath}/${BrandConfig.storageRootName}/Videos (and Audio, Downloads)"
        } else {
            "your device's Movies, Music and Download folders (${BrandConfig.storageRootName})"
        }

    fun delete(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Moves a completed temp file into the user-visible destination.
     * Returns null on failure (the engine then reports a storage error).
     */
    fun finalize(temp: File, destination: Destination, title: String, extension: String, mime: String): StoredFile? {
        if (!temp.exists() || temp.length() == 0L) return null
        val safeTitle = com.vidx.core.util.FileNames.sanitize(title)
        val ext = extension.trimStart('.').lowercase()
        val finalName = "$safeTitle.$ext"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                finalizeMediaStore(destination, finalName, mime, temp)
            } else {
                finalizeLegacy(destination, finalName, temp)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun finalizeMediaStore(destination: Destination, name: String, mime: String, temp: File): StoredFile {
        val collection: Uri
        val relativePath: String
        when (destination) {
            Destination.VIDEO -> {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                relativePath = Environment.DIRECTORY_MOVIES + "/" + BrandConfig.storageRootName
            }
            Destination.AUDIO -> {
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                relativePath = Environment.DIRECTORY_MUSIC + "/" + BrandConfig.storageRootName
            }
            Destination.DOWNLOADS -> {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + BrandConfig.storageRootName
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            temp.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Could not open output stream")
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        temp.delete()
        return StoredFile(uri, null, name, temp.length().let { _ -> querySize(uri) })
    }

    private fun querySize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun finalizeLegacy(destination: Destination, name: String, temp: File): StoredFile {
        val base = Environment.getExternalStorageDirectory()
        val folder = when (destination) {
            Destination.VIDEO -> File(base, "${BrandConfig.storageRootName}/Videos")
            Destination.AUDIO -> File(base, "${BrandConfig.storageRootName}/Audio")
            Destination.DOWNLOADS -> File(base, "${BrandConfig.storageRootName}/Downloads")
        }
        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Could not create ${folder.path}")
        var target = File(folder, name)
        var n = 2
        while (target.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            target = File(folder, "$stem ($n)$ext")
            n++
        }
        FileOutputStream(target).use { out ->
            temp.inputStream().use { input -> input.copyTo(out) }
        }
        temp.delete()
        return StoredFile(null, target.absolutePath, target.name, target.length())
    }
}
