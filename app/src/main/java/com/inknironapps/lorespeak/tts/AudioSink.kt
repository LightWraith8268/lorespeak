package com.inknironapps.lorespeak.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Streaming PCM sink for just-in-time TTS output, thread-safe.
 *
 * AudioTrack in MODE_STREAM with ENCODING_PCM_FLOAT (Kokoro emits 24 kHz mono float). All native
 * calls are serialized on [lock] and gated by [released] so a write from the synth thread can never
 * touch a track that another thread is releasing (the use-after-free that crashed playback).
 *
 * The buffer is sized to ~2 s so brief synthesis stalls between sentences don't underrun (the cause
 * of the unnatural gaps). Speed is applied here with pitch pinned at 1.0 → pitch-preserved
 * time-stretch, so changing speed never invalidates already-synthesized audio.
 */
class AudioSink(private val sampleRate: Int) {

    private val lock = Any()

    @Volatile
    private var released = false

    private val maxSpeed = 2.0f

    private val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
    ).coerceAtLeast(4096)

    // ~4 s of float PCM, but at least a few minBuffers and enough for max speed.
    private val bufferBytes = maxOf(
        sampleRate * 4 * 4,                                  // 4 s @ 4 bytes/sample
        minBuffer * ceil(maxSpeed.toDouble()).toInt() * 2,
    )

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .build()

    @Volatile
    var speed: Float = 1.0f
        set(value) {
            field = value
            synchronized(lock) {
                if (!released) {
                    runCatching { track.playbackParams = track.playbackParams.setSpeed(value).setPitch(1.0f) }
                }
            }
        }

    fun play() = synchronized(lock) {
        if (!released && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }
        }
        Unit
    }

    /**
     * Non-blocking write of [samples] starting at [offset]. Returns the number of samples accepted
     * (0 if the track buffer is momentarily full), or -1 if released. Caller loops, yielding when 0.
     */
    fun offer(samples: FloatArray, offset: Int): Int = synchronized(lock) {
        if (released) return -1
        track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun pause() = synchronized(lock) {
        if (!released) runCatching { track.pause() }
        Unit
    }

    fun release() = synchronized(lock) {
        if (!released) {
            released = true
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

/**
 * Suspending, cancellation-aware write of a whole buffer. Yields when the track buffer is full and
 * throws CancellationException promptly when the coroutine is cancelled, so the caller's finally can
 * release the sink on the same thread (no cross-thread native release).
 */
suspend fun AudioSink.writeAll(samples: FloatArray) {
    var offset = 0
    while (offset < samples.size) {
        coroutineContext.ensureActive()
        val written = offer(samples, offset)
        if (written < 0) return
        offset += written
        if (written == 0) delay(3)
    }
}
