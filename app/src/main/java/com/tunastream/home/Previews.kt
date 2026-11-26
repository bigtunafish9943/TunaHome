package com.tunastream.home

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Watch Next previews, as on Google TV: rest on a show for a moment and the short preview its app
 * provides plays silently behind the apps, filling the screen. Moving on stops it. Only shows
 * whose app gives a preview have one.
 */
@OptIn(UnstableApi::class)
class Previews(private val view: PlayerView) {
    companion object {
        val SHOW = Look.Switch("watch_next_previews", true)

        /** How long to rest on a show before its preview starts. */
        private const val DWELL_MS = 1200L
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playing: String? = null
    private val enabled = SHOW.get(view.context)

    /** The card the viewer is on now; null when not on one. */
    fun focused(app: AppEntry?) {
        if (!enabled) return
        val preview = app?.preview
        if (preview != null && app.pkg == playing) return
        main.removeCallbacksAndMessages(null)
        stop()
        if (preview != null) {
            val key = app.pkg
            main.postDelayed({ play(key, preview) }, DWELL_MS)
        }
    }

    private fun play(key: String, address: String) {
        val made = try {
            ExoPlayer.Builder(view.context).build()
        } catch (e: Throwable) {
            return
        }
        player = made
        playing = key
        made.volume = 0f
        made.repeatMode = Player.REPEAT_MODE_ONE
        made.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Fade in only once there's a picture, so nothing flashes black first
                view.animate().alpha(1f).setDuration(600).start()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                stop()
            }
        })
        view.alpha = 0f
        // Shown (nearly invisibly) before it plays: a hidden view gets no picture
        view.visibility = View.VISIBLE
        view.player = made
        made.setMediaItem(MediaItem.fromUri(Uri.parse(address)))
        made.prepare()
        made.play()
    }

    /** Stops any preview, fading it out. */
    fun stop() {
        val made = player ?: return
        player = null
        playing = null
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(300).withEndAction {
            if (player == null) {
                view.player = null
                view.visibility = View.GONE
            }
            made.release()
        }.start()
    }

    /** The home screen is going away: stop at once. */
    fun clear() {
        main.removeCallbacksAndMessages(null)
        val made = player
        player = null
        playing = null
        view.animate().cancel()
        view.alpha = 0f
        view.player = null
        view.visibility = View.GONE
        made?.release()
    }
}
