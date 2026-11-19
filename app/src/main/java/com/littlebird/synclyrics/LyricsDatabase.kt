package com.littlebird.synclyrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class SavedLyrics(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val lrc: String,
    val updatedAt: Long
)

class LyricsDatabase(context: Context) : SQLiteOpenHelper(context, "synclyrics.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE lyrics (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                normalized_title TEXT NOT NULL,
                normalized_artist TEXT NOT NULL,
                lrc TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(normalized_title, normalized_artist)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX lyrics_title_idx ON lyrics(normalized_title)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(title: String, artist: String, album: String, lrc: String): Long {
        val normalizedTitle = normalize(title)
        val normalizedArtist = normalize(artist)
        require(normalizedTitle.isNotBlank() && lrc.isNotBlank()) { "Song title and lyrics are required." }
        val values = ContentValues().apply {
            put("title", title.trim())
            put("artist", artist.trim())
            put("album", album.trim())
            put("normalized_title", normalizedTitle)
            put("normalized_artist", normalizedArtist)
            put("lrc", lrc)
            put("updated_at", System.currentTimeMillis())
        }
        val existing = writableDatabase.query(
            "lyrics", arrayOf("_id"), "normalized_title=? AND normalized_artist=?",
            arrayOf(normalizedTitle, normalizedArtist), null, null, null
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        return if (existing == null) writableDatabase.insertOrThrow("lyrics", null, values)
        else {
            writableDatabase.update("lyrics", values, "_id=?", arrayOf(existing.toString()))
            existing
        }
    }

    fun find(title: String, artist: String): SavedLyrics? {
        val normalizedTitle = normalize(title)
        val normalizedArtist = normalize(artist)
        if (normalizedTitle.isBlank()) return null
        val exact = readOne(
            "normalized_title=? AND normalized_artist=?",
            arrayOf(normalizedTitle, normalizedArtist)
        )
        if (exact != null) return exact
        // Some players omit artist metadata. Only use a title-only fallback when it is unambiguous.
        val matches = readableDatabase.query(
            "lyrics", null, "normalized_title=?", arrayOf(normalizedTitle),
            null, null, "updated_at DESC", "2"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorToSaved(cursor)) } }
        return matches.singleOrNull()
    }

    fun all(): List<SavedLyrics> = readableDatabase.query(
        "lyrics", null, null, null, null, null, "title COLLATE NOCASE ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorToSaved(cursor)) } }

    fun delete(id: Long): Int = writableDatabase.delete("lyrics", "_id=?", arrayOf(id.toString()))

    private fun readOne(where: String, args: Array<String>): SavedLyrics? =
        readableDatabase.query("lyrics", null, where, args, null, null, "updated_at DESC", "1")
            .use { if (it.moveToFirst()) cursorToSaved(it) else null }

    private fun cursorToSaved(c: android.database.Cursor) = SavedLyrics(
        id = c.getLong(c.getColumnIndexOrThrow("_id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        artist = c.getString(c.getColumnIndexOrThrow("artist")),
        album = c.getString(c.getColumnIndexOrThrow("album")),
        lrc = c.getString(c.getColumnIndexOrThrow("lrc")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
