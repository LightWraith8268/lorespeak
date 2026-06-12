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
 * Foreground service that drains the [DownloadManager] queue, rendering each book to the on-disk
 * audio cache one at a time. Shows aggregate progress in a notification and is resumable — cached
 * sentences are skipped and cancelled books are left where they stopped.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", 0, 1))
        if (loop?.isActive != true) {
            loop = scope.launch {
                drainQueue()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun drainQueue() {
        while (scope.isActive) {
            val next = DownloadManager.nextQueued() ?: break
            DownloadManager.update(next.bookId) { it.copy(status = DownloadStatus.RUNNING) }
            val result = renderBook(next.bookId)
            DownloadManager.update(next.bookId) {
                if (DownloadManager.isCancelled(next.bookId)) {
                    it.copy(status = DownloadStatus.CANCELLED)
                } else {
                    it.copy(status = result)
                }
            }
        }
    }

    private suspend fun renderBook(bookId: String): DownloadStatus {
        val store = AppGraph.store(this)
        val record = store.get(bookId) ?: return DownloadStatus.FAILED
        val cache = AppGraph.cache(this)
        val tts = AppGraph.tts(this)
        val book = runCatching { Importer.parseStored(record) }.getOrNull() ?: return DownloadStatus.FAILED

        val sentences = book.chapters.flatMap { it.sentences }
        val total = sentences.size
        val voiceId = record.voiceId
        DownloadManager.update(bookId) { it.copy(done = 0, total = total) }

        sentences.forEachIndexed { index, text ->
            if (!scope.isActive || DownloadManager.isCancelled(bookId)) return DownloadStatus.CANCELLED
            if (!cache.has(bookId, voiceId, index)) {
                val audio = runCatching { tts.generate(text, voiceId, 1.0f) }.getOrNull()
                if (audio != null && audio.samples.isNotEmpty()) {
                    cache.write(bookId, voiceId, index, trimSilence(audio.samples, tts.sampleRate))
                }
            }
            val done = index + 1
            if (done == total || index % 5 == 0) {
                DownloadManager.update(bookId) { it.copy(done = done) }
                notify(buildNotification(record.title, done, total))
            }
        }
        DownloadManager.update(bookId) { it.copy(done = total, total = total) }
        return DownloadStatus.COMPLETED
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val remaining = DownloadManager.items.value.count { it.active }
        val title = if (remaining > 1) "Downloading $remaining audiobooks" else "Downloading audiobook"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, false)
            .build()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2001
    }
}
