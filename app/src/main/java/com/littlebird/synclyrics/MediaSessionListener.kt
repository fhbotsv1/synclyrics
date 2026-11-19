package com.littlebird.synclyrics

import android.content.ComponentName
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaSessionListener : NotificationListenerService() {
    private val controllers = mutableMapOf<MediaSession.Token, MediaController>()
    private var selected: MediaController? = null
    private lateinit var sessionManager: android.media.session.MediaSessionManager

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshSessions() }
    }
    private val sessionsChanged = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener {
        refreshSessions()
    }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(controller: MediaController, metadata: MediaMetadata?) { publishIfSelected(controller) }
        override fun onPlaybackStateChanged(controller: MediaController, state: PlaybackState?) { publishIfSelected(controller) }
        override fun onSessionDestroyed(controller: MediaController) { refreshSessions() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(android.media.session.MediaSessionManager::class.java)
        val refreshFilter = IntentFilter("com.littlebird.synclyrics.REFRESH_SESSIONS")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(refreshReceiver, refreshFilter) }
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChanged, ComponentName(this, MediaSessionListener::class.java)
            )
        }
        refreshSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { refreshSessions() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refreshSessions() }

    private fun refreshSessions() {
        val active = runCatching {
            sessionManager.getActiveSessions(ComponentName(this, MediaSessionListener::class.java))
        }.getOrDefault(emptyList())
        val activeTokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in activeTokens }.forEach { token ->
            controllers.remove(token)?.unregisterCallback(callback)
        }
        active.forEach { controller ->
            if (!controllers.containsKey(controller.sessionToken)) {
                controllers[controller.sessionToken] = controller
                controller.registerCallback(callback)
            }
        }
        selected = active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: active.firstOrNull { it.metadata != null }
        publish(selected)
    }

    private fun publishIfSelected(controller: MediaController) {
        if (selected?.sessionToken == controller.sessionToken) publish(controller)
    }

    private fun publish(controller: MediaController?) {
        val metadata = controller?.metadata
        val state = controller?.playbackState
        val intent = Intent("com.littlebird.synclyrics.MEDIA_UPDATE").setPackage(packageName).apply {
            putExtra("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "")
            putExtra("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "")
            putExtra("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            putExtra("package", controller?.packageName.orEmpty())
            putExtra("position", state?.position?.coerceAtLeast(0L) ?: 0L)
            putExtra("duration", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            putExtra("speed", state?.playbackSpeed ?: 1f)
            putExtra("playing", state?.state == PlaybackState.STATE_PLAYING)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        if (::sessionManager.isInitialized) runCatching {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged)
        }
        controllers.values.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        super.onDestroy()
    }
}
