package com.tunastream.home

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import kotlin.concurrent.thread

/**
 * What shows behind the home screen: the theme's colours, the viewer's picture, their own videos
 * and pictures, or whatever a wallpaper app such as Aerial Views offers.
 *
 * Videos play silently and stop the moment another app opens, so the TV's video decoder is free
 * for whatever you open next.
 */
@OptIn(UnstableApi::class)
class Backdrop(
    private val activity: Context,
    private val image: ImageView,
    private val video: PlayerView,
    private val shade: View,
    private val caption: TextView,
) {
    companion object {
        const val SOURCE_THEME = 0
        const val SOURCE_PICTURE = 1
        const val SOURCE_VIDEOS = 2
        const val SOURCE_APP = 3
        const val SOURCE_DAILY = 4
        val SOURCE = Look.Choice(
            "bg_source", SOURCE_THEME, intArrayOf(
                R.string.bg_theme, R.string.bg_picture, R.string.bg_videos, R.string.bg_app, R.string.bg_daily,
            )
        )

        val DIM = Look.Choice(
            "bg_dim", 2, intArrayOf(
                R.string.dim_none, R.string.dim_light, R.string.dim_medium,
                R.string.dim_strong, R.string.dim_stronger, R.string.dim_strongest,
            )
        )
        private val DIM_ALPHA = floatArrayOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f)

        val CHANGE_EVERY = Look.Choice(
            "bg_change_every", 2, intArrayOf(
                R.string.every_15s, R.string.every_30s, R.string.every_1m, R.string.every_2m,
                R.string.every_5m, R.string.every_15m, R.string.every_30m, R.string.every_1h,
            )
        )
        private val CHANGE_MS = longArrayOf(
            15_000, 30_000, 60_000, 120_000, 300_000, 900_000, 1_800_000, 3_600_000,
        )

        const val FIT_FILL = 0
        const val FIT_WHOLE = 1
        const val FIT_STRETCH = 2
        val FIT = Look.Choice(
            "bg_fit", FIT_FILL, intArrayOf(R.string.fit_fill, R.string.fit_whole, R.string.fit_stretch)
        )

        // Picture adjustments, as photo apps have them
        val BRIGHTNESS = Look.Choice(
            "bg_brightness", 2, intArrayOf(
                R.string.adj_darker, R.string.adj_bit_darker, R.string.adj_normal,
                R.string.adj_bit_brighter, R.string.adj_brighter,
            )
        )
        private val BRIGHTNESS_SHIFT = floatArrayOf(-50f, -25f, 0f, 20f, 40f)

        val CONTRAST = Look.Choice(
            "bg_contrast", 1, intArrayOf(R.string.adj_low, R.string.adj_normal, R.string.adj_high, R.string.adj_higher)
        )
        private val CONTRAST_SCALE = floatArrayOf(0.8f, 1f, 1.2f, 1.4f)

        val SATURATION = Look.Choice(
            "bg_saturation", 2, intArrayOf(
                R.string.adj_grey, R.string.adj_faded, R.string.adj_normal, R.string.adj_rich, R.string.adj_vivid,
            )
        )
        private val SATURATION_SCALE = floatArrayOf(0f, 0.55f, 1f, 1.3f, 1.6f)

        /** Pictures only: blurring a video every frame would be too much for the TV. */
        val BLUR = Look.Choice(
            "bg_blur", 0, intArrayOf(R.string.off, R.string.blur_light, R.string.blur_medium, R.string.blur_strong)
        )

        /** The colour changes chosen above, as one filter; null when nothing is changed. */
        fun filter(context: Context): android.graphics.ColorMatrixColorFilter? {
            val b = BRIGHTNESS.get(context)
            val c = CONTRAST.get(context)
            val sat = SATURATION.get(context)
            if (b == 2 && c == 1 && sat == 2) {
                return null
            }
            val matrix = android.graphics.ColorMatrix()
            matrix.setSaturation(SATURATION_SCALE[sat])
            val scale = CONTRAST_SCALE[c]
            // Contrast around the middle grey, then brightness as a shift
            val shift = (1 - scale) * 128f + BRIGHTNESS_SHIFT[b]
            matrix.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, shift,
                        0f, scale, 0f, 0f, shift,
                        0f, 0f, scale, 0f, shift,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
            return android.graphics.ColorMatrixColorFilter(matrix)
        }

        /** The background drifts a little as you scroll down the sections, for a sense of depth. */
        val DRIFT = Look.Switch("bg_drift", false)

        /** How dark the background is made, step by step. */
        val FINE_DIM = Look.Level("fine_bg_dim", 0, 90, 5, R.string.fine_percent,
            { Math.round(DIM_ALPHA[DIM.get(it)] * 100) }, DIM)

        val SHUFFLE = Look.Switch("bg_shuffle", true)
        val SHOW_CAPTION = Look.Switch("bg_caption", false)
        val PLAY_VIDEOS = Look.Switch("bg_play_videos", true)

        private const val APP = "bg_app"
        private const val VIDEOS = "bg_videos"

        /** What the background is doing, in plain words, for the Background settings page. */
        @Volatile
        var status: String = ""
            private set

        fun app(context: Context): ComponentName? =
            Prefs.of(context).getString(APP, null)?.let { ComponentName.unflattenFromString(it) }

        fun setApp(context: Context, component: ComponentName) {
            Prefs.of(context).edit().putString(APP, component.flattenToString()).apply()
            Look.touch(context)
        }

        /** The viewer's own videos and pictures, in order. */
        fun videos(context: Context): List<Own> =
            (Prefs.of(context).getString(VIDEOS, "") ?: "").split('\n').filter { it.isNotBlank() }.map { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) Own(line.substring(tab + 1), line.substring(0, tab) != "picture")
                else Own(line, !looksLikePicture(line))
            }

        fun setVideos(context: Context, list: List<Own>) {
            val text = list.joinToString("\n") { (if (it.isVideo) "video" else "picture") + "\t" + it.uri }
            Prefs.of(context).edit().putString(VIDEOS, text).apply()
            Look.touch(context)
        }

        /** Whether an address looks like a picture rather than a video. */
        fun looksLikePicture(address: String): Boolean {
            val path = address.substringBefore('?').lowercase()
            return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp").any { path.endsWith(it) }
        }
    }

    /** One of the viewer's own videos or pictures, on the TV or on the web. */
    class Own(val uri: String, val isVideo: Boolean) {
        /** A short name for lists: the end of its address. */
        fun shortName(): String {
            val path = Uri.parse(uri).lastPathSegment ?: uri
            return Uri.decode(path).substringAfterLast('/').substringAfterLast(':')
        }
    }

    private class Item(val uri: String, val isVideo: Boolean, val title: String?)

    private val context: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var items: MutableList<Item> = ArrayList()
    private var index = 0
    private var failures = 0
    private var running = false

    /** Bumped on every start and stop, so late answers for an old showing are ignored. */
    private var generation = 0

    fun start() {
        stop()
        running = true
        val showing = ++generation
        val source = SOURCE.get(context)
        shade.alpha = if (FINE_DIM.isSet(context)) FINE_DIM.get(context) / 100f else DIM_ALPHA[DIM.get(context)]
        shade.visibility = if (source == SOURCE_THEME) View.GONE else View.VISIBLE
        applyFit()
        applyFilter()

        status = ""
        when (source) {
            SOURCE_PICTURE -> thread(name = "TunaHome wallpaper") {
                val picture = try {
                    Pictures.loadWallpaper(context)?.let { soften(it) }
                } catch (e: Throwable) {
                    null
                }
                main.post {
                    if (showing == generation) {
                        if (picture == null) clear() else showDrawable(BitmapDrawable(context.resources, picture))
                    }
                }
            }
            SOURCE_VIDEOS -> play(videos(context).map { Item(it.uri, it.isVideo, null) })
            SOURCE_DAILY -> thread(name = "TunaHome picture of the day") {
                val (picture, caption) = try {
                    Daily.picture(context).let { (p, c) -> p?.let { soften(it) } to c }
                } catch (e: Throwable) {
                    null to null
                }
                main.post {
                    if (showing != generation) return@post
                    if (picture == null) {
                        clear()
                        return@post
                    }
                    showDrawable(BitmapDrawable(context.resources, picture))
                    if (SHOW_CAPTION.get(context) && !caption.isNullOrBlank()) {
                        caption.let { this.caption.text = it }
                        this.caption.visibility = View.VISIBLE
                    }
                }
            }
            SOURCE_APP -> {
                val app = WallpaperApps.find(context, app(context))
                if (app == null) {
                    report(R.string.status_no_app)
                    clear()
                } else {
                    report(R.string.status_asking, app.name)
                    WallpaperApps.fetch(context, app) { list, problem ->
                        if (showing != generation) {
                            return@fetch
                        }
                        when {
                            problem != null -> report(R.string.status_app_failed, app.name, problem)
                            list.isEmpty() -> report(R.string.status_app_empty, app.name)
                            else -> report(R.string.status_app_gave, app.name, list.size)
                        }
                        play(list.mapNotNull { w ->
                            when (w.type) {
                                WallpaperType.VIDEO -> Item(w.uri, true, w.title)
                                WallpaperType.IMAGE -> Item(w.uri, false, w.title)
                                WallpaperType.COLOR -> Item(w.uri, false, w.title)
                                else -> null
                            }
                        })
                    }
                }
            }
            else -> clear()
        }
    }

    /** Moves on to the next video or picture now (a shortcut). */
    fun skip() {
        if (running && items.size > 1) {
            next()
        }
    }

    /** Stops everything and lets go of the video player. Call when another app comes in front. */
    fun stop() {
        (image.drawable as? Animatable)?.stop()
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        video.player = null
        video.visibility = View.GONE
    }

    private fun report(text: Int, vararg args: Any) {
        status = context.getString(text, *args)
    }

    private fun clear() {
        image.setImageDrawable(null)
        caption.visibility = View.GONE
        shade.visibility = View.GONE
        video.visibility = View.GONE
    }

    /** Brightness, contrast and colour strength, on pictures and videos alike. */
    private fun applyFilter() {
        val filter = filter(context)
        image.colorFilter = filter
        // A video is drawn on its own layer: the filter goes on that layer's paint
        (video.videoSurfaceView as? android.view.TextureView)?.setLayerPaint(
            if (filter == null) null else android.graphics.Paint().apply { colorFilter = filter }
        )
    }

    /** Blurs a picture, if the viewer asked for that. Slow: call from a background thread. */
    private fun soften(picture: Bitmap): Bitmap {
        val blur = BLUR.get(context)
        return if (blur == 0) picture else try {
            Pictures.blur(picture, blur)
        } catch (e: Throwable) {
            picture
        }
    }

    private fun applyFit() {
        when (FIT.get(context)) {
            FIT_WHOLE -> {
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                video.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            FIT_STRETCH -> {
                image.scaleType = ImageView.ScaleType.FIT_XY
                video.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            else -> {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                video.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        }
    }

    // ------------------------------------------------------------------ a list of things to show

    private fun play(list: List<Item>) {
        val usable = if (PLAY_VIDEOS.get(context)) list else list.filter { !it.isVideo }
        if (usable.isEmpty()) {
            clear()
            return
        }
        items = if (SHUFFLE.get(context)) usable.shuffled().toMutableList() else usable.toMutableList()
        index = 0
        failures = 0
        showCurrent()
    }

    private fun showCurrent() {
        if (!running || items.isEmpty()) {
            return
        }
        val item = items[index]
        if (SHOW_CAPTION.get(context) && !item.title.isNullOrBlank()) {
            caption.text = item.title
            caption.visibility = View.VISIBLE
        } else {
            caption.visibility = View.GONE
        }
        if (item.isVideo) playVideo(item) else showPicture(item)
    }

    private fun next() {
        if (!running || items.isEmpty()) {
            return
        }
        main.removeCallbacksAndMessages(null)
        index++
        if (index >= items.size) {
            index = 0
            if (SHUFFLE.get(context) && items.size > 2) {
                items.shuffle()
            }
        }
        showCurrent()
    }

    /** Something couldn't be shown: try the next one, unless nothing in the list works. */
    private fun failed() {
        failures++
        if (failures >= items.size) {
            report(R.string.status_nothing_played)
            stop()
            clear()
            return
        }
        next()
    }

    // ------------------------------------------------------------------ pictures

    private fun showPicture(item: Item) {
        val showing = generation
        // A wallpaper app can hand over a plain colour, such as "#203040"
        val colour = try {
            if (item.uri.startsWith("#")) Color.parseColor(item.uri) else null
        } catch (e: Exception) {
            null
        }
        if (colour != null) {
            pictureShown(ColorDrawable(colour))
            return
        }
        thread(name = "TunaHome background picture") {
            // A moving picture (GIF or animated WebP) plays as it is
            val moving = try {
                Pictures.animated(context, Uri.parse(item.uri), 1920, 1080)
            } catch (e: Throwable) {
                null
            }
            val bitmap = if (moving != null) null else try {
                Pictures.decode(context, Uri.parse(item.uri), 1920, 1080)?.let { soften(it) }
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (showing != generation) {
                    return@post
                }
                when {
                    moving != null -> pictureShown(moving)
                    bitmap == null -> failed()
                    else -> pictureShown(BitmapDrawable(context.resources, bitmap))
                }
            }
        }
    }

    private fun pictureShown(drawable: Drawable) {
        failures = 0
        if (items.isNotEmpty()) {
            report(R.string.status_picture, index + 1, items.size)
        }
        showDrawable(drawable)
        // The picture covers the screen now, so the video can stop
        player?.pause()
        video.visibility = View.GONE
        if (items.size > 1) {
            main.postDelayed({ next() }, CHANGE_MS[CHANGE_EVERY.get(context)])
        }
    }

    /** Fades from what was showing to [drawable]. */
    private fun showDrawable(drawable: Drawable) {
        val old = image.drawable
        (old?.current as? Animatable)?.stop()
        if (drawable is Animatable) {
            // A moving picture: shown straight in, playing (a crossfade would hold it still)
            image.setImageDrawable(drawable)
            drawable.start()
            image.alpha = 0f
            image.animate().alpha(1f).setDuration(500).start()
            return
        }
        if (old == null) {
            image.setImageDrawable(drawable)
            image.alpha = 0f
            image.animate().alpha(1f).setDuration(600).start()
        } else {
            val fade = TransitionDrawable(arrayOf(old.current ?: old, drawable))
            fade.isCrossFadeEnabled = true
            image.setImageDrawable(fade)
            fade.startTransition(800)
        }
    }

    // ------------------------------------------------------------------ videos

    @SuppressLint("UnsafeOptInUsageError")
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val made = ExoPlayer.Builder(activity).build()
        // Silent, and never takes the sound away from anything else
        made.trackSelectionParameters = made.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        made.volume = 0f
        made.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    next()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                report(R.string.status_video_failed, error.errorCodeName)
                failed()
            }

            override fun onRenderedFirstFrame() {
                failures = 0
                report(R.string.status_playing, index + 1, items.size)
                // The video layer was already on screen, nearly see-through: fade it in fully
                video.animate().cancel()
                if (video.alpha < 1f) {
                    video.animate().alpha(1f).setDuration(800).start()
                }
            }
        })
        video.player = made
        player = made
        return made
    }

    private fun playVideo(item: Item) {
        val player = ensurePlayer()
        // The video needs its layer on screen to have something to draw on, so put it there
        // now, almost see-through, and fade it in once the first frame is drawn. (Hidden, it
        // never gets a surface, never draws a frame, and the background stays plain.)
        if (video.visibility != View.VISIBLE) {
            video.alpha = 0.01f
            video.visibility = View.VISIBLE
        }
        report(R.string.status_loading_video, index + 1, items.size)
        player.setMediaItem(MediaItem.fromUri(item.uri))
        // A single video just loops
        player.repeatMode = if (items.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare()
        player.playWhenReady = true
    }
}
