package com.vidx.platform.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Download history — a small SQLite store, separate from the live queue.
 * History survives queue clears, app restarts and uninstall-free upgrades.
 */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "vidx_history.db", null, 1) {

    data class Entry(
        val id: String,
        val url: String,
        val title: String,
        val platformId: String,
        val thumbnailUrl: String?,
        val durationMillis: Long,
        val sizeBytes: Long,
        val outputUri: String?,
        val destinationId: String,
        val statusId: String,
        val createdAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                platform_id TEXT NOT NULL,
                thumbnail_url TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                output_uri TEXT,
                destination_id TEXT NOT NULL,
                status_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(e: Entry) {
        val cv = ContentValues().apply {
            put("id", e.id)
            put("url", e.url)
            put("title", e.title)
            put("platform_id", e.platformId)
            put("thumbnail_url", e.thumbnailUrl)
            put("duration_ms", e.durationMillis)
            put("size_bytes", e.sizeBytes)
            put("output_uri", e.outputUri)
            put("destination_id", e.destinationId)
            put("status_id", e.statusId)
            put("created_at", e.createdAt)
        }
        writableDatabase.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String) = writableDatabase.delete("history", "id=?", arrayOf(id))

    fun clear() = writableDatabase.delete("history", null, null)

    fun get(id: String): Entry? = query("id=?", arrayOf(id)).firstOrNull()

    enum class Filter { ALL, COMPLETED, FAILED, AUDIO, VIDEO }

    fun list(filter: Filter = Filter.ALL, limit: Int = 500): List<Entry> {
        val where = when (filter) {
            Filter.ALL -> null
            Filter.COMPLETED -> "status_id='completed'"
            Filter.FAILED -> "status_id='failed'"
            Filter.AUDIO -> "destination_id='audio'"
            Filter.VIDEO -> "destination_id='video'"
        }
        return query(where, null, limit)
    }

    private fun query(where: String?, args: Array<String>?, limit: Int = 500): List<Entry> {
        val out = mutableListOf<Entry>()
        val db = readableDatabase
        val cursor: Cursor = db.query(
            "history", null, where, args, null, null, "created_at DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                out.add(
                    Entry(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        url = it.getString(it.getColumnIndexOrThrow("url")),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        platformId = it.getString(it.getColumnIndexOrThrow("platform_id")),
                        thumbnailUrl = it.getString(it.getColumnIndexOrThrow("thumbnail_url")),
                        durationMillis = it.getLong(it.getColumnIndexOrThrow("duration_ms")),
                        sizeBytes = it.getLong(it.getColumnIndexOrThrow("size_bytes")),
                        outputUri = it.getString(it.getColumnIndexOrThrow("output_uri")),
                        destinationId = it.getString(it.getColumnIndexOrThrow("destination_id")),
                        statusId = it.getString(it.getColumnIndexOrThrow("status_id")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                    )
                )
            }
        }
        return out
    }
}
