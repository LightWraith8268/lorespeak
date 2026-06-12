package com.inknironapps.lorespeak.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * On-device Kokoro-82M TTS via sherpa-onnx.
 *
 * model.int8.onnx / voices.bin / tokens.txt are read straight from APK assets through the
 * AssetManager (no copy). espeak-ng-data MUST live on the real filesystem (espeak opens it with
 * fopen), so it is copied to filesDir once on first launch.
 *
 * English bundle kokoro-int8-en-v0_19 exposes 11 speakers (sid 0..10), 24 kHz mono float output.
 */
class KokoroTts private constructor(private val tts: OfflineTts) {

    // One ONNX session — serialize generate() so concurrent callers (playback + download) are safe.
    private val generateLock = Any()

    val sampleRate: Int get() = tts.sampleRate()
    val numSpeakers: Int get() = tts.numSpeakers()

    /** Blocking full synthesis of one chunk. Returns float PCM in [-1, 1] at [sampleRate]. */
    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudio =
        synchronized(generateLock) { tts.generate(text, sid, speed) }

    /**
     * Streaming synthesis: [onChunk] is invoked on the synthesis thread with each partial buffer as
     * it is produced. Return true to keep going, false to abort early.
     */
    fun generateStreaming(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        onChunk: (FloatArray) -> Boolean,
    ): GeneratedAudio = synchronized(generateLock) {
        tts.generateWithCallback(text, sid, speed) { samples ->
            if (onChunk(samples)) 1 else 0
        }
    }

    fun release() = tts.free()

    companion object {
        private const val MODEL_DIR = "kokoro-int8-en-v0_19"

        /** Build the engine. Call off the main thread — model load takes ~hundreds of ms. */
        fun create(context: Context, numThreads: Int = 6): KokoroTts {
            val espeakData = File(context.filesDir, "$MODEL_DIR/espeak-ng-data")
            // "phontab" is a core espeak file; its presence means the copy already completed.
            if (!File(espeakData, "phontab").exists()) {
                copyAssetDir(context, "$MODEL_DIR/espeak-ng-data", espeakData)
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$MODEL_DIR/model.int8.onnx",
                        voices = "$MODEL_DIR/voices.bin",
                        tokens = "$MODEL_DIR/tokens.txt",
                        dataDir = espeakData.absolutePath,
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            val tts = OfflineTts(assetManager = context.assets, config = config)
            return KokoroTts(tts)
        }

        private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
            val assets = context.assets
            val children = assets.list(assetPath) ?: emptyArray()
            if (children.isEmpty()) {
                // Leaf = a file.
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                dest.mkdirs()
                for (child in children) {
                    copyAssetDir(context, "$assetPath/$child", File(dest, child))
                }
            }
        }
    }
}
