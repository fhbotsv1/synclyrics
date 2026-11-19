package com.littlebird.synclyrics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/**
 * Public, user-managed LRC library stored in Downloads/SyncLyrics.
 * On Android 10+ it uses MediaStore; older Android versions use a user-granted SAF folder.
 */
class PublicLyricsLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("public_lyrics_library", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    fun hasFolderAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || prefs.getString("tree_uri", null) != null

    fun saveFolderAccess(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString("tree_uri", uri.toString()).apply()
    }

    fun all(): List<SavedLyrics> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val result = mutableListOf<SavedLyrics>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH
        )
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val path = cursor.getString(pathCol).orEmpty()
                if (!name.endsWith(".lrc", true) || !path.equals(Environment.DIRECTORY_DOWNLOADS + "/SyncLyrics/", true)) continue
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString())
                runCatching {
                    resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { text -> result += toSaved(uri.toString(), name, text) }
            }
        }
        result.sortedBy { it.title.lowercase(Locale.ROOT) }
    } else {
        val tree = treeDocument() ?: return emptyList()
        tree.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".lrc", true) }
            .mapNotNull { file ->
                runCatching { resolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()?.let { toSaved(file.uri.toString(), file.name.orEmpty(), it) }
            }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun find(title: String, artist: String): SavedLyrics? {
        val entries = all()
        val t = normalize(title)
        val a = normalize(artist)
        if (t.isBlank()) return null
        entries.firstOrNull { normalize(it.title) == t && normalize(it.artist) == a }?.let { return it }
        return entries.filter { normalize(it.title) == t }.singleOrNull()
    }

    fun write(title: String, artist: String, album: String, lrc: String) {
        require(title.isNotBlank() && lrc.isNotBlank()) { "Song title and lyrics are required." }
        val name = makeLrcFileName(title, artist)
        val text = withMetadata(lrc, title, artist, album)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/SyncLyrics/"
            val existing = all().firstOrNull { it.title.equals(title, true) && it.artist.equals(artist, true) }
            if (existing != null) resolver.delete(Uri.parse(existing.lrcFileUri()), null, null)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-lrc")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the LRC file in Downloads/SyncLyrics.")
            try {
                resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                    ?: throw IllegalStateException("Could not write the LRC file.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val folder = treeDocument() ?: throw IllegalStateException("Choose the Downloads/SyncLyrics folder first using Scan & Sync.")
            folder.findFile(name)?.delete()
            val file = folder.createFile("application/x-lrc", name)
                ?: throw IllegalStateException("Could not create the LRC file in the selected folder.")
            resolver.openOutputStream(file.uri)?.bufferedWriter()?.use { it.write(text) }
                ?: throw IllegalStateException("Could not write the LRC file.")
        }
    }

    fun delete(item: SavedLyrics) {
        val uri = Uri.parse(item.lrcFileUri())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.delete(uri, null, null)
        else DocumentFile.fromSingleUri(context, uri)?.delete()
    }

    fun migrateLegacy(entries: List<SavedLyrics>): Int {
        val current = all()
        var migrated = 0
        entries.forEach { old ->
            val exists = current.any {
                normalize(it.title) == normalize(old.title) && normalize(it.artist) == normalize(old.artist)
            }
            if (!exists) {
                write(old.title, old.artist, old.album, old.lrc)
                migrated++
            }
        }
        return migrated
    }

    private fun treeDocument(): DocumentFile? {
        val uri = prefs.getString("tree_uri", null)?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    private fun toSaved(uri: String, fileName: String, text: String): SavedLyrics {
        val titleTag = Regex("(?im)^\\[ti:(.*?)]\\s*$").find(text)?.groupValues?.get(1)?.trim()
        val artistTag = Regex("(?im)^\\[ar:(.*?)]\\s*$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val albumTag = Regex("(?im)^\\[al:(.*?)]\\s*$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val base = fileName.removeSuffix(".lrc")
        val split = base.split(" - ", limit = 2)
        val title = titleTag?.takeIf { it.isNotBlank() } ?: split.first()
        val artist = artistTag.ifBlank { split.getOrNull(1).orEmpty() }
        return SavedLyrics(uri.hashCode().toLong(), title, artist, albumTag, text, 0L, uri)
    }

    private fun withMetadata(lrc: String, title: String, artist: String, album: String): String {
        val lines = lrc.lineSequence().toList()
        val prefix = buildList {
            if (lines.none { it.trim().startsWith("[ti:", true) }) add("[ti:$title]")
            if (artist.isNotBlank() && lines.none { it.trim().startsWith("[ar:", true) }) add("[ar:$artist]")
            if (album.isNotBlank() && lines.none { it.trim().startsWith("[al:", true) }) add("[al:$album]")
        }
        return (prefix + lines).joinToString("\n")
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun SavedLyrics.lrcFileUri(): String = fileUri ?: ""
}
