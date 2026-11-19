package com.littlebird.synclyrics

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

class MediaSessionListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaSessionListener"
        private const val ACTION_MEDIA_UPDATE = "com.littlebird.synclyrics.MEDIA_UPDATE"
        private const val ACTION_REFRESH_SESSIONS = "com.littlebird.synclyrics.REFRESH_SESSIONS"
    }

    private var sessionManager: MediaSessionManager? = null
    private val activeControllers = mutableMapOf<MediaController, MediaControllerCallback>()

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REFRESH_SESSIONS) {
                updateActiveSessions()
            }
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateControllers(controllers)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val filter = IntentFilter(ACTION_REFRESH_SESSIONS)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(refreshReceiver, filter)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val component = ComponentName(this, MediaSessionListener::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            updateActiveSessions()
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification listener permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting media session listener", e)
        }
    }

    override fun onListenerDisconnected() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting media session listener", e)
        }
        clearControllers()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        clearControllers()
        super.onDestroy()
    }

    private fun updateActiveSessions() {
        try {
            val component = ComponentName(this, MediaSessionListener::class.java)
            val controllers = sessionManager?.getActiveSessions(component)
            updateControllers(controllers)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting active sessions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active sessions", e)
        }
    }

    private fun updateControllers(controllers: List<MediaController>?) {
        val newControllers = controllers.orEmpty()

        val iterator = activeControllers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!newControllers.contains(entry.key)) {
                entry.key.unregisterCallback(entry.value)
                iterator.remove()
            }
        }

        for (controller in newControllers) {
            if (!activeControllers.containsKey(controller)) {
                val callback = MediaControllerCallback(controller)
                controller.registerCallback(callback)
                activeControllers[controller] = callback
                callback.broadcastState()
            }
        }

        broadcastBestControllerState(newControllers)
    }

    private fun clearControllers() {
        for ((controller, callback) in activeControllers) {
            controller.unregisterCallback(callback)
        }
        activeControllers.clear()
        broadcastEmptyState()
    }

    private fun broadcastBestControllerState(controllers: List<MediaController>) {
        val best = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (best != null) {
            activeControllers[best]?.broadcastState() ?: broadcastEmptyState()
        } else {
            broadcastEmptyState()
        }
    }

    private fun broadcastEmptyState() {
        val intent = Intent(ACTION_MEDIA_UPDATE).apply {
            setPackage(packageName)
            putExtra("title", "")
            putExtra("artist", "")
            putExtra("album", "")
            putExtra("package", "")
            putExtra("position", 0L)
            putExtra("duration", 0L)
            putExtra("speed", 1f)
            putExtra("playing", false)
        }
        sendBroadcast(intent)
    }

    private inner class MediaControllerCallback(private val controller: MediaController) : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            broadcastState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            broadcastState()
        }

        fun broadcastState() {
            val metadata = controller.metadata
            val playbackState = controller.playbackState

            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: ""
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L

            val state = playbackState?.state ?: PlaybackState.STATE_NONE
            val playing = state == PlaybackState.STATE_PLAYING
            val position = playbackState?.position?.coerceAtLeast(0L) ?: 0L
            val speed = playbackState?.playbackSpeed?.takeIf { it > 0f } ?: 1f
            val pkg = controller.packageName ?: ""

            val intent = Intent(ACTION_MEDIA_UPDATE).apply {
                setPackage(packageName)
                putExtra("title", title)
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("package", pkg)
                putExtra("position", position)
                putExtra("duration", duration)
                putExtra("speed", speed)
                putExtra("playing", playing)
            }
            sendBroadcast(intent)
        }
    }
}
