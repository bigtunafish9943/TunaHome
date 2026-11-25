package com.tunastream.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rows on the home screen, in order. Three are built in (Favourites, TV apps, Mobile apps)
 * and the viewer can add their own. Any row can be hidden, renamed or moved.
 */
object Rows {
    const val FAVOURITES = "favourites"
    const val TV = "tv"
    const val MOBILE = "mobile"
    const val RECENT = "recent"
    const val WATCH_NEXT = "watchnext"
    const val NOW_PLAYING = "nowplaying"
    const val SIDELOADED = "sideloaded"
    const val GAMES = "games"
    const val PC_GAMES = "pcgames"
    const val VIDEO = "video"
    const val MUSIC = "music"
    const val CUSTOM = "custom"

    private const val KEY = "rows"

    /** Lines of apps: as many as it takes to show every app without scrolling sideways. */
    const val WRAP = 0
    const val MAX_LINES = 5

    /** How a section orders its apps. */
    const val SORT_OWN = 0
    const val SORT_AZ = 1
    const val SORT_USED = 2
    const val SORT_NEWEST = 3
    const val SORT_UPDATED = 4
    val SORT_NAMES = intArrayOf(
        R.string.sort_own, R.string.sort_az, R.string.sort_used, R.string.sort_newest, R.string.sort_updated,
    )

    class Row(
        val id: String,
        val kind: String,
        /** Null shows the row's usual name. */
        var title: String?,
        var visible: Boolean,
        /** A row of the viewer's own: its apps, in order. */
        val apps: MutableList<String> = ArrayList(),
        /** How many lines of apps this section shows: 1 to 5, or [WRAP] for as many as needed. */
        var lines: Int = 1,
        /** This section's own card size (a position in [Look.CARD_SCALES]), or -1 for the usual. */
        var cardSize: Int = -1,
        /** One of the SORT_ orders. */
        var sort: Int = SORT_OWN,
        /** The first app shown twice the size (single-line sections only). */
        var spotlight: Boolean = false,
        /** Its own card style (one of Look's TILE_ styles), or -1 for the one set for every card. */
        var style: Int = -1,
    ) {
        fun name(context: Context): String = title ?: context.getString(
            when (kind) {
                FAVOURITES -> R.string.favourites
                TV -> R.string.tv_apps
                MOBILE -> R.string.mobile_apps
                RECENT -> R.string.recent
                WATCH_NEXT -> R.string.watch_next
                NOW_PLAYING -> R.string.np_title
                SIDELOADED -> R.string.sideloaded
                GAMES -> R.string.games
                PC_GAMES -> R.string.pc_games
                VIDEO -> R.string.video_apps
                MUSIC -> R.string.music_apps
                else -> R.string.row_untitled
            }
        )

        val custom: Boolean
            get() = kind == CUSTOM

        /** Sections in an order of their own (newest first, or what the TV says) can't be sorted or moved in. */
        val ownOrder: Boolean
            get() = kind == RECENT || kind == WATCH_NEXT || kind == NOW_PLAYING || kind == PC_GAMES
    }

    fun load(context: Context): MutableList<Row> {
        val text = Prefs.of(context).getString(KEY, null)
        val rows = ArrayList<Row>()
        if (text != null) {
            try {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val apps = ArrayList<String>()
                    o.optJSONArray("apps")?.let { a -> for (j in 0 until a.length()) apps.add(a.getString(j)) }
                    rows.add(
                        Row(
                            o.getString("id"),
                            o.getString("kind"),
                            if (o.has("title") && !o.isNull("title")) o.getString("title") else null,
                            o.optBoolean("visible", true),
                            apps,
                            o.optInt("lines", 1).coerceIn(WRAP, MAX_LINES),
                            o.optInt("card_size", -1),
                            o.optInt("sort", SORT_OWN).coerceIn(SORT_OWN, SORT_UPDATED),
                            o.optBoolean("spotlight", false),
                            o.optInt("style", -1).coerceIn(-1, Look.TILE_ICON_NAME),
                        )
                    )
                }
            } catch (e: Exception) {
                rows.clear()
            }
        }
        // The built-in rows are always in the list, even if only hidden
        for (kind in listOf(FAVOURITES, TV, MOBILE)) {
            if (rows.none { it.kind == kind }) {
                rows.add(Row(kind, kind, null, true))
            }
        }
        // Sideloaded apps came later: hidden to begin with, under Mobile apps
        if (rows.none { it.kind == SIDELOADED }) {
            val at = rows.indexOfFirst { it.kind == MOBILE }
            rows.add(if (at >= 0) at + 1 else rows.size, Row(SIDELOADED, SIDELOADED, null, false))
        }
        // PC games came later: under Watch Next (it hides itself without TunaReceive)
        if (rows.none { it.kind == PC_GAMES }) {
            val at = rows.indexOfFirst { it.kind == WATCH_NEXT }
            rows.add(if (at >= 0) at + 1 else rows.size, Row(PC_GAMES, PC_GAMES, null, true))
        }
        // Sections filled by what sort of app it is came later: hidden, after the rest
        for (kind in listOf(GAMES, VIDEO, MUSIC)) {
            if (rows.none { it.kind == kind }) {
                rows.add(Row(kind, kind, null, false))
            }
        }
        // Now playing came later: at the top (it only shows when switched on and something plays)
        if (rows.none { it.kind == NOW_PLAYING }) {
            rows.add(0, Row(NOW_PLAYING, NOW_PLAYING, null, true))
        }
        // Watch Next came later: shown under Favourites (it hides itself while empty)
        if (rows.none { it.kind == WATCH_NEXT }) {
            val at = rows.indexOfFirst { it.kind == FAVOURITES }
            rows.add(if (at >= 0) at + 1 else 0, Row(WATCH_NEXT, WATCH_NEXT, null, true))
        }
        // Recently opened came later: it starts hidden, under Favourites
        if (rows.none { it.kind == RECENT }) {
            val at = rows.indexOfFirst { it.kind == FAVOURITES }
            rows.add(if (at >= 0) at + 1 else 0, Row(RECENT, RECENT, null, false))
        }
        return rows
    }

    /**
     * Saves the rows. [restyle] tells open screens to rebuild; leave it off for changes the home
     * screen has already shown, such as an app moved within a row.
     */
    fun save(context: Context, rows: List<Row>, restyle: Boolean = true) {
        val array = JSONArray()
        for (row in rows) {
            val o = JSONObject()
            o.put("id", row.id)
            o.put("kind", row.kind)
            o.put("title", row.title ?: JSONObject.NULL)
            o.put("visible", row.visible)
            o.put("lines", row.lines)
            o.put("card_size", row.cardSize)
            o.put("sort", row.sort)
            o.put("spotlight", row.spotlight)
            o.put("style", row.style)
            if (row.custom) {
                o.put("apps", JSONArray(row.apps))
            }
            array.put(o)
        }
        Prefs.of(context).edit().putString(KEY, array.toString()).apply()
        if (restyle) {
            Look.touch(context)
        }
    }

    fun add(context: Context, title: String): Row {
        val rows = load(context)
        val row = Row("custom-" + System.currentTimeMillis(), CUSTOM, title, true)
        // New rows go straight under Favourites, where they are easy to find
        val at = rows.indexOfFirst { it.kind == FAVOURITES }
        rows.add(if (at >= 0) at + 1 else 0, row)
        save(context, rows)
        return row
    }

    fun update(context: Context, id: String, restyle: Boolean = true, change: (Row) -> Unit) {
        val rows = load(context)
        rows.firstOrNull { it.id == id }?.let(change)
        save(context, rows, restyle)
    }
}
