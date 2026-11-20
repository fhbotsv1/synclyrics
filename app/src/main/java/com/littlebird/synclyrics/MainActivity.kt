package com.littlebird.synclyrics

import android.Manifest
import android.content.BroadcastReceiver
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

private const val ACTION_MEDIA_UPDATE = "com.littlebird.synclyrics.MEDIA_UPDATE"
private const val ACTION_VISUALIZER_LEVEL = "com.littlebird.synclyrics.VISUALIZER_LEVEL"

data class LyricLine(val timeMs: Long, val text: String)
data class PlayerInfo(
    val title: String = "", val artist: String = "", val album: String = "",
    val packageName: String = "", val position: Long = 0L, val duration: Long = 0L,
    val speed: Float = 1f, val playing: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_UPDATE) return
            val info = PlayerInfo(
                title = intent.getStringExtra("title").orEmpty(),
                artist = intent.getStringExtra("artist").orEmpty(),
                album = intent.getStringExtra("album").orEmpty(),
                packageName = intent.getStringExtra("package").orEmpty(),
                position = intent.getLongExtra("position", 0L),
                duration = intent.getLongExtra("duration", 0L),
                speed = intent.getFloatExtra("speed", 1f),
                playing = intent.getBooleanExtra("playing", false)
            )
            onPlayerUpdate?.invoke(info)
        }
    }
    private var onPlayerUpdate: ((PlayerInfo) -> Unit)? = null
    private var onVisualizerBands: ((FloatArray) -> Unit)? = null
    private val visualizerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_VISUALIZER_LEVEL) {
                onVisualizerBands?.invoke(intent.getFloatArrayExtra("bands") ?: FloatArray(48))
            }
        }
    }
    private var playbackUiEnabled = false

    private fun setPlaybackUi(enabled: Boolean) {
        playbackUiEnabled = enabled
        window.decorView.systemUiVisibility = if (enabled) {
            (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        } else {
            android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playbackUiEnabled) setPlaybackUi(true)
    }
    private var accessRefresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyncLyricsScreen() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_MEDIA_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(mediaReceiver, filter) }
        val visualizerFilter = IntentFilter(ACTION_VISUALIZER_LEVEL)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(visualizerReceiver, visualizerFilter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(visualizerReceiver, visualizerFilter) }
        sendBroadcast(Intent("com.littlebird.synclyrics.REFRESH_SESSIONS").setPackage(packageName))
    }

    override fun onResume() {
        super.onResume()
        accessRefresh++
        sendBroadcast(Intent("com.littlebird.synclyrics.REFRESH_SESSIONS").setPackage(packageName))
    }

    override fun onStop() {
        runCatching { unregisterReceiver(mediaReceiver) }
        runCatching { unregisterReceiver(visualizerReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        onPlayerUpdate = null
        onVisualizerBands = null
        super.onDestroy()
    }

    @Composable
    private fun SyncLyricsScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var player by remember { mutableStateOf(PlayerInfo()) }
        var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
        var lyricsMessage by remember { mutableStateOf("Waiting for a song…") }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val lyricsDb = remember { LyricsDatabase(context) }
        val publicLibrary = remember { PublicLyricsLibrary(context) }
        val migrationPrefs = remember { context.getSharedPreferences("synclyrics_migration", Context.MODE_PRIVATE) }
        var isSaved by remember { mutableStateOf(false) }
        var showLibrary by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var localPlayerMode by remember { mutableStateOf(false) }
        var mediaPermissionRefresh by remember { mutableIntStateOf(0) }
        val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPermissionRefresh++
        }
        val settingsPrefs = remember { context.getSharedPreferences("synclyrics_settings", Context.MODE_PRIVATE) }
        var syncOffsetMs by remember { mutableIntStateOf(settingsPrefs.getInt("lyrics_sync_offset_ms", 0).coerceIn(-5000, 5000)) }
        var multicolorVisualizer by remember { mutableStateOf(settingsPrefs.getBoolean("visualizer_multicolor", false)) }
        var visualizerStyle by remember { mutableStateOf(settingsPrefs.getString("visualizer_style", "Bars") ?: "Bars") }
        var visualizerColorMode by remember { mutableStateOf(settingsPrefs.getString("visualizer_color_mode", if (settingsPrefs.getBoolean("visualizer_multicolor", false)) "Spectrum" else "Single") ?: "Single") }
        var visualizerHeight by remember { mutableFloatStateOf(settingsPrefs.getFloat("visualizer_height", 46f).coerceIn(20f, 80f)) }
        var visualizerSensitivity by remember { mutableFloatStateOf(settingsPrefs.getFloat("visualizer_sensitivity", 1f).coerceIn(0.5f, 2f)) }
        var playbackFontSize by remember { mutableFloatStateOf(settingsPrefs.getFloat("playback_font_size", 46f).coerceIn(30f, 64f)) }
        var playbackLineSpacing by remember { mutableFloatStateOf(settingsPrefs.getFloat("playback_line_spacing", 22f).coerceIn(8f, 40f)) }
        var playbackVerticalPosition by remember { mutableFloatStateOf(settingsPrefs.getFloat("playback_vertical_position", 0f).coerceIn(-100f, 100f)) }
        var playbackFontStyle by remember { mutableStateOf(settingsPrefs.getString("playback_font_style", "Default") ?: "Default") }
        var lyricColorValue by remember { mutableLongStateOf(settingsPrefs.getLong("lyrics_color", 0xFFB8F36BL)) }
        val lyricColorOptions = listOf("Lime" to 0xFFB8F36BL, "Cyan" to 0xFF55DDE0L, "Blue" to 0xFF6EA8F7L, "Purple" to 0xFFBE8CFFL, "Pink" to 0xFFFF80ABL, "Amber" to 0xFFFFC15AL, "White" to 0xFFFFFFFFL)
        val lyricColor = Color(lyricColorValue)
        var playbackMode by remember { mutableStateOf(false) }
        var gestureSide by remember { mutableStateOf("") }
        var gestureValue by remember { mutableFloatStateOf(0f) }
        var playbackControlsVisible by remember { mutableStateOf(true) }
        var playbackControlsReset by remember { mutableIntStateOf(0) }
        var visualizerEnabled by remember { mutableStateOf(context.getSharedPreferences("synclyrics_settings", Context.MODE_PRIVATE).getBoolean("visualizer_enabled", false)) }
        var audioBands by remember { mutableStateOf(FloatArray(48)) }
        onVisualizerBands = { audioBands = it }
        val projectionManager = remember { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
        val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
                    putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                visualizerEnabled = true
                context.getSharedPreferences("synclyrics_settings", Context.MODE_PRIVATE).edit().putBoolean("visualizer_enabled", true).apply()
            } else {
                visualizerEnabled = false
                context.getSharedPreferences("synclyrics_settings", Context.MODE_PRIVATE).edit().putBoolean("visualizer_enabled", false).apply()
            }
        }
        val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            else {
                visualizerEnabled = false
                context.getSharedPreferences("synclyrics_settings", Context.MODE_PRIVATE).edit().putBoolean("visualizer_enabled", false).apply()
            }
        }
        var libraryItems by remember { mutableStateOf(emptyList<SavedLyrics>()) }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                publicLibrary.saveFolderAccess(uri)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (!migrationPrefs.getBoolean("legacy_migrated", false)) {
                                publicLibrary.migrateLegacy(lyricsDb.all())
                                lyricsDb.close()
                                context.deleteDatabase("synclyrics.db")
                                migrationPrefs.edit().putBoolean("legacy_migrated", true).apply()
                            }
                            publicLibrary.all()
                        }
                    }.onSuccess { libraryItems = it; lyricsMessage = "Folder scanned and library synced" }
                        .onFailure { error = it.message ?: "Could not scan the selected folder." }
                }
            }
        }

        fun scanAndSync() {
            if (!publicLibrary.hasFolderAccess()) {
                folderPicker.launch(null)
                return
            }
            scope.launch {
                loading = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (!migrationPrefs.getBoolean("legacy_migrated", false)) {
                            publicLibrary.migrateLegacy(lyricsDb.all())
                            lyricsDb.close()
                            context.deleteDatabase("synclyrics.db")
                            migrationPrefs.edit().putBoolean("legacy_migrated", true).apply()
                        }
                        publicLibrary.all()
                    }
                }.onSuccess { libraryItems = it; lyricsMessage = "Scanned ${it.size} LRC file(s) — library synced" }
                    .onFailure { error = it.message ?: "Scan & Sync failed." }
                loading = false
            }
        }


        var positionNow by remember { mutableLongStateOf(0L) }
        var lyricsReload by remember { mutableIntStateOf(0) }
        var hasAccess by remember { mutableStateOf(hasNotificationAccess()) }
        LaunchedEffect(accessRefresh) { hasAccess = hasNotificationAccess() }
        val listState = rememberLazyListState()

        fun applyPlayer(next: PlayerInfo) {
            // Media-session refreshes and returning from the document picker can briefly
            // produce an empty session update. Do not let that transient update erase
            // the current song or its lyrics.
            if (next.title.isBlank() && player.title.isNotBlank()) {
                // Ignore transient empty media-session updates (for example when
                // returning from screen sharing or another system activity).
                // Keeping the last known playing state lets the lyric clock continue.
                return
            }

            val changedSong = next.title != player.title || next.artist != player.artist
            player = next
            positionNow = next.position
            if (changedSong) {
                lyrics = emptyList()
                isSaved = false
                lyricsMessage = if (next.title.isBlank()) "Waiting for a song…" else "Finding synced lyrics…"
            }
        }
        onPlayerUpdate = { applyPlayer(it) }

        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: throw IllegalStateException("Unable to read this file.")
                    }
                }.onSuccess { text ->
                    val parsed = parseLrc(text)
                    if (parsed.isEmpty()) error = "No timestamped lyric lines found in this LRC file."
                    else {
                        lyrics = parsed
                        withContext(Dispatchers.IO) { publicLibrary.write(player.title.ifBlank { "Imported lyrics" }, player.artist, player.album, text) }
                        isSaved = true
                        lyricsMessage = "Imported and saved offline"
                        error = null
                    }
                }.onFailure { error = it.message ?: "Could not open lyrics file." }
            }
        }

        val songKey = "${player.title.lowercase(Locale.ROOT)}|${player.artist.lowercase(Locale.ROOT)}"
        LaunchedEffect(songKey, lyricsReload) {
            if (player.title.isNotBlank()) {
                loading = true
                val cached = withContext(Dispatchers.IO) { publicLibrary.find(player.title, player.artist) }
                if (cached != null) {
                    lyrics = parseLrc(cached.lrc)
                    isSaved = lyrics.isNotEmpty()
                    lyricsMessage = "Loaded from offline library"
                } else {
                    lyricsMessage = "Looking up lyrics online…"
                    val result = runCatching { fetchLyrics(player.title, player.artist, player.album) }
                    result.onSuccess { found ->
                        lyrics = found
                        lyricsMessage = if (found.isEmpty()) "No synced lyrics found. Import an LRC file." else "Synced lyrics — tap Download to save"
                    }.onFailure {
                        lyricsMessage = "Could not retrieve lyrics. Check your connection or import an LRC file."
                    }
                }
                loading = false
            } else {
                lyrics = emptyList()
                isSaved = false
                lyricsMessage = "Waiting for a song…"
            }
        }

        LaunchedEffect(player.playing, player.position, player.speed) {
            positionNow = player.position
            while (player.playing) {
                delay(250)
                positionNow += (250 * player.speed).toLong().coerceAtLeast(0)
            }
        }

        LaunchedEffect(playbackMode, playbackControlsVisible, playbackControlsReset) {
            if (playbackMode && playbackControlsVisible) {
                delay(3500)
                playbackControlsVisible = false
            }
        }

        val adjustedLyricsPosition = (positionNow - syncOffsetMs).coerceAtLeast(0L)
        val activeIndex = lyrics.indexOfLast { it.timeMs <= adjustedLyricsPosition }.coerceAtLeast(0)
        LaunchedEffect(activeIndex, lyrics.size) {
            if (lyrics.isNotEmpty()) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }

        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFFB8F36B), onPrimary = Color(0xFF17210A),
            secondary = Color(0xFFB8F36B), background = Color(0xFF10120F),
            surface = Color(0xFF191D18), surfaceVariant = Color(0xFF252B23)
        )) {
            Surface(Modifier.fillMaxSize(), color = if (playbackMode) Color.Black else MaterialTheme.colorScheme.background) {
                if (playbackMode) {
                    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
                    val originalScreenBrightness = remember { window.attributes.screenBrightness }
                    var brightness by remember {
                        mutableFloatStateOf((originalScreenBrightness.takeIf { it >= 0f } ?: 0.5f).coerceIn(0.01f, 1f))
                    }
                    var volumeLevel by remember {
                        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .pointerInput(playbackMode) {
                                detectTapGestures {
                                    playbackControlsVisible = true
                                    playbackControlsReset++
                                }
                            }
                            .pointerInput(playbackMode) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    gestureSide = if (offset.x < size.width / 2f) "BRIGHTNESS" else "VOLUME"
                                    volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                    gestureValue = if (gestureSide == "BRIGHTNESS") brightness
                                        else volumeLevel /
                                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    val adjustment = -dragAmount / size.height.coerceAtLeast(1).toFloat() * 1.2f
                                    if (gestureSide == "BRIGHTNESS") {
                                        brightness = (brightness + adjustment).coerceIn(0.01f, 1f)
                                        val attributes = window.attributes
                                        attributes.screenBrightness = brightness
                                        window.attributes = attributes
                                        gestureValue = brightness
                                    } else if (gestureSide == "VOLUME") {
                                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        volumeLevel = (volumeLevel + adjustment * maxVolume).coerceIn(0f, maxVolume.toFloat())
                                        val nextVolume = volumeLevel.roundToInt().coerceIn(0, maxVolume)
                                        if (nextVolume != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVolume, 0)
                                        }
                                        gestureValue = volumeLevel / maxVolume
                                    }
                                },
                                onDragEnd = { gestureSide = "" },
                                onDragCancel = { gestureSide = "" }
                            )
                        }
                    ) {
                        if (visualizerEnabled && Build.VERSION.SDK_INT >= 29) {
                            Canvas(Modifier.fillMaxSize()) {
                                val bands = audioBands.size
                                val centerY = size.height / 2f
                                val maxBarHeight = size.height * (visualizerHeight / 100f)
                                fun bandColor(index: Int, level: Float): Color = when (visualizerColorMode) {
                                    "Spectrum" -> Color.hsv(
                                        hue = index.toFloat() / bands.coerceAtLeast(1) * 300f,
                                        saturation = (0.35f + level * 0.65f).coerceIn(0f, 1f),
                                        value = (0.22f + level * 0.78f).coerceIn(0f, 1f),
                                        alpha = (0.10f + level * 0.55f).coerceAtMost(0.65f)
                                    )
                                    "Gradient" -> Color.hsv(
                                        hue = (index.toFloat() / bands.coerceAtLeast(1) * 270f + 90f) % 360f,
                                        saturation = 0.82f, value = 1f,
                                        alpha = (0.12f + level * 0.48f).coerceAtMost(0.60f)
                                    )
                                    else -> lyricColor.copy(alpha = (0.08f + level * 0.34f).coerceAtMost(0.42f))
                                }
                                val levels = audioBands.map { (it * visualizerSensitivity).coerceIn(0f, 1f) }
                                when (visualizerStyle) {
                                    "Waveform" -> {
                                        val stepX = size.width / (bands - 1).coerceAtLeast(1)
                                        for (i in 0 until bands - 1) {
                                            val y1 = centerY - (levels[i] - 0.08f) * maxBarHeight
                                            val y2 = centerY - (levels[i + 1] - 0.08f) * maxBarHeight
                                            drawLine(bandColor(i, levels[i]), Offset(i * stepX, y1),
                                                Offset((i + 1) * stepX, y2), strokeWidth = 4.dp.toPx())
                                        }
                                    }
                                    "Circular" -> {
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val baseRadius = size.minDimension * 0.18f
                                        val maxLength = size.minDimension * (visualizerHeight / 100f) * 0.42f
                                        for (i in 0 until bands) {
                                            val angle = (i.toFloat() / bands * Math.PI * 2.0 - Math.PI / 2.0)
                                            val inner = baseRadius
                                            val outer = inner + (maxLength * levels[i]).coerceAtLeast(2f)
                                            drawLine(bandColor(i, levels[i]),
                                                Offset(cx + kotlin.math.cos(angle).toFloat() * inner, cy + kotlin.math.sin(angle).toFloat() * inner),
                                                Offset(cx + kotlin.math.cos(angle).toFloat() * outer, cy + kotlin.math.sin(angle).toFloat() * outer),
                                                strokeWidth = 3.dp.toPx())
                                        }
                                    }
                                    else -> {
                                        val gap = size.width * 0.0065f
                                        val barWidth = (size.width - gap * (bands - 1)) / bands
                                        for (i in 0 until bands) {
                                            val level = levels[i]
                                            val barHeight = (maxBarHeight * level).coerceAtLeast(2f)
                                            val x = i * (barWidth + gap)
                                            val mirrored = visualizerStyle == "Mirrored Bars"
                                            if (mirrored) {
                                                val halfHeight = (barHeight * 0.5f).coerceAtMost(size.height * 0.38f)
                                                drawRoundRect(
                                                    color = bandColor(i, level),
                                                    topLeft = Offset(x, centerY - halfHeight),
                                                    size = Size(barWidth, halfHeight),
                                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                                                )
                                                drawRoundRect(
                                                    color = bandColor(i, level),
                                                    topLeft = Offset(x, centerY),
                                                    size = Size(barWidth, halfHeight),
                                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                                                )
                                            } else {
                                                drawRoundRect(
                                                    color = bandColor(i, level),
                                                    topLeft = Offset(x, size.height - barHeight),
                                                    size = Size(barWidth, barHeight),
                                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (playbackControlsVisible) {
                        Button(
                            onClick = {
                                playbackMode = false
                                val restoredAttributes = window.attributes
                                restoredAttributes.screenBrightness = originalScreenBrightness
                                window.attributes = restoredAttributes
                                setPlaybackUi(false)
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            },
                            modifier = Modifier.align(Alignment.TopEnd),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("EXIT", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        }

                        Column(
                            modifier = Modifier.fillMaxSize().padding(bottom = 58.dp)
                                .offset(y = playbackVerticalPosition.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (lyrics.isNotEmpty()) {
                                if (activeIndex > 0) {
                                    Text(
                                        lyrics[activeIndex - 1].text,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = (playbackFontSize * 0.48f).sp,
                                        lineHeight = (playbackFontSize * 0.63f).sp,
                                        fontFamily = when (playbackFontStyle) {
                                            "Serif" -> FontFamily.Serif
                                            "Monospace" -> FontFamily.Monospace
                                            else -> FontFamily.Default
                                        },
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.42f),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                    Spacer(Modifier.height(playbackLineSpacing.dp))
                                }
                                Text(
                                    lyrics[activeIndex].text,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = playbackFontSize.sp,
                                    lineHeight = (playbackFontSize * 1.2f).sp,
                                    fontFamily = when (playbackFontStyle) {
                                        "Serif" -> FontFamily.Serif
                                        "Monospace" -> FontFamily.Monospace
                                        else -> FontFamily.Default
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = lyricColor,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3
                                )
                                if (activeIndex < lyrics.lastIndex) {
                                    Spacer(Modifier.height(playbackLineSpacing.dp))
                                    Text(
                                        lyrics[activeIndex + 1].text,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = (playbackFontSize * 0.48f).sp,
                                        lineHeight = (playbackFontSize * 0.63f).sp,
                                        fontFamily = when (playbackFontStyle) {
                                            "Serif" -> FontFamily.Serif
                                            "Monospace" -> FontFamily.Monospace
                                            else -> FontFamily.Default
                                        },
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.65f),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            } else {
                                Text("No synced lyrics available", color = Color.White.copy(alpha = 0.65f))
                            }
                        }

                        if (playbackControlsVisible) {
                        Row(
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .padding(horizontal = 28.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                sendBroadcast(Intent("com.littlebird.synclyrics.MEDIA_CONTROL")
                                    .setPackage(packageName).putExtra("command", "previous"))
                            }) { Text("⏮", fontSize = 30.sp, color = Color.White) }
                            Spacer(Modifier.width(34.dp))
                            FilledIconButton(
                                onClick = {
                                    sendBroadcast(Intent("com.littlebird.synclyrics.MEDIA_CONTROL")
                                        .setPackage(packageName).putExtra("command", "play_pause"))
                                },
                                modifier = Modifier.size(58.dp)
                            ) {
                                Text(if (player.playing) "Ⅱ" else "▶", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(34.dp))
                            TextButton(onClick = {
                                sendBroadcast(Intent("com.littlebird.synclyrics.MEDIA_CONTROL")
                                    .setPackage(packageName).putExtra("command", "next"))
                            }) { Text("⏭", fontSize = 30.sp, color = Color.White) }
                        }
                        }
                        if (gestureSide.isNotBlank()) {
                            Surface(
                                modifier = Modifier.align(
                                    if (gestureSide == "BRIGHTNESS") Alignment.CenterStart else Alignment.CenterEnd
                                ).padding(horizontal = 18.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xDD252B23)
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (gestureSide == "BRIGHTNESS") "☀" else "♪", fontSize = 22.sp, color = Color(0xFFB8F36B))
                                    Text("${(gestureValue * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else if (localPlayerMode) {
                    LocalMediaPlayerScreen(
                        library = publicLibrary,
                        permissionRefresh = mediaPermissionRefresh,
                        onBack = { localPlayerMode = false },
                        onRequestMediaPermissions = {
                            val permissions = if (Build.VERSION.SDK_INT >= 33) {
                                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
                            } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            mediaPermissionLauncher.launch(permissions)
                        }
                    )
                } else {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(
                        "SYNC LYRICS",
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 25.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scanAndSync() }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp),
                            shape = RoundedCornerShape(12.dp)) {
                            Text("SCAN & SYNC", fontSize = 11.sp, maxLines = 1)
                        }
                        Button(onClick = {
                            scope.launch {
                                libraryItems = withContext(Dispatchers.IO) { publicLibrary.all() }
                                showLibrary = true
                            }
                        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp),
                            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.filledTonalButtonColors()) {
                            Text("LIBRARY", fontSize = 11.sp)
                        }
                        Button(onClick = { showSettings = true }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp),
                            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.filledTonalButtonColors()) {
                            Text("⚙ SETTINGS", fontSize = 11.sp, maxLines = 1)
                        }
                        Button(onClick = {
                            localPlayerMode = true
                            mediaPermissionRefresh++
                        }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp),
                            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.filledTonalButtonColors()) {
                            Text("PLAYER", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("MUSIC VISUALIZER", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(if (visualizerEnabled) "ON" else "OFF", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = visualizerEnabled,
                            enabled = Build.VERSION.SDK_INT >= 29,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    visualizerEnabled = false
                                    context.getSharedPreferences("synclyrics_settings", Context.MODE_PRIVATE).edit().putBoolean("visualizer_enabled", false).apply()
                                    context.startService(Intent(context, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                            Text(player.title.ifBlank { "Play music in another app" },
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(player.artist.ifBlank { "Spotify • YouTube Music • VLC and more" },
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("LYRICS", fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(lyricsMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (lyrics.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                enabled = !isSaved,
                                onClick = {
                                    scope.launch {
                                        val lrcText = serializeLrc(lyrics)
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                publicLibrary.write(player.title, player.artist, player.album, lrcText)
                                            }
                                        }.onSuccess {
                                            isSaved = true
                                            lyricsMessage = "Saved to public Downloads/SyncLyrics"
                                        }.onFailure { error = it.message ?: "Could not save or export lyrics." }
                                    }
                                }
                            ) { Text(if (isSaved) "✓ DOWNLOADED" else "↓ DOWNLOAD LYRICS") }
                        }
                    }
                    if (lyrics.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text(
                                    when {
                                        !hasAccess -> "Allow notification access to detect music playing in other apps."
                                        player.title.isBlank() -> "Start playing a song. Its lyrics will appear here."
                                        else -> "Lyrics will appear here when available."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                                )
                                if (!hasAccess) Button(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }) { Text("Enable notification access") }
                                if (player.title.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                loading = true
                                                lyricsMessage = "Searching LRCLIB…"
                                                error = null
                                                runCatching { fetchLyrics(player.title, player.artist, player.album) }
                                                    .onSuccess { found ->
                                                        lyrics = found
                                                        lyricsMessage = if (found.isEmpty()) "No synced lyrics found. Try another version or import an LRC file." else "Synced lyrics"
                                                    }
                                                    .onFailure {
                                                        lyricsMessage = "Lyrics search failed. Check your internet connection."
                                                        error = it.message
                                                    }
                                                loading = false
                                            }
                                        },
                                        enabled = !loading
                                    ) { Text(if (loading) "Searching…" else "Search Lyrics") }
                                }
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import .LRC file") }
                                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(lyrics) { index, line ->
                                Text(
                                    line.text, modifier = Modifier.fillMaxWidth(),
                                    fontSize = if (index == activeIndex) 27.sp else 20.sp,
                                    lineHeight = if (index == activeIndex) 35.sp else 28.sp,
                                    fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        index == activeIndex -> lyricColor
                                        index < activeIndex -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                    }, textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            playbackControlsVisible = true
                            playbackControlsReset++
                            playbackMode = true
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            setPlaybackUi(true)
                        },
                        enabled = lyrics.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("▶  PLAYBACK", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                }
                }
            }
            if (showLibrary) {
                AlertDialog(
                    onDismissRequest = { showLibrary = false },
                    title = { Text("Downloaded lyrics") },
                    text = {
                        if (libraryItems.isEmpty()) {
                            Text("Your offline lyrics library is empty. Download lyrics while a song is playing.")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                itemsIndexed(
                                    libraryItems,
                                    key = { _, item -> item.fileUri ?: "${item.title}|${item.artist}" }
                                ) { _, item ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.title, fontWeight = FontWeight.SemiBold)
                                            Text(item.artist.ifBlank { "Unknown artist" }, fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = {
                                            lyrics = parseLrc(item.lrc)
                                            player = player.copy(title = item.title, artist = item.artist, album = item.album)
                                            positionNow = 0L
                                            isSaved = true
                                            lyricsMessage = "Loaded from offline library"
                                            showLibrary = false
                                        }) { Text("LOAD") }
                                        TextButton(onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { publicLibrary.delete(item) }
                                                libraryItems = withContext(Dispatchers.IO) { publicLibrary.all() }
                                                if (player.title == item.title && player.artist == item.artist) isSaved = false
                                            }
                                        }) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showLibrary = false }) { Text("CLOSE") } }
                )
            }

            if (showSettings) {
                AlertDialog(
                    onDismissRequest = { showSettings = false },
                    title = { Text("Settings") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("LYRICS SYNCHRONISATION", color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(
                                when {
                                    syncOffsetMs > 0 -> "Lyrics delayed by ${syncOffsetMs} ms"
                                    syncOffsetMs < 0 -> "Lyrics advanced by ${-syncOffsetMs} ms"
                                    else -> "No timing adjustment"
                                },
                                fontSize = 14.sp
                            )
                            Slider(
                                value = syncOffsetMs.toFloat(),
                                onValueChange = {
                                    syncOffsetMs = it.roundToInt()
                                    settingsPrefs.edit().putInt("lyrics_sync_offset_ms", syncOffsetMs).apply()
                                },
                                valueRange = -5000f..5000f,
                                steps = 99
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("−5.0 s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    syncOffsetMs = 0
                                    settingsPrefs.edit().putInt("lyrics_sync_offset_ms", 0).apply()
                                }) { Text("RESET") }
                                Text("+5.0 s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                            Text("PLAYBACK APPEARANCE", color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("Lyrics size: ${playbackFontSize.roundToInt()} sp", fontSize = 13.sp)
                            Slider(value = playbackFontSize, onValueChange = {
                                playbackFontSize = it
                                settingsPrefs.edit().putFloat("playback_font_size", it).apply()
                            }, valueRange = 30f..64f)
                            Text("Line spacing: ${playbackLineSpacing.roundToInt()} dp", fontSize = 13.sp)
                            Slider(value = playbackLineSpacing, onValueChange = {
                                playbackLineSpacing = it
                                settingsPrefs.edit().putFloat("playback_line_spacing", it).apply()
                            }, valueRange = 8f..40f)
                            Text("Vertical position: ${playbackVerticalPosition.roundToInt()} dp", fontSize = 13.sp)
                            Slider(value = playbackVerticalPosition, onValueChange = {
                                playbackVerticalPosition = it
                                settingsPrefs.edit().putFloat("playback_vertical_position", it).apply()
                            }, valueRange = -100f..100f)
                            Text("Font style", fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Default", "Serif", "Monospace").forEach { style ->
                                    FilterChip(selected = playbackFontStyle == style, onClick = {
                                        playbackFontStyle = style
                                        settingsPrefs.edit().putString("playback_font_style", style).apply()
                                    }, label = { Text(style, fontSize = 11.sp) })
                                }
                            }
                            HorizontalDivider()
                            Text("VISUALIZER CUSTOMIZATION", color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("Style", fontSize = 13.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf("Bars", "Waveform", "Mirrored Bars", "Circular").chunked(2).forEach { rowItems ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rowItems.forEach { style ->
                                            FilterChip(selected = visualizerStyle == style, onClick = {
                                                visualizerStyle = style
                                                settingsPrefs.edit().putString("visualizer_style", style).apply()
                                            }, label = { Text(style, fontSize = 11.sp) })
                                        }
                                    }
                                }
                            }
                            Text("Height: ${visualizerHeight.roundToInt()}%", fontSize = 13.sp)
                            Slider(value = visualizerHeight, onValueChange = {
                                visualizerHeight = it
                                settingsPrefs.edit().putFloat("visualizer_height", it).apply()
                            }, valueRange = 20f..80f)
                            Text("Sensitivity: ${String.format(Locale.ROOT, "%.1f", visualizerSensitivity)}×", fontSize = 13.sp)
                            Slider(value = visualizerSensitivity, onValueChange = {
                                visualizerSensitivity = it
                                settingsPrefs.edit().putFloat("visualizer_sensitivity", it).apply()
                            }, valueRange = 0.5f..2f)
                            Text("Colour mode", fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Single", "Spectrum", "Gradient").forEach { mode ->
                                    FilterChip(selected = visualizerColorMode == mode, onClick = {
                                        visualizerColorMode = mode
                                        multicolorVisualizer = mode == "Spectrum"
                                        settingsPrefs.edit()
                                            .putString("visualizer_color_mode", mode)
                                            .putBoolean("visualizer_multicolor", multicolorVisualizer).apply()
                                    }, label = { Text(mode, fontSize = 11.sp) })
                                }
                            }
                            Text("Single colour follows the selected lyrics colour. Spectrum reacts with changing intensity; Gradient uses a fixed colour spectrum.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            Text("LYRICS TEXT COLOUR", color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                lyricColorOptions.forEach { (name, value) ->
                                    FilterChip(
                                        selected = lyricColorValue == value,
                                        onClick = {
                                            lyricColorValue = value
                                            settingsPrefs.edit().putLong("lyrics_color", value).apply()
                                        },
                                        label = { Text(name) },
                                        leadingIcon = {
                                            Surface(modifier = Modifier.size(14.dp), shape = CircleShape, color = Color(value)) {}
                                        }
                                    )
                                }
                            }
                            Text("The selected colour is used for the active lyric and, in matching mode, the visualizer.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = {
                                playbackFontSize = 46f
                                playbackLineSpacing = 22f
                                playbackVerticalPosition = 0f
                                playbackFontStyle = "Default"
                                visualizerStyle = "Bars"
                                visualizerHeight = 46f
                                visualizerSensitivity = 1f
                                visualizerColorMode = "Single"
                                multicolorVisualizer = false
                                settingsPrefs.edit()
                                    .putFloat("playback_font_size", 46f)
                                    .putFloat("playback_line_spacing", 22f)
                                    .putFloat("playback_vertical_position", 0f)
                                    .putString("playback_font_style", "Default")
                                    .putString("visualizer_style", "Bars")
                                    .putFloat("visualizer_height", 46f)
                                    .putFloat("visualizer_sensitivity", 1f)
                                    .putString("visualizer_color_mode", "Single")
                                    .putBoolean("visualizer_multicolor", false).apply()
                            }) { Text("RESET APPEARANCE & VISUALIZER") }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showSettings = false }) { Text("DONE") } }
                )
            }
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(ComponentName(this, MediaSessionListener::class.java).flattenToString())
    }
}

private fun parseLrc(source: String): List<LyricLine> {
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    return source.lineSequence().flatMap { line ->
        val matches = regex.findAll(line).toList()
        val lyric = regex.replace(line, "").trim()
        if (lyric.isBlank()) emptySequence()
        else matches.asSequence().map { match ->
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fractionText = match.groupValues[3]
            val fractionMs = when (fractionText.length) {
                1 -> fractionText.toLong() * 100
                2 -> fractionText.toLong() * 10
                3 -> fractionText.toLong()
                else -> 0L
            }
            LyricLine((minutes * 60 + seconds) * 1000 + fractionMs, lyric)
        }
    }.sortedBy { it.timeMs }.toList()
}

private suspend fun fetchLyrics(title: String, artist: String, album: String): List<LyricLine> = withContext(Dispatchers.IO) {
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    fun requestJson(path: String): String? {
        val connection = (URL("https://lrclib.net$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "SyncLyrics/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // First try LRCLIB's exact-match endpoint.
    val exactQuery = buildList {
        add("track_name=${encode(title)}")
        if (artist.isNotBlank()) add("artist_name=${encode(artist)}")
        if (album.isNotBlank()) add("album_name=${encode(album)}")
    }.joinToString("&")
    val exactJson = requestJson("/api/get?$exactQuery")
    if (exactJson != null) {
        val synced = JSONObject(exactJson).optString("syncedLyrics")
        val parsed = if (synced.isNotBlank()) parseLrc(synced) else emptyList()
        if (parsed.isNotEmpty()) return@withContext parsed
    }

    // Exact matches are often absent because media players expose slightly different
    // title/artist strings. Search the catalogue and select the closest synced result.
    val searchQuery = buildList {
        add("track_name=${encode(title)}")
        if (artist.isNotBlank()) add("artist_name=${encode(artist)}")
    }.joinToString("&")
    val searchJson = requestJson("/api/search?$searchQuery") ?: return@withContext emptyList()
    val results = org.json.JSONArray(searchJson)
    val normalizedTitle = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    val normalizedArtist = artist.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    val candidates = (0 until results.length()).mapNotNull { index ->
        val item = results.optJSONObject(index) ?: return@mapNotNull null
        val synced = item.optString("syncedLyrics")
        if (synced.isBlank()) return@mapNotNull null
        val candidateTitle = item.optString("trackName").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val candidateArtist = item.optString("artistName").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val score = (if (candidateTitle == normalizedTitle) 4 else if (candidateTitle.contains(normalizedTitle) || normalizedTitle.contains(candidateTitle)) 2 else 0) +
                (if (normalizedArtist.isNotBlank() && candidateArtist == normalizedArtist) 4 else if (normalizedArtist.isNotBlank() && (candidateArtist.contains(normalizedArtist) || normalizedArtist.contains(candidateArtist))) 2 else 0)
        Triple(score, index, synced)
    }.sortedByDescending { it.first }
    candidates.firstOrNull()?.third?.let { parseLrc(it) } ?: emptyList()
}

private fun serializeLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
    val totalCentiseconds = line.timeMs.coerceAtLeast(0L) / 10L
    val minutes = totalCentiseconds / 6000

    val seconds = (totalCentiseconds / 100) % 60
    val centiseconds = totalCentiseconds % 100
    "[%02d:%02d.%02d]%s".format(Locale.ROOT, minutes, seconds, centiseconds, line.text)
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}


