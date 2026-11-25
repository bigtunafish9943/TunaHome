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
    const val CUSTOM = "custom"

    private const val KEY = "rows"

    /** Lines of apps: as many as it takes to show every app without scrolling sideways. */
    const val WRAP = 0
    const val MAX_LINES = 5

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
    ) {
        fun name(context: Context): String = title ?: context.getString(
            when (kind) {
                FAVOURITES -> R.string.favourites
                TV -> R.string.tv_apps
                MOBILE -> R.string.mobile_apps
                RECENT -> R.string.recent
                else -> R.string.row_untitled
            }
        )

        val custom: Boolean
            get() = kind == CUSTOM
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
