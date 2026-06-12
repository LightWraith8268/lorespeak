package com.inknironapps.lorespeak.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.inknironapps.lorespeak.AppGraph
import com.inknironapps.lorespeak.R
import com.inknironapps.lorespeak.data.Importer
import com.inknironapps.lorespeak.tts.trimSilence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that renders an entire book to the on-disk audio cache (the "Download"). Shows
 * a progress notification, reports to [DownloadManager], and is resumable — already-cached sentences
 * are skipped, so it continues across sessions.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bookId = intent?.getStringExtra(DownloadManager.EXTRA_BOOK_ID)
        if (bookId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing…", 0, 1))

        job?.cancel()
        job = scope.launch {
            render(bookId)
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun render(bookId: String) {
        val store = AppGraph.store(this)
        val record = store.get(bookId) ?: return
        val cache = AppGraph.cache(this)
        val tts = AppGraph.tts(this)
        val book = runCatching { Importer.parseStored(record) }.getOrNull() ?: return

        val sentences = book.chapters.flatMap { it.sentences }
        val total = sentences.size
        val voiceId = record.voiceId
        DownloadManager.publish(DownloadState(bookId, 0, total, running = true))

        sentences.forEachIndexed { index, text ->
            if (!scope.isActive) return
            if (!cache.has(bookId, voiceId, index)) {
                val audio = runCatching { tts.generate(text, voiceId, 1.0f) }.getOrNull()
                if (audio != null && audio.samples.isNotEmpty()) {
                    cache.write(bookId, voiceId, index, trimSilence(audio.samples, tts.sampleRate))
                }
            }
            val done = index + 1
            if (done == total || index % 5 == 0) {
                DownloadManager.publish(DownloadState(bookId, done, total, running = done < total))
                notify(buildNotification(record.title, done, total))
            }
        }
        DownloadManager.publish(DownloadState(bookId, total, total, running = false))
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading audiobook")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, false)
            .build()

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2001
    }
}
