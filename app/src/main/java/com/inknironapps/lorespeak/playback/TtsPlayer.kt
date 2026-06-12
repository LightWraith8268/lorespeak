package com.inknironapps.lorespeak.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.inknironapps.lorespeak.reader.ReaderEngine
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Bridges [ReaderEngine] into a Media3 [Player] so the system gets a media notification, lockscreen
 * transport, and Bluetooth/headset button handling. Audio itself is produced by the engine's
 * AudioTrack; this player only mirrors state and forwards transport commands.
 *
 * Media buttons next/previous map to chapter skips (sensible for a remote/lockscreen).
 */
class TtsPlayer(
    looper: Looper,
    private val engine: ReaderEngine,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        engine.state
            .onEach { invalidateState() }
            .launchIn(scope)
    }

    override fun getState(): State {
        val s = engine.state.value
        val chapters = engine.chapters
        val rate = CHARS_PER_SECOND * engine.speed.coerceAtLeast(0.1f) // effective chars/sec

        val playlist = if (chapters.isEmpty()) {
            listOf(chapterItem(0, s.bookTitle.ifBlank { "LoreSpeak" }, s.bookTitle, 1, rate))
        } else {
            chapters.mapIndexed { index, chapter ->
                chapterItem(index, chapter.title, s.bookTitle, chapter.charCount, rate)
            }
        }

        val currentChapter = s.chapterIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        val positionMs = ((s.chapterCharOffset / rate) * 1000f).toLong()

        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(if (s.finished) Player.STATE_ENDED else Player.STATE_READY)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentChapter)
            .setContentPositionMs(positionMs)
            .build()
    }

    private fun chapterItem(
        index: Int,
        chapterTitle: String,
        bookTitle: String,
        charCount: Int,
        rate: Float,
    ): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(chapterTitle.ifBlank { "Chapter ${index + 1}" })
            .setArtist(bookTitle.ifBlank { "LoreSpeak" })
            .setAlbumTitle(bookTitle.ifBlank { "LoreSpeak" })
            .build()
        val durationMs = ((charCount / rate) * 1000f).toLong().coerceAtLeast(1000L)
        return MediaItemData.Builder("chapter-$index")
            .setMediaItem(
                MediaItem.Builder().setMediaId("chapter-$index").setMediaMetadata(metadata).build(),
            )
            .setMediaMetadata(metadata)
            .setDurationUs(durationMs * 1000L)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> engine.skipChapter(1)

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> engine.skipChapter(-1)

            else -> {
                // Scrub within (or jump to) a chapter: convert the time position back to a char
                // offset and land on the nearest sentence.
                val rate = CHARS_PER_SECOND * engine.speed.coerceAtLeast(0.1f)
                val charOffset = (positionMs / 1000f * rate).toInt()
                engine.seekToChapterChars(mediaItemIndex, charOffset)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    companion object {
        // Rough English narration rate at 1.0x; only used to render the per-chapter time scrubber.
        private const val CHARS_PER_SECOND = 14.5f

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_STOP,
            )
            .build()
    }
}
