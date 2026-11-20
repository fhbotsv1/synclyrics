package com.littlebird.synclyrics

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

private data class LocalMediaItem(
    val uri: Uri, val title: String, val artist: String,
    val duration: Long, val folder: String, val isVideo: Boolean
)

private fun requiredMediaPermissions(tab: String): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(
        if (tab == "Videos") Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO
    ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
fun LocalMediaPlayerScreen(
    library: PublicLyricsLibrary,
    permissionRefresh: Int,
    syncOffsetMs: Int,
    lyricColor: Color,
    onBack: () -> Unit,
    onRequestMediaPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Music") }
    var selectedFolder by remember { mutableStateOf("All folders") }
    var items by remember { mutableStateOf(emptyList<LocalMediaItem>()) }
    var selected by remember { mutableStateOf<LocalMediaItem?>(null) }
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf(emptyList<LyricLine>()) }
    var lyricsMessage by remember { mutableStateOf("Choose a song to find its lyrics") }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsSaved by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()
    val exo = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(exo) { onDispose { exo.release() } }
    BackHandler {
        if (selected != null) { exo.stop(); selected = null } else onBack()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Unable to read this LRC file.")
                }
                val parsed = parseLocalLrc(text)
                require(parsed.isNotEmpty()) { "No timestamped lyric lines found in this file." }
                lyrics = parsed
                val track = selected
                if (track != null) {
                    runCatching { withContext(Dispatchers.IO) { library.write(track.title, track.artist, "", text) } }
                        .onSuccess { lyricsSaved = true; lyricsMessage = "Imported and saved to offline library" }
                        .onFailure { lyricsSaved = false; lyricsMessage = "Imported for this session; choose Downloads/SyncLyrics using Scan & Sync to save it" }
                } else lyricsMessage = "LRC imported"
                importError = null
            }.onFailure { importError = it.message ?: "Could not import LRC file." }
        }
    }

    fun scan() {
        val permitted = requiredMediaPermissions(tab).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permitted) { scanning = false; return }
        scope.launch {
            scanning = true
            items = withContext(Dispatchers.IO) {
                val result = mutableListOf<LocalMediaItem>()
                val video = tab == "Videos"
                val folderColumn = if (android.os.Build.VERSION.SDK_INT >= 29)
                    MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
                val projection = if (video) arrayOf(
                    MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                    folderColumn
                ) else arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION, folderColumn
                )
                val selection = if (video) null else MediaStore.Audio.Media.IS_MUSIC + " != 0"
                val sort = if (video) MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC"
                    else MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
                val collections = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    MediaStore.getExternalVolumeNames(context).map { volume ->
                        if (video) MediaStore.Video.Media.getContentUri(volume)
                        else MediaStore.Audio.Media.getContentUri(volume)
                    }
                } else listOf(
                    if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                )
                collections.forEach { collection ->
                    context.contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(if (video) MediaStore.Video.Media.TITLE else MediaStore.Audio.Media.TITLE)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                        val folderCol = cursor.getColumnIndexOrThrow(folderColumn)
                        val artistCol = if (video) -1 else cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        while (cursor.moveToNext()) {
                            val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                                ?: cursor.getString(nameCol)?.substringBeforeLast('.') ?: "Unknown"
                            result += LocalMediaItem(
                                Uri.withAppendedPath(collection, cursor.getLong(idCol).toString()),
                                title,
                                if (video) "" else cursor.getString(artistCol).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(),
                                cursor.getLong(durationCol),
                                cursor.getString(folderCol).orEmpty().let {
                                    if (android.os.Build.VERSION.SDK_INT < 29) it.substringBeforeLast('/', "") else it
                                }, video
                            )
                        }
                    }
                }
                result
            }
            scanning = false
        }
    }

    LaunchedEffect(tab, permissionRefresh) { scan() }

    LaunchedEffect(selected?.uri) {
        val track = selected ?: run {
            lyrics = emptyList()
            lyricsMessage = "Choose a song to find its lyrics"
            return@LaunchedEffect
        }
        lyrics = emptyList()
        lyricsSaved = false
        lyricsLoading = true
        lyricsMessage = "Checking offline lyrics…"
        val cached = withContext(Dispatchers.IO) { library.find(track.title, track.artist) }
        if (cached != null) {
            lyrics = parseLocalLrc(cached.lrc)
            lyricsSaved = lyrics.isNotEmpty()
            lyricsMessage = "Loaded from offline library"
        } else {
            lyricsMessage = "Searching LRCLIB…"
            runCatching { searchLocalLyrics(track.title, track.artist) }
                .onSuccess {
                    lyrics = it
                    lyricsMessage = if (it.isEmpty()) "No synced lyrics found. Import an LRC file." else "Synced lyrics from LRCLIB"
                }
                .onFailure { lyricsMessage = "Lyrics search failed. Check connection or import an LRC file." }
        }
        lyricsLoading = false
    }

    LaunchedEffect(exo, selected?.uri) {
        while (true) {
            if (!seeking) position = exo.currentPosition.coerceAtLeast(0L)
            duration = exo.duration.takeIf { it > 0L } ?: (selected?.duration ?: 0L)
            playing = exo.isPlaying
            delay(300)
        }
    }

    val activeIndex = lyrics.indexOfLast { it.timeMs <= (position - syncOffsetMs).coerceAtLeast(0L) }.coerceAtLeast(0)
    LaunchedEffect(activeIndex, lyrics.size) {
        if (lyrics.isNotEmpty()) lyricListState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
    }
    val folders = listOf("All folders") + items.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    val filtered = items.filter {
        (selectedFolder == "All folders" || it.folder == selectedFolder) &&
            (search.isBlank() || it.title.contains(search, true) ||
                it.artist.contains(search, true) || it.folder.contains(search, true))
    }
    val hasPermission = requiredMediaPermissions(tab).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (selected != null) { exo.stop(); selected = null }
                else onBack()
            }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text(if (selected == null) "LOCAL PLAYER" else "NOW PLAYING",
                Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.3.sp, color = MaterialTheme.colorScheme.primary)
            if (selected != null) TextButton(onClick = { exo.stop(); selected = null }) { Text("BROWSE") }
        }

        if (selected == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Music", "Videos").forEach { name ->
                    FilterChip(selected = tab == name, onClick = { tab = name; search = ""; selectedFolder = "All folders" }, label = { Text(name) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scan() }) { Text("RESCAN") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                folders.forEach { folder ->
                    FilterChip(selected = selectedFolder == folder, onClick = { selectedFolder = folder },
                        label = { Text(if (folder == "All folders") folder else folder.trimEnd('/').substringAfterLast('/', folder)) })
                }
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                singleLine = true, placeholder = { Text("Search title, artist or folder") },
                shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(8.dp))
            when {
                !hasPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Allow access to music and videos stored on this device.")
                        Button(onClick = onRequestMediaPermissions) { Text("GRANT MEDIA ACCESS") }
                    }
                }
                scanning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (search.isBlank()) "No " + tab.lowercase() + " found on this device." else "No matching files found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.uri.toString() }) { item ->
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = item
                            position = 0L
                            exo.setMediaItem(MediaItem.fromUri(item.uri))
                            exo.prepare()
                            exo.playWhenReady = true
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (item.isVideo) Icons.Default.Movie else Icons.Default.AudioFile,
                                        contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(item.artist.takeIf { it.isNotBlank() },
                                    item.folder.trim('/').takeIf { it.isNotBlank() }).joinToString(" • "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatLocalTime(item.duration), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        } else {
            val track = selected!!
            if (track.isVideo) {
                AndroidView(
                    factory = { PlayerView(it).apply { player = exo; useController = false } },
                    update = { it.player = exo },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 230.dp)
                        .background(Color.Black, RoundedCornerShape(14.dp))
                )
            } else {
                Box(Modifier.fillMaxWidth().height(145.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AudioFile, null, Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(track.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist.ifBlank { "Local media" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f,
                onValueChange = { seeking = true; position = it.toLong() },
                onValueChangeFinished = { exo.seekTo(position); seeking = false },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatLocalTime(position), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatLocalTime(duration), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val index = items.indexOf(track)
                    items.getOrNull(index - 1)?.let {
                        selected = it; exo.setMediaItem(MediaItem.fromUri(it.uri)); exo.prepare(); exo.play()
                    }
                }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                FilledIconButton(onClick = { if (exo.isPlaying) exo.pause() else exo.play() }, Modifier.size(54.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                IconButton(onClick = {
                    val index = items.indexOf(track)
                    items.getOrNull(index + 1)?.let {
                        selected = it; exo.setMediaItem(MediaItem.fromUri(it.uri)); exo.prepare(); exo.play()
                    }
                }) { Icon(Icons.Default.SkipNext, "Next") }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LYRICS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(lyricsMessage, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (lyrics.isNotEmpty() && !lyricsSaved) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) {
                                library.write(track.title, track.artist, "", lyrics.joinToString("\n") { line ->
                                    val cs = line.timeMs.coerceAtLeast(0L) / 10L
                                    "[%02d:%02d.%02d]%s".format(Locale.ROOT, cs / 6000, (cs / 100) % 60, cs % 100, line.text)
                                })
                            } }.onSuccess {
                                lyricsSaved = true
                                lyricsMessage = "Saved to offline library"
                            }.onFailure {
                                lyricsMessage = "Choose Downloads/SyncLyrics using Scan & Sync before saving"
                            }
                        }
                    }) { Text("↓ SAVE LYRICS OFFLINE") }
                }
            }
            if (lyrics.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (lyricsLoading) CircularProgressIndicator()
                        Text(if (lyricsLoading) "Finding synced lyrics…" else "No synced lyrics available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("IMPORT .LRC FILE") }
                        importError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                    }
                }
            } else {
                LazyColumn(state = lyricListState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(lyrics.indices.toList()) { index ->
                        Text(lyrics[index].text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            fontSize = if (index == activeIndex) 23.sp else 17.sp,
                            fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Medium,
                            color = if (index == activeIndex) lyricColor
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                }
            }
        }
    }
}

private fun parseLocalLrc(source: String): List<LyricLine> {
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    return source.lineSequence().flatMap { line ->
        val matches = regex.findAll(line).toList()
        val text = regex.replace(line, "").trim()
        if (text.isBlank()) emptySequence() else matches.asSequence().map { match ->
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                3 -> fraction.toLong()
                else -> 0L
            }
            LyricLine((match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 + millis, text)
        }
    }.sortedBy { it.timeMs }.toList()
}

private suspend fun searchLocalLyrics(title: String, artist: String): List<LyricLine> =
    withContext(Dispatchers.IO) {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        val query = "track_name=" + encode(title) + if (artist.isBlank()) "" else "&artist_name=" + encode(artist)
        val connection = (URL("https://lrclib.net/api/search?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "SyncLyrics/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val results = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val normalizedTitle = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            val normalizedArtist = artist.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            val candidates = (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val synced = item.optString("syncedLyrics")
                if (synced.isBlank()) return@mapNotNull null
                val t = item.optString("trackName").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                val a = item.optString("artistName").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                val score = (if (t == normalizedTitle) 4 else if (t.contains(normalizedTitle) || normalizedTitle.contains(t)) 2 else 0) +
                    (if (normalizedArtist.isNotBlank() && a == normalizedArtist) 4 else if (normalizedArtist.isNotBlank() && (a.contains(normalizedArtist) || normalizedArtist.contains(a))) 2 else 0)
                score to synced
            }.sortedByDescending { it.first }
            candidates.firstOrNull()?.second?.let { parseLocalLrc(it) } ?: emptyList()
        } finally { connection.disconnect() }
    }

private fun formatLocalTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
