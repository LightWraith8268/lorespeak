package com.inknironapps.lorespeak.playback

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service hosting a [MediaSession] over [TtsPlayer]. The UI connects a
 * MediaController to this service and drives play/pause through it; Media3 then automatically posts
 * the media notification and promotes the service to the foreground (and demotes it on pause), which
 * is what keeps audio alive when the app is backgrounded or the screen is locked.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = PlaybackController.engine ?: run {
            stopSelf()
            return
        }
        val player = TtsPlayer(mainLooper, engine)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
