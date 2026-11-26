package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Android TV's Watch Next list: the shows and films streaming apps put there for you to carry
 * on with. Reading it needs only the ordinary TV listings permission, which Android grants when
 * TunaHome is installed. Each entry becomes a card with its picture, a progress bar when the
 * app says how far you got, and opens straight into that show in its app.
 */
object WatchNext {
    private const val TAG = "TunaHome"
    private const val MAX = 24

    /** Pictures already fetched, so coming back to the home screen doesn't fetch them all again. */
    private val posters = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    fun forgetPictures() = posters.evictAll()

    /** Why the list came back empty, when it wasn't simply empty. */
    @Volatile
    var problem: String? = null

    /** Newest first. Slow (it loads pictures): call from a background thread. */
    fun load(context: Context): List<AppEntry> {
        if (Build.VERSION.SDK_INT < 26) {
            return emptyList()
        }
        problem = null
        val pm = context.packageManager
        val hidden = Prefs.hidden(context)
        val found = ArrayList<Pair<Long, AppEntry>>()
        val cursor = try {
            context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Watch Next: $e")
            problem = e.toString()
            null
        } ?: return emptyList()

        cursor.use { c ->
            fun text(column: String): String? {
                val i = c.getColumnIndex(column)
                return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
            }
            fun number(column: String): Long {
                val i = c.getColumnIndex(column)
                return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
            }
            while (c.moveToNext()) {
                // An app can keep an entry but ask for it not to be shown
                if (c.getColumnIndex("browsable") >= 0 && number("browsable") == 0L) {
                    continue
                }
                val pkg = text("package_name") ?: continue
                if (pkg in hidden) {
                    continue
                }
                val launch = text("intent_uri")?.let {
                    try {
                        Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
                    } catch (e: Exception) {
                        null
                    }
                } ?: continue
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val title = text("title") ?: continue
                val episode = text("episode_title")
                val label = if (episode.isNullOrBlank()) title else "$title · $episode"
                val picture = text("poster_art_uri") ?: text("thumbnail_uri")
                val duration = number("duration_millis")
                val position = number("last_playback_position_millis")
                val progress = if (duration > 0 && position > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else -1f
                val poster = picture?.let {
                    try {
                        val bitmap = posters.get(it) ?: Pictures.decode(context, Uri.parse(it), 640, 360)?.also { b -> posters.put(it, b) }
                        bitmap?.let { b -> BitmapDrawable(context.resources, b) }
                    } catch (e: Throwable) {
                        null
                    }
                }
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }
                val id = number("_id")
                found.add(
                    number("last_engagement_time_utc_millis") to AppEntry(
                        pkg = "watchnext:$id",
                        label = label,
                        launch = launch,
                        banner = poster,
                        icon = icon,
                        tv = true,
                        own = poster,
                        appPkg = pkg,
                        progress = progress,
                        preview = text("preview_video_uri"),
                    )
                )
                if (found.size >= MAX * 2) {
                    break
                }
            }
        }
        return found.sortedByDescending { it.first }.take(MAX).map { it.second }
    }
}
