package com.littlebird.synclyrics

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.os.Build
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private const val ACTION_MEDIA_UPDATE = "com.littlebird.synclyrics.MEDIA_UPDATE"

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
        sendBroadcast(Intent("com.littlebird.synclyrics.REFRESH_SESSIONS").setPackage(packageName))
    }

    override fun onResume() {
        super.onResume()
        accessRefresh++
        sendBroadcast(Intent("com.littlebird.synclyrics.REFRESH_SESSIONS").setPackage(packageName))
    }

    override fun onStop() {
        runCatching { unregisterReceiver(mediaReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        onPlayerUpdate = null
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
        var positionNow by remember { mutableLongStateOf(0L) }
        var hasAccess by remember { mutableStateOf(hasNotificationAccess()) }
        LaunchedEffect(accessRefresh) { hasAccess = hasNotificationAccess() }
        val listState = rememberLazyListState()

        fun applyPlayer(next: PlayerInfo) {
            val changedSong = next.title != player.title || next.artist != player.artist
            player = next
            positionNow = next.position
            if (changedSong) {
                lyrics = emptyList()
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
                    else { lyrics = parsed; lyricsMessage = "Lyrics loaded from file"; error = null }
                }.onFailure { error = it.message ?: "Could not open lyrics file." }
            }
        }

        val songKey = "${player.title.lowercase(Locale.ROOT)}|${player.artist.lowercase(Locale.ROOT)}"
        LaunchedEffect(songKey) {
            if (player.title.isNotBlank()) {
                loading = true
                lyricsMessage = "Looking up lyrics…"
                val result = runCatching { fetchLyrics(player.title, player.artist, player.album) }
                result.onSuccess { found ->
                    lyrics = found
                    lyricsMessage = if (found.isEmpty()) "No synced lyrics found. Import an LRC file." else "Synced lyrics"
                }.onFailure {
                    lyricsMessage = "Could not retrieve lyrics. Check your connection or import an LRC file."
                }
                loading = false
            } else {
                lyrics = emptyList()
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

        val activeIndex = lyrics.indexOfLast { it.timeMs <= positionNow }.coerceAtLeast(0)
        LaunchedEffect(activeIndex, lyrics.size) {
            if (lyrics.isNotEmpty()) listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }

        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFFB8F36B), onPrimary = Color(0xFF17210A),
            secondary = Color(0xFFB8F36B), background = Color(0xFF10120F),
            surface = Color(0xFF191D18), surfaceVariant = Color(0xFF252B23)
        )) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("SYNC", fontSize = 12.sp, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("Lyrics", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                        Surface(shape = CircleShape, color = if (hasAccess) Color(0xFF24351C) else Color(0xFF38291B)) {
                            Text(if (hasAccess) "● ACCESS ENABLED" else "● SETUP REQUIRED",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = if (hasAccess) Color(0xFFB8F36B) else Color(0xFFFFC078), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (player.title.isBlank()) "NOTHING PLAYING" else "NOW PLAYING",
                                color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                            Text(player.title.ifBlank { "Play music in another app" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(player.artist.ifBlank { "Spotify • YouTube Music • VLC and more" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (player.packageName.isNotBlank()) Text(player.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (player.duration > 0) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { (positionNow.toFloat() / player.duration).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(formatTime(positionNow), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatTime(player.duration), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("LYRICS", fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(lyricsMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                    Spacer(Modifier.height(8.dp))
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
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import .LRC file") }
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
                                        index == activeIndex -> MaterialTheme.colorScheme.primary
                                        index < activeIndex -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                    }, textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("Open LRC") }
                        OutlinedButton(onClick = { hasAccess = hasNotificationAccess(); context.sendBroadcast(Intent("com.littlebird.synclyrics.REFRESH_SESSIONS").setPackage(context.packageName)) }, modifier = Modifier.weight(1f)) { Text("Refresh player") }
                    }
                    Text("Playback is detected through Android media sessions. SyncLyrics does not record audio.",
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
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
    val query = listOf("track_name" to title, "artist_name" to artist, "album_name" to album)
        .filter { it.second.isNotBlank() }
        .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
    val connection = (URL("https://lrclib.net/api/get?$query").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 10000
        setRequestProperty("User-Agent", "SyncLyrics/1.0 (Android)")
    }
    try {
        if (connection.responseCode !in 200..299) return@withContext emptyList()
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val synced = json.optString("syncedLyrics")
        if (synced.isNotBlank()) parseLrc(synced) else emptyList()
    } finally {
        connection.disconnect()
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
