package com.inknironapps.lorespeak.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a short fixed sample in a chosen voice so the user can audition voices before reading.
 * Only one preview plays at a time; starting a new one cancels the previous. [onActive] reports the
 * sid currently playing (or null when idle) — Compose snapshot state is safe to set from any thread.
 */
class VoicePreviewPlayer(
    private val tts: KokoroTts,
    private val onActive: (Int?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var sink: AudioSink? = null
    private var current: Int? = null

    fun preview(sid: Int, speed: Float) {
        stop()
        current = sid
        onActive(sid)
        job = scope.launch {
            val audio = runCatching { tts.generate(SAMPLE, sid, 1.0f) }.getOrNull()
            if (audio == null || !isActive) {
                if (current == sid) { current = null; onActive(null) }
                return@launch
            }
            val audioSink = AudioSink(audio.sampleRate).also { it.speed = speed }
            sink = audioSink
            try {
                audioSink.play()
                audioSink.writeAll(audio.samples)
            } finally {
                audioSink.release()
                if (current == sid) { current = null; onActive(null) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        sink?.pause() // the cancelled job's finally releases the track
        sink = null
        if (current != null) { current = null; onActive(null) }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val SAMPLE =
            "Hello. This is how I sound when I read your book aloud, page after page."
    }
}
