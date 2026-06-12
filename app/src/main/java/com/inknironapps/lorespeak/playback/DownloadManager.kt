package com.inknironapps.lorespeak.playback

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Progress of the active book download (full pre-render to disk). */
data class DownloadState(
    val bookId: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Drives "Download" (full-book pre-render). One download at a time, run by a foreground
 * [DownloadService] so it survives backgrounding. The cache is resumable, so cancelling and
 * restarting picks up where it left off.
 */
object DownloadManager {

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun start(context: Context, bookId: String) {
        val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_BOOK_ID, bookId)
        context.startForegroundService(intent)
    }

    fun cancel(context: Context) {
        context.stopService(Intent(context, DownloadService::class.java))
        _state.value = _state.value.copy(running = false)
    }

    internal fun publish(state: DownloadState) {
        _state.value = state
    }

    const val EXTRA_BOOK_ID = "bookId"
}
