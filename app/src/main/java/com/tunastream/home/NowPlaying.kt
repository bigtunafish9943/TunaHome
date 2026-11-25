package com.tunastream.home

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * The Now playing card: whatever any app is playing, with play, pause and skip. Off unless the
 * viewer switches it on, and it shows nothing until they give TunaHome notification access.
 */
class NowPlaying(private val activity: Activity, private val card: View) {
    companion object {
        val SHOW = Look.Switch("now_playing", false)

        fun component(context: Context) = ComponentName(context, NowPlayingListener::class.java)

        /** Whether the viewer has given TunaHome notification access. */
        fun allowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 27) {
                return context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(component(context)) == true
            }
            val list = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == component(context) }
        }

        const val ADB_COMMAND = "adb shell cmd notification allow_listener com.tunastream.home/com.tunastream.home.NowPlayingListener"
    }

    private val manager = activity.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var shownArt: String? = null

    private val art: ImageView = card.findViewById(R.id.npArt)
    private val app: TextView = card.findViewById(R.id.npApp)
    private val title: TextView = card.findViewById(R.id.npTitle)
    private val subtitle: TextView = card.findViewById(R.id.npSubtitle)
    private val progress: View = card.findViewById(R.id.npProgress)
    private val track: View = card.findViewById(R.id.npTrack)
    private val play: ImageView = card.findViewById(R.id.npPlay)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() {
            choose(null)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            showProgress()
            main.postDelayed(this, 1000)
        }
    }

    init {
        card.findViewById<View>(R.id.npPrev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        card.findViewById<View>(R.id.npNext).setOnClickListener { controller?.transportControls?.skipToNext() }
        play.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        card.findViewById<View>(R.id.npOpen).setOnClickListener { openApp() }
    }

    fun start() {
        if (!SHOW.get(activity) || !allowed(activity) || manager == null) {
            card.visibility = View.GONE
            return
        }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component(activity), main)
            choose(manager.getActiveSessions(component(activity)))
        } catch (e: SecurityException) {
            card.visibility = View.GONE
        }
        main.post(tick)
    }

    fun stop() {
        main.removeCallbacks(tick)
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: Exception) {
            // Never added
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** The session to show: one that's playing, else the most recent one. */
    private fun choose(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (pick?.sessionToken == controller?.sessionToken) {
            refresh()
            return
        }
        controller?.unregisterCallback(callback)
        controller = pick
        pick?.registerCallback(callback, main)
        refresh()
    }

    private fun refresh() {
        val c = controller
        val meta = c?.metadata
        val name = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || name.isNullOrBlank()) {
            if (card.visibility == View.VISIBLE) {
                card.animate().alpha(0f).setDuration(250).withEndAction { card.visibility = View.GONE }.start()
            }
            return
        }
        val m = meta ?: return
        title.text = name
        subtitle.text = listOfNotNull(
            m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            m.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        app.text = try {
            activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(c.packageName, 0))
        } catch (e: Exception) {
            c.packageName
        }
        play.setImageResource(if (c.playbackState?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        showArt(m)
        showProgress()
        if (card.visibility != View.VISIBLE) {
            card.alpha = 0f
            card.visibility = View.VISIBLE
            card.animate().alpha(1f).setDuration(350).start()
        }
    }

    private fun showArt(meta: MediaMetadata) {
        val bitmap: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            shownArt = null
            art.setImageBitmap(bitmap)
            return
        }
        val uri = meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri == null) {
            shownArt = null
            art.setImageResource(R.drawable.ic_music)
            return
        }
        if (uri == shownArt) {
            return
        }
        shownArt = uri
        thread(name = "TunaHome now playing art") {
            val loaded = try {
                Pictures.decode(activity.applicationContext, Uri.parse(uri), 256, 256)
            } catch (e: Throwable) {
                null
            }
            main.post { if (shownArt == uri && loaded != null) art.setImageBitmap(loaded) }
        }
    }

    private fun showProgress() {
        val c = controller ?: return
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = c.playbackState
        if (duration <= 0 || state == null) {
            track.visibility = View.INVISIBLE
            return
        }
        track.visibility = View.VISIBLE
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val share = (position.toFloat() / duration).coerceIn(0f, 1f)
        progress.layoutParams = progress.layoutParams.apply { width = Math.round(track.width * share) }
    }

    private fun openApp() {
        val c = controller ?: return
        try {
            c.sessionActivity?.send()
            return
        } catch (e: Exception) {
            // Fall back to opening the app itself
        }
        val pm = activity.packageManager
        (pm.getLeanbackLaunchIntentForPackage(c.packageName) ?: pm.getLaunchIntentForPackage(c.packageName))
            ?.let { activity.startActivity(it) }
    }
}
