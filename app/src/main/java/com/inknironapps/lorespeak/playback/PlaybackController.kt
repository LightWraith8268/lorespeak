package com.inknironapps.lorespeak.playback

import android.content.Context
import android.content.Intent
import com.inknironapps.lorespeak.AppGraph
import com.inknironapps.lorespeak.data.BookRecord
import com.inknironapps.lorespeak.data.Importer
import com.inknironapps.lorespeak.data.LibraryStore
import com.inknironapps.lorespeak.reader.ReaderEngine

/**
 * Process-scoped owner of the active reading engine. The engine lives here (not in a composable) so
 * playback survives leaving the reader screen and the app being backgrounded; the foreground
 * [PlaybackService] keeps the process alive while audio plays.
 */
object PlaybackController {

    @Volatile
    var engine: ReaderEngine? = null
        private set

    @Volatile
    var currentBookId: String? = null
        private set

    /** Loads (or returns the already-loaded) engine for [record], seeked to its saved position. */
    suspend fun openBook(
        context: Context,
        record: BookRecord,
        store: LibraryStore,
        speed: Float,
    ): ReaderEngine {
        engine?.let { existing ->
            if (currentBookId == record.id) return existing
        }
        shutdown(context)

        val tts = AppGraph.tts(context)
        val cache = AppGraph.cache(context)
        val voiceId = AppGraph.settings(context).defaultVoiceId // single global voice
        val book = Importer.parseStored(record)
        val eng = ReaderEngine(tts, cache, record.id) { chapterIndex, sentenceIndex, globalSentence ->
            store.saveProgress(record.id, chapterIndex, sentenceIndex, globalSentence)
        }
        eng.load(book, record.chapterIndex, record.sentenceIndex, voiceId, speed)
        engine = eng
        currentBookId = record.id
        return eng
    }

    /** Stops and releases everything (used when switching books or finishing). */
    fun shutdown(context: Context) {
        engine?.release()
        engine = null
        currentBookId = null
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
