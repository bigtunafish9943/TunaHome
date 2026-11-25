package com.tunastream.home

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.PlayerView

/**
 * TunaHome as the TV's screen saver: whatever background the home screen has (Aerial Views and
 * other wallpaper apps included), with the time. The time moves to another corner every few
 * minutes so it never stays in one place on the screen. Chosen in the TV's own screen saver
 * settings; TunaHome changes nothing there itself.
 */
class Screensaver : DreamService() {
    private var backdrop: Backdrop? = null
    private val main = Handler(Looper.getMainLooper())
    private var corner = 0

    // The weather and what's playing, looked at again every half minute
    private val extras: Runnable = object : Runnable {
        override fun run() {
            showExtras()
            Weather.refresh(this@Screensaver) { showExtras() }
            main.postDelayed(this, 30_000L)
        }
    }

    private fun showExtras() {
        val box = findViewById<View>(R.id.saverWeatherBox) ?: return
        val now = Weather.current
        if (Weather.SHOW.get(this) && Weather.place(this) != null && now != null) {
            findViewById<ImageView>(R.id.saverWeatherIcon).setImageResource(Weather.icon(now.code, now.day))
            findViewById<TextView>(R.id.saverWeather).text =
                getString(R.string.wx_degrees, now.temperature) + "  " + getString(Weather.condition(now.code))
            box.visibility = View.VISIBLE
        } else {
            box.visibility = View.GONE
        }
        val music = findViewById<TextView>(R.id.saverMusic)
        val playing = playing()
        music.text = playing ?: ""
        music.visibility = if (playing == null) View.GONE else View.VISIBLE
    }

    /** "♪ Song · Artist" for whatever is playing, if TunaHome may see it. */
    private fun playing(): String? {
        if (!NowPlaying.allowed(this)) return null
        return try {
            val manager = getSystemService(android.media.session.MediaSessionManager::class.java) ?: return null
            val controller = manager.getActiveSessions(NowPlaying.component(this)).firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            } ?: return null
            val meta = controller.metadata ?: return null
            val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            "♪  " + if (artist.isNullOrBlank()) title else "$title · $artist"
        } catch (e: Exception) {
            null
        }
    }

    private val move: Runnable = object : Runnable {
        override fun run() {
            moveClock(true)
            main.postDelayed(this, MOVE_EVERY_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        // The home screen's theme, highlight and text colours, as every TunaHome screen wears them
        val themed = ContextThemeWrapper(this, R.style.TunaHome)
        themed.theme.applyStyle(TunaTheme.styleOf(TunaTheme.current(this)), true)
        Look.ACCENT_STYLES[Look.ACCENT.get(this)].let { if (it != 0) themed.theme.applyStyle(it, true) }
        Look.TEXT_COLOUR_STYLES[Look.TEXT_COLOUR.get(this)].let { if (it != 0) themed.theme.applyStyle(it, true) }
        val root = LayoutInflater.from(themed).inflate(R.layout.screensaver, null)
        root.background = themed.getDrawable(R.drawable.home_bg)
        Look.styleText(root)
        setContentView(root)

        backdrop = Backdrop(
            themed,
            root.findViewById<ImageView>(R.id.backdropImage),
            root.findViewById<PlayerView>(R.id.backdropVideo),
            root.findViewById(R.id.backdropShade),
            root.findViewById<TextView>(R.id.backdropCaption),
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        backdrop?.start()
        corner = (0..3).random()
        moveClock(false)
        main.removeCallbacks(move)
        main.postDelayed(move, MOVE_EVERY_MS)
        main.removeCallbacks(extras)
        extras.run()
    }

    override fun onDreamingStopped() {
        main.removeCallbacks(move)
        main.removeCallbacks(extras)
        backdrop?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(move)
        main.removeCallbacks(extras)
        backdrop?.stop()
        backdrop = null
        super.onDetachedFromWindow()
    }

    /** Puts the time in the next corner, fading out and back in. */
    private fun moveClock(fade: Boolean) {
        val clock = findViewById<View>(R.id.saverClock) ?: return
        val place = {
            corner = (corner + 1) % 4
            val params = clock.layoutParams as FrameLayout.LayoutParams
            params.gravity = when (corner) {
                0 -> Gravity.BOTTOM or Gravity.END
                1 -> Gravity.TOP or Gravity.END
                2 -> Gravity.TOP or Gravity.START
                else -> Gravity.BOTTOM or Gravity.START
            }
            (clock as? android.widget.LinearLayout)?.gravity =
                if (corner == 0 || corner == 1) Gravity.END else Gravity.START
            clock.layoutParams = params
        }
        if (!fade) {
            place()
            return
        }
        clock.animate().alpha(0f).setDuration(800).withEndAction {
            place()
            clock.animate().alpha(1f).setDuration(800).start()
        }.start()
    }

    companion object {
        private const val MOVE_EVERY_MS = 3L * 60 * 1000

        /** Whether the TV's screen saver is TunaHome's (false if the TV won't say). */
        fun isChosen(context: android.content.Context): Boolean = try {
            android.provider.Settings.Secure.getString(context.contentResolver, "screensaver_components")
                ?.contains(context.packageName + "/") == true
        } catch (e: Exception) {
            false
        }
    }
}
