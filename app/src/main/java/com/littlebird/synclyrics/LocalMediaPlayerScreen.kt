package com.littlebird.synclyrics

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlin.math.roundToInt

private data class LocalMediaItem(
    val uri: Uri, val title: String, val artist: String,
    val duration: Long, val folder: String, val isVideo: Boolean
)

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
    val playerPrefs = remember { context.getSharedPreferences("local_player_settings", android.content.Context.MODE_PRIVATE) }
    var rootUri by remember { mutableStateOf(playerPrefs.getString("default_folder_uri", null)) }
    var rootLabel by remember { mutableStateOf(playerPrefs.getString("default_folder_name", null) ?: "No folder selected") }
    var tab by remember { mutableStateOf("Music") }
    var selectedFolder by remember { mutableStateOf("All folders") }
    var items by remember { mutableStateOf(emptyList<LocalMediaItem>()) }
    var selected by remember { mutableStateOf<LocalMediaItem?>(null) }
    var search by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf(emptyList<LyricLine>()) }
    var artworkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lyricsMessage by remember { mutableStateOf("Choose a song to find its lyrics") }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsSaved by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var fullscreenPlayback by remember { mutableStateOf(false) }
    var playbackControlsVisible by remember { mutableStateOf(true) }
    var playbackControlsReset by remember { mutableIntStateOf(0) }
    var gestureSide by remember { mutableStateOf("") }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var videoVisible by remember { mutableStateOf(true) }
    var videoScale by remember { mutableFloatStateOf(0.32f) }
    var videoX by remember { mutableIntStateOf(16) }
    var videoY by remember { mutableIntStateOf(16) }
    val lyricListState = rememberLazyListState()
    val exo = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(exo) { onDispose { exo.release() } }
    val activity = context as? Activity
    val originalWindowBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }

    fun exitLocalFullscreen() {
        // Restore the Activity immediately, rather than relying only on Compose disposal.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        activity?.window?.let { window ->
            val attributes = window.attributes
            attributes.screenBrightness = originalWindowBrightness
            window.attributes = attributes
        }
        fullscreenPlayback = false
        playbackControlsVisible = true
    }

    BackHandler(enabled = fullscreenPlayback || selected != null) {
        if (fullscreenPlayback) {
            exitLocalFullscreen()
        } else if (selected != null) {
            exo.stop()
            selected = null
        }
    }

    DisposableEffect(fullscreenPlayback) {
        val window = activity?.window
        val decor = window?.decorView
        val oldOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val oldUiVisibility = decor?.systemUiVisibility ?: 0
        if (fullscreenPlayback) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            decor?.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        onDispose {
            if (fullscreenPlayback) {
                // Always leave this screen in portrait; this also covers system/back exits.
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                decor?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                activity?.window?.let { window ->
                    val attributes = window.attributes
                    attributes.screenBrightness = originalWindowBrightness
                    window.attributes = attributes
                }
            }
        }
    }

    LaunchedEffect(fullscreenPlayback, playbackControlsVisible, playbackControlsReset) {
        if (fullscreenPlayback && playbackControlsVisible) {
            delay(3500)
            playbackControlsVisible = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val folderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"
            rootUri = uri.toString()
            rootLabel = folderName
            playerPrefs.edit()
                .putString("default_folder_uri", uri.toString())
                .putString("default_folder_name", folderName)
                .apply()
            selectedFolder = "All folders"
            search = ""
        }
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
        val savedTree = rootUri?.let(Uri::parse)
        if (savedTree == null) {
            items = emptyList()
            scanning = false
            return
        }
        scope.launch {
            scanning = true
            items = withContext(Dispatchers.IO) {
                runCatching { scanSelectedFolder(context, savedTree, tab == "Videos") }
                    .getOrElse { emptyList() }
            }
            scanning = false
        }
    }

    LaunchedEffect(tab, permissionRefresh, rootUri) { scan() }

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

    LaunchedEffect(selected?.uri, selected?.title, selected?.artist, selected?.isVideo) {
        val track = selected
        artworkBitmap = null
        if (track != null && !track.isVideo) {
            artworkBitmap = withContext(Dispatchers.IO) { loadOrFetchLocalArtwork(context, track) }
        }
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
    val hasPermission = rootUri != null && DocumentFile.fromTreeUri(context, Uri.parse(rootUri!!)) != null

    if (fullscreenPlayback) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(fullscreenPlayback) {
                    detectTapGestures {
                        playbackControlsVisible = true
                        playbackControlsReset++
                    }
                }
                .pointerInput(fullscreenPlayback) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            gestureSide = if (offset.x < size.width / 2f) "BRIGHTNESS" else "VOLUME"
                            gestureValue = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val activityWindow = activity?.window
                            val maxVolume = (context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                                .getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            if (gestureSide == "BRIGHTNESS") {
                                val current = activityWindow?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                val next = (current - dragAmount.y / size.height * 1.2f).coerceIn(0.01f, 1f)
                                activityWindow?.let { window ->
                                    val attributes = window.attributes
                                    attributes.screenBrightness = next
                                    window.attributes = attributes
                                }
                                gestureValue = next
                            } else {
                                val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                val next = (current - dragAmount.y / size.height * maxVolume * 1.2f).roundToInt().coerceIn(0, maxVolume)
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
                                gestureValue = next.toFloat() / maxVolume
                            }
                        },
                        onDragEnd = { gestureSide = "" },
                        onDragCancel = { gestureSide = "" }
                    )
                }
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val containerWidthPx = with(density) { maxWidth.roundToPx() }
            val containerHeightPx = with(density) { maxHeight.roundToPx() }
            val currentTrack = selected
            val lyricIndex = lyrics.indexOfLast { it.timeMs <= (position - syncOffsetMs).coerceAtLeast(0L) }.coerceAtLeast(0)
            // Keep the lyric group clear of the bottom transport area. The controls
            // remain layered above the playback screen, so long lyrics can use the
            // remaining space without colliding with the seek bar or buttons.
            Column(
                Modifier.fillMaxSize().padding(start = 36.dp, top = 20.dp, end = 36.dp, bottom = 142.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (lyrics.isNotEmpty()) {
                    if (lyricIndex > 0) Text(
                        lyrics[lyricIndex - 1].text, color = Color.White.copy(alpha = 0.42f),
                        fontSize = 22.sp, lineHeight = 29.sp,
                        textAlign = TextAlign.Center, maxLines = 2
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        lyrics[lyricIndex].text,
                        color = lyricColor,
                        fontSize = 46.sp,
                        lineHeight = 60.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                    if (lyricIndex < lyrics.lastIndex) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            lyrics[lyricIndex + 1].text,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 22.sp,
                            lineHeight = 29.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                } else {
                    Text("No synced lyrics available", color = Color.White.copy(alpha = 0.65f))
                }
            }

            if (currentTrack?.isVideo == true && videoVisible) {
                val popupWidth = maxWidth * videoScale
                val popupHeight = popupWidth * 9f / 16f
                Box(
                    Modifier.offset { IntOffset(videoX, videoY) }
                        .width(popupWidth).height(popupHeight)
                        .background(Color(0xFF151515), RoundedCornerShape(10.dp))
                        .pointerInput(fullscreenPlayback) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                videoX = (videoX + drag.x.roundToInt()).coerceIn(0, (containerWidthPx - size.width).coerceAtLeast(0))
                                videoY = (videoY + drag.y.roundToInt()).coerceIn(0, (containerHeightPx - size.height).coerceAtLeast(0))
                            }
                        }
                ) {
                    AndroidView(
                        factory = { PlayerView(it).apply { player = exo; useController = false } },
                        update = { it.player = exo },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (playbackControlsVisible) {
                        Surface(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(25.dp),
                            shape = RoundedCornerShape(50), color = Color(0xCC10120F)
                        ) {
                            IconButton(onClick = { videoVisible = false }, modifier = Modifier.fillMaxSize()) {
                                Text("×", color = Color.White, fontSize = 17.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                    // Drag this corner handle horizontally to resize the video popup.
                    Surface(
                        Modifier.align(Alignment.BottomEnd).padding(5.dp).size(30.dp)
                            .pointerInput(containerWidthPx) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    videoScale = (videoScale + drag.x / containerWidthPx.coerceAtLeast(1))
                                        .coerceIn(0.22f, 0.60f)
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xDD10120F)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("↘", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (playbackControlsVisible) {
                TextButton(
                    onClick = {
                        exitLocalFullscreen()
                        playbackControlsReset++
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) { Text("EXIT", color = Color.White, fontWeight = FontWeight.Bold) }

                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val trackIndex = currentTrack?.let { items.indexOf(it) } ?: -1
                    IconButton(onClick = {
                        items.getOrNull(trackIndex - 1)?.let {
                            selected = it; videoVisible = it.isVideo; videoX = 16; videoY = 16
                            exo.setMediaItem(MediaItem.fromUri(it.uri)); exo.prepare(); exo.play()
                        }
                    }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                    Spacer(Modifier.width(30.dp))
                    FilledIconButton(onClick = { if (exo.isPlaying) exo.pause() else exo.play() },
                        modifier = Modifier.size(54.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    }
                    Spacer(Modifier.width(30.dp))
                    IconButton(onClick = {
                        items.getOrNull(trackIndex + 1)?.let {
                            selected = it; videoVisible = it.isVideo; videoX = 16; videoY = 16
                            exo.setMediaItem(MediaItem.fromUri(it.uri)); exo.prepare(); exo.play()
                        }
                    }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp)) {
                    Slider(
                        value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f,
                        onValueChange = { seeking = true; position = it.toLong() },
                        onValueChangeFinished = { exo.seekTo(position); seeking = false },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatLocalTime(position), color = Color.White, fontSize = 10.sp)
                        Text(formatLocalTime(duration), color = Color.White, fontSize = 10.sp)
                    }
                }
            }
            if (gestureSide.isNotBlank()) {
                Surface(Modifier.align(if (gestureSide == "BRIGHTNESS") Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(18.dp), color = Color(0xDD252B23), shape = RoundedCornerShape(14.dp)) {
                    Text("${if (gestureSide == "BRIGHTNESS") "☀" else "♪"}  ${(gestureValue * 100).toInt()}%",
                        Modifier.padding(14.dp), color = Color.White)
                }
            }
        }
    } else {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DEFAULT FOLDER", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                    Text(rootLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { folderPicker.launch(rootUri?.let(Uri::parse)) }) {
                    Text(if (rootUri == null) "CHOOSE FOLDER" else "CHANGE")
                }
            }
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
                        Text("Choose a default folder to browse your music and videos.")
                        Button(onClick = { folderPicker.launch(null) }) { Text("CHOOSE DEFAULT FOLDER") }
                    }
                }
                scanning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (search.isBlank()) "No " + tab.lowercase() + " found in this folder or its subfolders." else "No matching files found.",
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
            } else if (artworkBitmap != null) {
                Image(
                    bitmap = artworkBitmap!!.asImageBitmap(),
                    contentDescription = "Album artwork for ${track.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(145.dp)
                        .padding(horizontal = 0.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                )
            } else {
                val rotation = rememberInfiniteTransition(label = "vinylRotation")
                    .animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 6000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "vinylAngle"
                    )
                val recordAccent = MaterialTheme.colorScheme.primary
                Box(
                    Modifier.fillMaxWidth().height(145.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // The record spins continuously while the tonearm stays in place.
                    Canvas(
                        Modifier.size(128.dp).graphicsLayer { rotationZ = rotation.value }
                    ) {
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val radius = size.minDimension * 0.47f
                        drawCircle(Color(0xFF171A18), radius, center)
                        for (index in 1..9) {
                            val grooveRadius = radius * (0.43f + index * 0.052f)
                            drawCircle(
                                color = Color.White.copy(alpha = if (index % 3 == 0) 0.19f else 0.09f),
                                radius = grooveRadius,
                                center = center,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                        drawArc(
                            color = Color.White.copy(alpha = 0.22f),
                            startAngle = 205f,
                            sweepAngle = 82f,
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * 0.88f, center.y - radius * 0.88f),
                            size = androidx.compose.ui.geometry.Size(radius * 1.76f, radius * 1.76f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawCircle(recordAccent, radius * 0.29f, center)
                        drawCircle(Color(0xFF263128), radius * 0.19f, center)
                        drawCircle(Color(0xFFB8F36B), radius * 0.035f,
                            androidx.compose.ui.geometry.Offset(center.x + radius * 0.19f, center.y - radius * 0.12f))
                        drawCircle(Color(0xFF080A09), radius * 0.055f, center)
                    }
                    Canvas(Modifier.size(145.dp)) {
                        val start = androidx.compose.ui.geometry.Offset(size.width * 0.86f, size.height * 0.13f)
                        val elbow = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.22f)
                        val end = androidx.compose.ui.geometry.Offset(size.width * 0.69f, size.height * 0.38f)
                        drawLine(Color(0xFF9EA69F), start, elbow, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color(0xFF737D75), elbow, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(Color(0xFFB8F36B), 4.dp.toPx(), start)
                        drawCircle(Color(0xFFBEC5BF), 3.dp.toPx(), end)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.ifBlank { "Local media" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    videoVisible = track.isVideo
                    videoScale = 0.32f
                    videoX = 16
                    videoY = 16
                    playbackControlsVisible = true
                    playbackControlsReset++
                    fullscreenPlayback = true
                }, enabled = lyrics.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("PLAYBACK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(7.dp))
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
}

private fun scanSelectedFolder(
    context: android.content.Context,
    treeUri: Uri,
    videosOnly: Boolean
): List<LocalMediaItem> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val results = mutableListOf<LocalMediaItem>()
    val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "3gp", "amr")
    val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "mpeg", "mpg", "m4v", "ts")

    fun visit(directory: DocumentFile, relativePath: String, depth: Int) {
        if (depth > 20) return
        val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
        children.forEach { file ->
            if (file.isDirectory) {
                val name = file.name ?: return@forEach
                visit(file, if (relativePath.isBlank()) name else "$relativePath/$name", depth + 1)
            } else if (file.isFile) {
                val name = file.name ?: return@forEach
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val mime = file.type.orEmpty()
                val isVideo = mime.startsWith("video/") || extension in videoExtensions
                val isAudio = mime.startsWith("audio/") || extension in audioExtensions
                if ((videosOnly && !isVideo) || (!videosOnly && !isAudio)) return@forEach

                var title = name.substringBeforeLast('.', name)
                var artist = ""
                var duration = 0L
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, file.uri)
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: title
                    if (!isVideo) {
                        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                            .takeUnless { it == "<unknown>" }.orEmpty()
                    }
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } catch (_: Exception) {
                    // Some document providers do not expose metadata; filename remains usable.
                } finally {
                    runCatching { retriever.release() }
                }
                results += LocalMediaItem(
                    file.uri, title, artist, duration,
                    if (relativePath.isBlank()) root.name ?: "Selected folder" else relativePath,
                    isVideo
                )
            }
        }
    }

    visit(root, "", 0)
    return results.sortedBy { it.title.lowercase(Locale.ROOT) }
}

private suspend fun loadOrFetchLocalArtwork(
    context: android.content.Context,
    track: LocalMediaItem
): Bitmap? = withContext(Dispatchers.IO) {
    val cacheDir = File(context.filesDir, "artwork").apply { mkdirs() }
    val cacheFile = File(cacheDir, artworkCacheName(track))
    if (cacheFile.exists() && cacheFile.length() > 0L) {
        BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return@withContext it }
    }

    // Embedded artwork always takes priority over an online match.
    val embedded = runCatching {
        MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(context, track.uri)
                retriever.embeddedPicture?.let { bytes ->
                    cacheFile.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } finally {
                retriever.release()
            }
        }
    }.getOrNull()
    if (embedded != null) {
        publishArtworkToDownloads(context, cacheFile, artworkCacheName(track))
        return@withContext embedded
    }

    if (track.title.isBlank()) return@withContext null
    val query = java.net.URLEncoder.encode(
        listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" "),
        "UTF-8"
    )
    val connection = runCatching {
        (URL("https://itunes.apple.com/search?term=$query&entity=song&limit=8").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "SyncLyrics/1.0 (Android)")
        }
    }.getOrNull() ?: return@withContext null

    try {
        if (connection.responseCode !in 200..299) return@withContext null
        val json = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val results = json.optJSONArray("results") ?: return@withContext null
        val wantedTitle = normalizeArtworkText(track.title)
        val wantedArtist = normalizeArtworkText(track.artist)
        val best = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val title = normalizeArtworkText(item.optString("trackName"))
            val artist = normalizeArtworkText(item.optString("artistName"))
            val titleScore = if (title == wantedTitle) 4 else if (title.contains(wantedTitle) || wantedTitle.contains(title)) 2 else 0
            val artistScore = if (wantedArtist.isBlank()) 0 else if (artist == wantedArtist) 4
                else if (artist.contains(wantedArtist) || wantedArtist.contains(artist)) 2 else 0
            (titleScore + artistScore) to item.optString("artworkUrl100")
        }.filter { it.first >= if (wantedArtist.isBlank()) 2 else 4 }
            .maxByOrNull { it.first }?.second?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return@withContext null

        val largeUrl = best.replace(Regex("\\d+x\\d+bb"), "600x600bb")
        val imageConnection = (URL(largeUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "SyncLyrics/1.0 (Android)")
        }
        try {
            if (imageConnection.responseCode !in 200..299) return@withContext null
            val bytes = imageConnection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 8 * 1024 * 1024) return@withContext null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            cacheFile.writeBytes(bytes)
            publishArtworkToDownloads(context, cacheFile, artworkCacheName(track))
            bitmap
        } finally {
            imageConnection.disconnect()
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun artworkCacheName(track: LocalMediaItem): String {
    val key = "${normalizeArtworkText(track.title)}|${normalizeArtworkText(track.artist)}"
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) } + ".jpg"
}

private fun normalizeArtworkText(value: String): String =
    value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

private fun publishArtworkToDownloads(context: android.content.Context, source: File, fileName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !source.exists()) return
    runCatching {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/SyncLyrics/Artwork"
        val existing = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath + "/"),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        val uri = if (existing != null) {
            android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing)
        } else {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: return
        resolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
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
