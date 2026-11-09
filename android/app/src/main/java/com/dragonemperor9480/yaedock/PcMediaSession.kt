package com.dragonemperor9480.yaedock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Media
import com.dragonemperor9480.yaedock.protocol.MediaAction
import com.dragonemperor9480.yaedock.protocol.MediaArt
import com.dragonemperor9480.yaedock.protocol.MediaControl
import com.dragonemperor9480.yaedock.protocol.Seek
import java.util.Base64

/**
 * What plays on the PC, as one of the phone's own media players: the
 * notification shade and the lock screen show it with its cover and a seek
 * bar, and their buttons control the PC. Lives in [ConnectionService].
 */
class PcMediaSession(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val session = MediaSession(context, "YaeDock")
    private var media: Media? = null
    private var send: (ClientMessage) -> Unit = {}
    private var cover: Pair<String, Bitmap?>? = null
    private var shownMetadata: MediaMetadata? = null
    private var metadataKey: List<Any?>? = null

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Media on the PC", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What plays on the PC, with its controls"
                setShowBadge(false)
            },
        )
        session.setSessionActivity(openApp())
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (media?.playing == false) control(MediaAction.PLAY_PAUSE)
            }

            override fun onPause() {
                if (media?.playing == true) control(MediaAction.PLAY_PAUSE)
            }

            override fun onSkipToNext() = control(MediaAction.NEXT)

            override fun onSkipToPrevious() = control(MediaAction.PREVIOUS)

            override fun onSeekTo(pos: Long) {
                media?.let { send(Seek(pos.coerceAtLeast(0), it.player)) }
            }
        })
    }

    /** Shows [media], or hides the player when it is null. */
    fun show(media: Media?, art: MediaArt?, send: (ClientMessage) -> Unit) {
        this.send = send
        val old = this.media
        this.media = media
        if (media == null) {
            if (old != null) hide()
            return
        }
        // The cover can arrive after the track's state.
        if (media == old && (media.art == null || cover?.first == media.art || art?.id != media.art)) return
        val image = coverFor(media, art)
        // Only when it changes: setting it again makes some phones redraw the player.
        val key = listOf(media.title, media.artist, media.app, media.lengthMs, image)
        val metadata = shownMetadata?.takeIf { key == metadataKey } ?: metadata(media, image).also {
            session.setMetadata(it)
            shownMetadata = it
            metadataKey = key
        }
        session.setPlaybackState(playback(media))
        session.isActive = true
        notifications.notify(NOTIFICATION_ID, notification(media, metadata))
    }

    /** Ends the session; the object is not used afterwards. */
    fun release() {
        hide()
        session.release()
    }

    private fun hide() {
        media = null
        shownMetadata = null
        metadataKey = null
        notifications.cancel(NOTIFICATION_ID)
        session.isActive = false
    }

    private fun control(action: MediaAction) {
        media?.let { send(MediaControl(action, it.player)) }
    }

    /** The cover of [media]: [art] once it is that cover's, decoded once. */
    private fun coverFor(media: Media, art: MediaArt?): Bitmap? {
        val id = media.art ?: return null
        cover?.let { (cached, bitmap) -> if (cached == id) return bitmap }
        if (art?.id != id) return null
        val bitmap = runCatching {
            val bytes = Base64.getDecoder().decode(art.data)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        cover = id to bitmap
        return bitmap
    }

    private fun metadata(media: Media, cover: Bitmap?) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, media.title.ifEmpty { media.app })
        .putString(MediaMetadata.METADATA_KEY_ARTIST, media.artist.ifEmpty { media.app })
        .apply {
            media.lengthMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        }
        .build()

    private fun playback(media: Media): PlaybackState {
        var actions = 0L
        if (media.canPlayPause) {
            actions = actions or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        }
        if (media.canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (media.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (media.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        // The system moves the position on from when it arrived, as the dock does.
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                media.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (media.playing) 1f else 0f,
                media.receivedAt,
            )
            .build()
    }

    /**
     * The player's notification. Newer Android draws its buttons from the
     * session; older versions use the actions here.
     */
    private fun notification(media: Media, metadata: MediaMetadata): Notification {
        val buttons = listOfNotNull(
            action(R.drawable.ic_skip_previous, "Previous", ACTION_PREVIOUS).takeIf { media.canPrevious },
            (if (media.playing) action(R.drawable.ic_pause, "Pause", ACTION_PLAY_PAUSE) else action(R.drawable.ic_play, "Play", ACTION_PLAY_PAUSE))
                .takeIf { media.canPlayPause },
            action(R.drawable.ic_skip_next, "Next", ACTION_NEXT).takeIf { media.canNext },
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
            .setContentText(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .setSubText(media.app)
            .setLargeIcon(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
            .setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(media.playing)
            .setShowWhen(false)
            .apply { buttons.forEach(::addAction) }
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(*buttons.indices.toList().toIntArray()),
            )
            .build()
    }

    /** Handles a notification button; true if [action] was one. */
    fun handle(action: String?): Boolean {
        when (action) {
            ACTION_PREVIOUS -> control(MediaAction.PREVIOUS)
            ACTION_PLAY_PAUSE -> control(MediaAction.PLAY_PAUSE)
            ACTION_NEXT -> control(MediaAction.NEXT)
            else -> return false
        }
        return true
    }

    private fun action(icon: Int, label: String, action: String) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(context, icon),
        label,
        PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, ConnectionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    private fun openApp() = PendingIntent.getActivity(
        context, 2,
        Intent(context, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL = "media"
        const val NOTIFICATION_ID = 2
        const val ACTION_PREVIOUS = "com.dragonemperor9480.yaedock.MEDIA_PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.dragonemperor9480.yaedock.MEDIA_PLAY_PAUSE"
        const val ACTION_NEXT = "com.dragonemperor9480.yaedock.MEDIA_NEXT"
    }
}
