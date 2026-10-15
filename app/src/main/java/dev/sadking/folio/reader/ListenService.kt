package dev.sadking.folio.reader

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps read-aloud going with the screen off and puts its controls on the lock screen, in the
 * notification shade and on headphones. It wraps the player [ReadAloud] already runs; Media3 makes
 * the service foreground while it plays and posts the media notification.
 */
class ListenService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val listening = current
        if (listening == null) {
            stopSelf()
            return
        }
        val open =
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.BOOK_ID, listening.bookId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        session =
            MediaSession.Builder(this, listening.player)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        running = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiping Librillo away from recent apps stops reading, like closing the book. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        current?.stop?.invoke()
        finish()
    }

    override fun onDestroy() {
        if (running === this) running = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun finish() {
        session?.release()
        session = null
        stopSelf()
    }

    /** What the service plays: the book it belongs to and how to stop it from outside. */
    internal class Listening(val bookId: String, val player: Player, val stop: () -> Unit)

    internal companion object {
        /** Set by [ReadAloud] while it reads; the service wraps this player when it starts. */
        var current: Listening? = null
        private var running: ListenService? = null

        /** Ends the session and the notification once reading stops. */
        fun end() {
            current = null
            running?.finish()
        }
    }
}
