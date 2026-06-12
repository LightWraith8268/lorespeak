package com.inknironapps.lorespeak.playback

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

data class DownloadItem(
    val bookId: String,
    val title: String,
    val done: Int = 0,
    val total: Int = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val active: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
}

data class DownloadRequest(val bookId: String, val title: String)

/**
 * Queue of full-book pre-render downloads, processed one at a time by [DownloadService] (they share
 * a single ONNX session, so sequential is correct). Holds the whole queue so a downloads screen can
 * show every item; supports bulk enqueue and per-item / all cancellation. The on-disk cache is
 * resumable, so cancelled or interrupted downloads continue from where they stopped.
 */
object DownloadManager {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    @Synchronized
    fun enqueue(context: Context, requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        val current = _items.value.toMutableList()
        for (request in requests) {
            val existing = current.firstOrNull { it.bookId == request.bookId }
            if (existing != null && existing.active) continue
            current.removeAll { it.bookId == request.bookId }
            current += DownloadItem(request.bookId, request.title, status = DownloadStatus.QUEUED)
        }
        _items.value = current
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    @Synchronized
    fun cancel(bookId: String) {
        _items.value = _items.value.map {
            if (it.bookId == bookId && it.active) it.copy(status = DownloadStatus.CANCELLED) else it
        }
    }

    @Synchronized
    fun cancelAll() {
        _items.value = _items.value.map {
            if (it.active) it.copy(status = DownloadStatus.CANCELLED) else it
        }
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter { it.active }
    }

    fun itemFor(bookId: String): DownloadItem? = _items.value.firstOrNull { it.bookId == bookId }

    // --- called by DownloadService ---

    @Synchronized
    internal fun nextQueued(): DownloadItem? =
        _items.value.firstOrNull { it.status == DownloadStatus.QUEUED }

    internal fun isCancelled(bookId: String): Boolean =
        _items.value.firstOrNull { it.bookId == bookId }?.status == DownloadStatus.CANCELLED

    @Synchronized
    internal fun update(bookId: String, transform: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.bookId == bookId) transform(it) else it }
    }
}
