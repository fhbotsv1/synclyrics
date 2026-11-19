package com.littlebird.synclyrics

import android.content.Context
import android.net.Uri
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/**
 * Public, user-managed LRC library. The user grants SyncLyrics access to
 * Downloads/SyncLyrics once through Android's folder picker (SAF).
 */
class PublicLyricsLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("public_lyrics_library", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    fun hasFolderAccess(): Boolean = prefs.getString("tree_uri", null) != null

    fun saveFolderAccess(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString("tree_uri", uri.toString()).apply()
    }

    fun all(): List<SavedLyrics> {
        val folder = treeDocument() ?: return emptyList()
        return folder.listFiles()
            .filter { it.isFile && it.name.orEmpty().endsWith(".lrc", true) }
            .mapNotNull { file ->
                runCatching { resolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()?.let { toSaved(file.uri, file.name.orEmpty(), it) }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
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
        val folder = treeDocument()
            ?: throw IllegalStateException("Choose Downloads/SyncLyrics first using Scan & Sync.")
        val name = makeLrcFileName(title, artist)
        folder.findFile(name)?.delete()
        val file = folder.createFile("application/x-lrc", name)
            ?: throw IllegalStateException("Could not create the LRC file in Downloads/SyncLyrics.")
        val text = withMetadata(lrc, title, artist, album)
        resolver.openOutputStream(file.uri)?.bufferedWriter()?.use { it.write(text) }
            ?: throw IllegalStateException("Could not write the LRC file.")
    }

    fun delete(item: SavedLyrics) {
        val folder = treeDocument() ?: return
        folder.listFiles().firstOrNull { it.uri == item.fileUri?.let(Uri::parse) }?.delete()
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

    private fun toSaved(uri: Uri, fileName: String, text: String): SavedLyrics {
        val titleTag = Regex("(?im)^\\[ti:(.*?)]\\s*$").find(text)?.groupValues?.get(1)?.trim()
        val artistTag = Regex("(?im)^\\[ar:(.*?)]\\s*$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val albumTag = Regex("(?im)^\\[al:(.*?)]\\s*$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val base = fileName.removeSuffix(".lrc")
        val split = base.split(" - ", limit = 2)
        val title = titleTag?.takeIf { it.isNotBlank() } ?: split.first()
        val artist = artistTag.ifBlank { split.getOrNull(1).orEmpty() }
        return SavedLyrics(uri.hashCode().toLong(), title, artist, albumTag, text, 0L, uri.toString())
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

    private fun makeLrcFileName(title: String, artist: String): String {
        val raw = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Lyrics" }
        val safe = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)
        return "$safe.lrc"
    }
}
