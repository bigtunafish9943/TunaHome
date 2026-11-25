package com.tunastream.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import kotlin.concurrent.thread

/**
 * The background following the app you're on, as on Google TV: after a moment on an app, its TV
 * picture (or box art, or poster) fades in over the usual background, darkened so names stay
 * readable. Apps with no picture of their own let the usual background show again.
 */
class Ambient(private val view: ImageView) {
    companion object {
        const val OFF = 0
        const val PICTURE = 1
        const val BLURRED = 2
        val FOLLOW = Look.Choice(
            "bg_follow", OFF, intArrayOf(R.string.off, R.string.follow_picture, R.string.follow_blurred)
        )

        /** How much the app's picture is darkened, so names stay readable. */
        val FINE_DARK = Look.Level("fine_follow_dark", 0, 90, 5, R.string.fine_percent, { 55 })

        /** How long to stay on an app before its picture fades in, so moving quickly stays calm. */
        private const val DWELL_MS = 450L
    }

    private val mode = FOLLOW.get(view.context)
    private val main = Handler(Looper.getMainLooper())
    private val made = LruCache<String, Bitmap>(16)
    private var wanted: String? = null
    private var showing: String? = null

    val enabled: Boolean
        get() = mode != OFF

    init {
        view.setColorFilter(Color.argb(Math.round(FINE_DARK.get(view.context) * 2.55f), 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
    }

    /** The app you're on now; null when you're not on an app. */
    fun focused(app: AppEntry?) {
        if (!enabled) {
            return
        }
        main.removeCallbacksAndMessages(null)
        val picture = app?.let { it.own ?: it.banner }
        if (app == null || picture == null) {
            wanted = null
            main.postDelayed({ fadeOut() }, DWELL_MS)
            return
        }
        val key = app.pkg
        wanted = key
        if (key == showing) {
            return
        }
        main.postDelayed({ load(key, picture) }, DWELL_MS)
    }

    /** Another app is in front: hide straight away. */
    fun clear() {
        main.removeCallbacksAndMessages(null)
        wanted = null
        showing = null
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
        view.setImageDrawable(null)
    }

    private fun load(key: String, picture: Drawable) {
        made.get(key)?.let {
            show(key, it)
            return
        }
        val copy = picture.constantState?.newDrawable()?.mutate() ?: picture
        thread(name = "TunaHome follow") {
            val bitmap = try {
                render(copy)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (bitmap != null) {
                    made.put(key, bitmap)
                    if (wanted == key) {
                        show(key, bitmap)
                    }
                }
            }
        }
    }

    private fun render(picture: Drawable): Bitmap {
        var w = picture.intrinsicWidth.takeIf { it > 0 } ?: 640
        var h = picture.intrinsicHeight.takeIf { it > 0 } ?: 360
        if (w > 960) {
            h = h * 960 / w
            w = 960
        }
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(16), h.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        picture.setBounds(0, 0, bitmap.width, bitmap.height)
        picture.draw(Canvas(bitmap))
        return if (mode == BLURRED) Pictures.blur(bitmap, 1) else bitmap
    }

    private fun show(key: String, bitmap: Bitmap) {
        showing = key
        val next = BitmapDrawable(view.resources, bitmap)
        view.animate().cancel()
        val old = view.drawable
        if (view.visibility != View.VISIBLE || old == null || view.alpha < 0.05f) {
            view.setImageDrawable(next)
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(500).start()
        } else {
            view.alpha = 1f
            // From whatever is showing now (the end of the last fade, if it was one)
            val from = (old as? TransitionDrawable)?.getDrawable(1) ?: old
            val fade = TransitionDrawable(arrayOf(from, next))
            fade.isCrossFadeEnabled = true
            view.setImageDrawable(fade)
            fade.startTransition(500)
        }
    }

    private fun fadeOut() {
        if (showing == null) {
            return
        }
        showing = null
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(500).withEndAction {
            if (showing == null) {
                view.visibility = View.GONE
                view.setImageDrawable(null)
            }
        }.start()
    }
}
