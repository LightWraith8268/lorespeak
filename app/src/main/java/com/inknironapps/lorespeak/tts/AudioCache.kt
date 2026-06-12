package com.inknironapps.lorespeak.tts

import android.content.Context
import java.io.File
import kotlin.math.abs

/**
 * Trims leading/trailing near-silence from a synthesized sentence, keeping a small pad (~10 ms lead
 * so word onsets aren't clipped, ~45 ms tail) so sentences flow with tight, natural spacing instead
 * of Kokoro's longer variable per-utterance padding. Shared by live playback and download rendering
 * so cached audio is identical either way.
 */
fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
    val threshold = 0.015f
    var start = 0
    while (start < samples.size && abs(samples[start]) < threshold) start++
    var end = samples.size
    while (end > start && abs(samples[end - 1]) < threshold) end--
    if (start >= end) return samples

    val leadPad = (sampleRate * 0.01).toInt()
    val tailPad = (sampleRate * 0.045).toInt()
    val from = (start - leadPad).coerceAtLeast(0)
    val to = (end + tailPad).coerceAtMost(samples.size)
    return if (from == 0 && to == samples.size) samples else samples.copyOfRange(from, to)
}

/**
 * On-disk cache of synthesized audio, one file per sentence, as raw little-endian 16-bit mono PCM at
 * 24 kHz. Keyed by book + voice + global sentence index, so:
 *  - playback reads files instead of synthesizing (no stalls once rendered),
 *  - the cache survives across sessions (resume is instant for rendered parts),
 *  - changing playback speed costs nothing (speed is applied at playback, not baked in),
 *  - changing voice uses a different folder (the new voice re-renders; old stays cached).
 */
class AudioCache(context: Context) {

    private val root = File(context.filesDir, "audiocache")

    private fun voiceDir(bookId: String, voiceId: Int) = File(root, "$bookId/$voiceId")

    private fun sentenceFile(bookId: String, voiceId: Int, index: Int) =
        File(voiceDir(bookId, voiceId), "s$index.pcm")

    fun has(bookId: String, voiceId: Int, index: Int): Boolean {
        val file = sentenceFile(bookId, voiceId, index)
        return file.exists() && file.length() > 0
    }

    /** Number of sentences already rendered for this book+voice (for progress display). */
    fun renderedCount(bookId: String, voiceId: Int): Int =
        voiceDir(bookId, voiceId).listFiles()?.count { it.length() > 0 } ?: 0

    /** Converts Kokoro float samples to 16-bit PCM and writes atomically (temp + rename). */
    fun write(bookId: String, voiceId: Int, index: Int, samples: FloatArray) {
        val dir = voiceDir(bookId, voiceId).apply { mkdirs() }
        val bytes = ByteArray(samples.size * 2)
        var b = 0
        for (sample in samples) {
            val clamped = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[b++] = (clamped and 0xFF).toByte()
            bytes[b++] = ((clamped shr 8) and 0xFF).toByte()
        }
        val tmp = File(dir, "s$index.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(sentenceFile(bookId, voiceId, index))
    }

    /** Reads a cached sentence back to Kokoro-style float samples, or null if absent. */
    fun read(bookId: String, voiceId: Int, index: Int): FloatArray? {
        val file = sentenceFile(bookId, voiceId, index)
        if (!file.exists() || file.length() == 0L) return null
        val bytes = file.readBytes()
        val samples = FloatArray(bytes.size / 2)
        var b = 0
        for (i in samples.indices) {
            val lo = bytes[b++].toInt() and 0xFF
            val hi = bytes[b++].toInt()
            samples[i] = ((hi shl 8) or lo) / 32768f
        }
        return samples
    }

    fun clearBook(bookId: String) {
        File(root, bookId).deleteRecursively()
    }
}
