package com.littlebird.synclyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Captures eligible playback audio for live visualisation.
 * Samples are analysed in memory only; no audio is saved or transmitted.
 */
class AudioCaptureService : Service() {
    companion object {
        const val ACTION_STOP = "com.littlebird.synclyrics.STOP_AUDIO_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "synclyrics_audio_visualizer"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "SyncLyricsCapture"
        private const val ACTION_LEVEL = "com.littlebird.synclyrics.VISUALIZER_LEVEL"
        private const val BAND_COUNT = 28
    }

    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 29 || running) return START_NOT_STICKY

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SyncLyrics visualizer active")
            .setContentText("Analysing eligible music audio on this device")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: throw IllegalStateException("Missing audio-capture permission result")

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, android.os.Handler(mainLooper))
            startCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start playback capture", e)
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val activeProjection = projection ?: return
        val config = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val sampleRate = 44100
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("Audio capture is not supported")
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("Could not initialise audio capture")
        }

        recorder = audioRecord
        running = true
        audioRecord.startRecording()
        captureThread = thread(name = "SyncLyricsAudioAnalysis", isDaemon = true) {
            val samples = ShortArray(1024)
            val smoothed = FloatArray(BAND_COUNT)
            val frequencies = FloatArray(BAND_COUNT) { index ->
                val fraction = index.toFloat() / (BAND_COUNT - 1)
                (70.0 * (7000.0 / 70.0).pow(fraction)).toFloat()
            }
            val coefficients = FloatArray(BAND_COUNT) { index ->
                (2.0 * cos(2.0 * PI * frequencies[index] / sampleRate)).toFloat()
            }

            while (running) {
                val count = try {
                    audioRecord.read(samples, 0, samples.size)
                } catch (_: Exception) { -1 }

                if (count > 0) {
                    for (band in 0 until BAND_COUNT) {
                        val coeff = coefficients[band]
                        var q1 = 0f
                        var q2 = 0f
                        for (i in 0 until count) {
                            val sample = samples[i] / 32768f
                            val q0 = sample + coeff * q1 - q2
                            q2 = q1
                            q1 = q0
                        }
                        val power = (q1 * q1 + q2 * q2 - coeff * q1 * q2).coerceAtLeast(0f)
                        val magnitude = (2f * sqrt(power) / count).coerceAtLeast(0f)
                        val target = if (magnitude < 0.006f) 0f
                            else ((magnitude * 7.5f).coerceIn(0f, 1f)).pow(0.72f)

                        // Fast attack, slower release: movement stays fluid between beats.
                        val smoothing = if (target > smoothed[band]) 0.48f else 0.13f
                        smoothed[band] += (target - smoothed[band]) * smoothing
                    }
                    sendBroadcast(
                        Intent(ACTION_LEVEL)
                            .setPackage(packageName)
                            .putExtra("bands", smoothed.copyOf())
                    )
                } else if (count < 0) {
                    Log.w(TAG, "AudioRecord read failed: $count")
                    break
                }
            }
            sendBroadcast(
                Intent(ACTION_LEVEL).setPackage(packageName).putExtra("bands", FloatArray(BAND_COUNT))
            )
        }
    }

    private fun stopCapture() {
        running = false
        val activeRecorder = recorder
        recorder = null
        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
        val activeProjection = projection
        projection = null
        runCatching { activeProjection?.stop() }
        captureThread = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        sendBroadcast(
            Intent(ACTION_LEVEL).setPackage(packageName).putExtra("bands", FloatArray(BAND_COUNT))
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Music visualizer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
