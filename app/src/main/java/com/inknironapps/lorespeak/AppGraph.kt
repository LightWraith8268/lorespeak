package com.inknironapps.lorespeak

import android.content.Context
import com.inknironapps.lorespeak.data.LibraryStore
import com.inknironapps.lorespeak.tts.AudioCache
import com.inknironapps.lorespeak.tts.KokoroTts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-wide singletons: the heavy TTS engine (load once) and the library store. */
object AppGraph {

    @Volatile
    private var storeRef: LibraryStore? = null

    @Volatile
    private var ttsRef: KokoroTts? = null
    private val ttsMutex = Mutex()

    fun store(context: Context): LibraryStore =
        storeRef ?: synchronized(this) {
            storeRef ?: LibraryStore(context.applicationContext).also { storeRef = it }
        }

    @Volatile
    private var cacheRef: AudioCache? = null

    fun cache(context: Context): AudioCache =
        cacheRef ?: synchronized(this) {
            cacheRef ?: AudioCache(context.applicationContext).also { cacheRef = it }
        }

    /** Loads the Kokoro engine on first use (off the main thread). Subsequent calls are instant. */
    suspend fun tts(context: Context): KokoroTts {
        ttsRef?.let { return it }
        return ttsMutex.withLock {
            ttsRef ?: withContext(Dispatchers.Default) {
                KokoroTts.create(context.applicationContext)
            }.also { ttsRef = it }
        }
    }

    fun isTtsLoaded(): Boolean = ttsRef != null
}
