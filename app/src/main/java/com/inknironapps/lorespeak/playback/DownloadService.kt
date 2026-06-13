package com.inknironapps.lorespeak.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.inknironapps.lorespeak.AppGraph
import com.inknironapps.lorespeak.R
import com.inknironapps.lorespeak.data.Importer
import com.inknironapps.lorespeak.tts.KokoroTts
import com.inknironapps.lorespeak.tts.trimSilence
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Foreground service that drains the [DownloadManager] queue, rendering each book to the on-disk
 * audio cache one at a time. Shows aggregate progress in a notification and is resumable — cached
 * sentences are skipped and cancelled books are left where they stopped.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", 0, 1))
        if (loop?.isActive != true) {
            acquireWakeLock()
            loop = scope.launch {
                drainQueue()
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Keeps the CPU running with the screen off so synthesis doesn't pause when backgrounded.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lorespeak:download").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // 8 h safety cap
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

    private suspend fun renderBook(bookId: String): DownloadStatus = coroutineScope {
        val service = this@DownloadService
        val store = AppGraph.store(service)
        val record = store.get(bookId) ?: return@coroutineScope DownloadStatus.FAILED
        val cache = AppGraph.cache(service)
        val book = runCatching { Importer.parseStored(record) }.getOrNull()
            ?: return@coroutineScope DownloadStatus.FAILED

        val sentences = book.chapters.flatMap { it.sentences }
        val total = sentences.size
        val voiceId = AppGraph.settings(service).defaultVoiceId // single global voice
        DownloadManager.update(bookId) {
            it.copy(done = cache.renderedCount(bookId, voiceId).coerceAtMost(total), total = total)
        }

        // Render in parallel across a small pool of sessions to use more of the SoC's cores. The
        // cache stays per-sentence, so already-downloaded books remain valid.
        val pool = List(POOL_SIZE) { KokoroTts.create(service, numThreads = THREADS_PER_SESSION) }
        val nextIndex = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val cancelled = AtomicInteger(0)

        try {
            pool.map { session ->
                launch {
                    while (isActive) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= total) break
                        if (DownloadManager.isCancelled(bookId)) {
                            cancelled.set(1)
                            break
                        }
                        if (!cache.has(bookId, voiceId, index)) {
                            val audio = runCatching { session.generate(sentences[index], voiceId, 1.0f) }.getOrNull()
                            if (audio != null && audio.samples.isNotEmpty()) {
                                cache.write(bookId, voiceId, index, trimSilence(audio.samples, session.sampleRate))
                            }
                        }
                        val done = completed.incrementAndGet()
                        if (done == total || done % 10 == 0) {
                            DownloadManager.update(bookId) { it.copy(done = done) }
                            notify(buildNotification(record.title, done, total))
                        }
                    }
                }
            }.joinAll()
        } finally {
            pool.forEach { runCatching { it.release() } }
        }

        if (cancelled.get() == 1) {
            DownloadStatus.CANCELLED
        } else {
            DownloadManager.update(bookId) { it.copy(done = total, total = total) }
            DownloadStatus.COMPLETED
        }
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
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2001

        // Parallel render sessions and threads each. 2 x 4 ≈ a full 8-core SoC, ~1.3-1.5x throughput.
        private const val POOL_SIZE = 2
        private const val THREADS_PER_SESSION = 4
    }
}
