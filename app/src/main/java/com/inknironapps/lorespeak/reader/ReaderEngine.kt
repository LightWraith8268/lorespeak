package com.inknironapps.lorespeak.reader

import com.inknironapps.lorespeak.parse.ParsedBook
import com.inknironapps.lorespeak.tts.AudioCache
import com.inknironapps.lorespeak.tts.AudioSink
import com.inknironapps.lorespeak.tts.KokoroTts
import com.inknironapps.lorespeak.tts.trimSilence
import com.inknironapps.lorespeak.tts.writeAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ReaderState(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val globalSentence: Int = 0,
    val totalSentences: Int = 0,
    val currentText: String = "",
    val playing: Boolean = false,
    val finished: Boolean = false,
    /** Characters of completed sentences before the current one, within the current chapter. */
    val chapterCharOffset: Int = 0,
) {
    val progress: Float
        get() = if (totalSentences <= 0) 0f else (globalSentence.toFloat() / totalSentences).coerceIn(0f, 1f)
}

/** Static per-chapter info for building the media timeline (one "track" per chapter). */
data class ChapterInfo(
    val title: String,
    val sentenceCount: Int,
    val charCount: Int,
)

/**
 * Streams a parsed book through Kokoro sentence-by-sentence and plays it gaplessly.
 *
 * A producer coroutine synthesizes one sentence ahead into a capacity-1 channel; a consumer writes
 * each buffer to the AudioTrack (blocking back-pressure) and reports progress as each sentence
 * begins. Pausing keeps the cursor on the current sentence — resuming re-synthesizes it from the
 * start, which is the standard audiobook resume behavior.
 */
class ReaderEngine(
    private val tts: KokoroTts,
    private val cache: AudioCache,
    private val bookId: String,
    private val onProgress: (chapterIndex: Int, sentenceIndex: Int, globalSentence: Int) -> Unit,
) {
    private data class Entry(
        val chapterIndex: Int,
        val sentenceIndex: Int,
        val chapterTitle: String,
        val text: String,
        val charOffsetInChapter: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flat: List<Entry> = emptyList()
    private var sink: AudioSink? = null
    private var playJob: Job? = null

    /** One entry per chapter, in order — drives the media notification's per-chapter timeline. */
    var chapters: List<ChapterInfo> = emptyList()
        private set

    /** Index into [flat] of the sentence currently playing / to resume from. */
    private var cursor = 0

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    var voiceId = 0
        private set

    @Volatile
    var speed = 1.25f
        set(value) {
            field = value
            sink?.speed = value
        }

    fun load(book: ParsedBook, startChapter: Int, startSentence: Int, voiceId: Int, speed: Float) {
        stopInternal()
        this.voiceId = voiceId
        this.speed = speed

        val list = ArrayList<Entry>()
        book.chapters.forEachIndexed { ci, chapter ->
            var charOffset = 0
            chapter.sentences.forEachIndexed { si, sentence ->
                list += Entry(ci, si, chapter.title, sentence, charOffset)
                charOffset += sentence.length
            }
        }
        flat = list
        chapters = book.chapters.map { chapter ->
            ChapterInfo(chapter.title, chapter.sentences.size, chapter.sentences.sumOf { it.length })
        }
        cursor = indexOf(startChapter, startSentence)

        val entry = flat.getOrNull(cursor)
        _state.value = ReaderState(
            bookTitle = book.title,
            totalSentences = flat.size,
            globalSentence = cursor,
            chapterIndex = entry?.chapterIndex ?: 0,
            chapterTitle = entry?.chapterTitle.orEmpty(),
            sentenceIndex = entry?.sentenceIndex ?: 0,
            currentText = entry?.text.orEmpty(),
            chapterCharOffset = entry?.charOffsetInChapter ?: 0,
            playing = false,
            finished = flat.isEmpty(),
        )
    }

    private fun indexOf(chapter: Int, sentence: Int): Int {
        if (flat.isEmpty()) return 0
        val exact = flat.indexOfFirst { it.chapterIndex == chapter && it.sentenceIndex == sentence }
        if (exact >= 0) return exact
        val chapterStart = flat.indexOfFirst { it.chapterIndex == chapter }
        return chapterStart.coerceAtLeast(0)
    }

    fun play() {
        if (_state.value.playing || flat.isEmpty()) return
        val audioSink = AudioSink(tts.sampleRate).also { it.speed = speed }
        sink = audioSink
        audioSink.play()
        _state.update { it.copy(playing = true, finished = false) }

        playJob = scope.launch {
            // Deep look-ahead: the producer sprints ahead during easy sentences and banks a reserve
            // so a few slow/long sentences never starve the track (the cause of mid-playback stalls).
            val channel = Channel<Pair<Int, FloatArray>>(capacity = LOOKAHEAD_SENTENCES)
            val producer = launch {
                var i = cursor
                while (isActive && i < flat.size) {
                    val samples = loadOrSynth(i)
                    if (samples != null && samples.isNotEmpty()) {
                        channel.send(i to samples)
                    }
                    i++
                }
                channel.close()
            }
            try {
                for ((index, samples) in channel) {
                    cursor = index
                    val entry = flat[index]
                    _state.update {
                        it.copy(
                            globalSentence = index,
                            chapterIndex = entry.chapterIndex,
                            chapterTitle = entry.chapterTitle,
                            sentenceIndex = entry.sentenceIndex,
                            currentText = entry.text,
                            chapterCharOffset = entry.charOffsetInChapter,
                        )
                    }
                    onProgress(entry.chapterIndex, entry.sentenceIndex, index)
                    audioSink.writeAll(samples)
                }
                cursor = flat.size.coerceAtLeast(1) - 1
                _state.update { it.copy(playing = false, finished = true) }
            } finally {
                producer.cancel()
                audioSink.release() // sink lifecycle stays on this coroutine — no cross-thread release
            }
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        sink?.pause() // stop audio immediately; the cancelled job's finally releases the track
        sink = null
        _state.update { it.copy(playing = false) }
        flat.getOrNull(cursor)?.let { onProgress(it.chapterIndex, it.sentenceIndex, cursor) }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun seekTo(globalIndex: Int) {
        val wasPlaying = _state.value.playing
        stopInternal()
        cursor = globalIndex.coerceIn(0, (flat.size - 1).coerceAtLeast(0))
        val entry = flat.getOrNull(cursor)
        _state.update {
            it.copy(
                globalSentence = cursor,
                chapterIndex = entry?.chapterIndex ?: 0,
                chapterTitle = entry?.chapterTitle.orEmpty(),
                sentenceIndex = entry?.sentenceIndex ?: 0,
                currentText = entry?.text.orEmpty(),
                chapterCharOffset = entry?.charOffsetInChapter ?: 0,
                finished = false,
            )
        }
        entry?.let { onProgress(it.chapterIndex, it.sentenceIndex, cursor) }
        if (wasPlaying) play()
    }

    fun skipSentence(delta: Int) = seekTo(cursor + delta)

    fun skipChapter(delta: Int) {
        val current = flat.getOrNull(cursor) ?: return
        val targetChapter = current.chapterIndex + delta
        val target = flat.indexOfFirst { it.chapterIndex == targetChapter }
        if (target >= 0) seekTo(target) else if (delta < 0) seekTo(0)
    }

    /** The sentences of one chapter, in order (for the on-screen paragraph view). */
    fun sentencesIn(chapterIndex: Int): List<String> =
        flat.asSequence().filter { it.chapterIndex == chapterIndex }.map { it.text }.toList()

    /** Jumps to a specific sentence within a chapter (tap-to-seek in the reader). */
    fun seekToChapterSentence(chapterIndex: Int, sentenceIndex: Int) {
        val index = flat.indexOfFirst {
            it.chapterIndex == chapterIndex && it.sentenceIndex == sentenceIndex
        }
        if (index >= 0) seekTo(index)
    }

    /** Jumps to a chapter and the sentence nearest [charOffset] within it (notification scrub). */
    fun seekToChapterChars(chapterIndex: Int, charOffset: Int) {
        val target = flat.indexOfLast {
            it.chapterIndex == chapterIndex && it.charOffsetInChapter <= charOffset
        }
        val fallback = flat.indexOfFirst { it.chapterIndex == chapterIndex }
        val index = if (target >= 0) target else fallback
        if (index >= 0) seekTo(index)
    }

    fun setVoice(id: Int) {
        voiceId = id
        if (_state.value.playing) {
            // restart current sentence with the new voice
            seekTo(cursor)
        }
    }

    private fun stopInternal() {
        playJob?.cancel()
        playJob = null
        sink?.pause() // the cancelled job's finally releases the track
        sink = null
        _state.update { it.copy(playing = false) }
    }

    /**
     * Returns a sentence's audio: from the disk cache if present (already trimmed), otherwise
     * synthesizes it, trims it, and caches it for next time. Cache is keyed by book + voice, so a
     * "downloaded" book plays entirely from disk with no synthesis in the playback path.
     */
    private fun loadOrSynth(index: Int): FloatArray? {
        cache.read(bookId, voiceId, index)?.let { return it }
        val entry = flat.getOrNull(index) ?: return null
        val audio = runCatching { tts.generate(entry.text, voiceId, 1.0f) }.getOrNull() ?: return null
        if (audio.samples.isEmpty()) return null
        val trimmed = trimSilence(audio.samples, tts.sampleRate)
        runCatching { cache.write(bookId, voiceId, index, trimmed) }
        return trimmed
    }

    fun release() {
        stopInternal()
        scope.cancel()
    }

    private companion object {
        // ~48 sentences banked ahead (~14 MB of float PCM) absorbs synthesis-rate variance so
        // playback never starves between sentences.
        const val LOOKAHEAD_SENTENCES = 48
    }
}
