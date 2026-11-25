package com.tunastream.home

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.text.Html
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * "What's new": after TunaHome updates, the first time the home screen shows, it lists what
 * changed since the version you had, from the notes on this project's GitHub releases.
 */
object ChangeLog {
    private const val SEEN = "changelog_seen_code"
    private const val RELEASES = "https://api.github.com/repos/bigtunafish9943/TunaHome/releases?per_page=30"

    /** Call when the home screen shows. Does nothing on a fresh install or if already seen. */
    fun showIfUpdated(activity: Activity) {
        val prefs = Prefs.of(activity)
        val current = BuildConfig.VERSION_CODE
        val seen = prefs.getInt(SEEN, 0)
        if (seen == 0) {
            // First time ever: nothing to compare with
            prefs.edit().putInt(SEEN, current).apply()
            return
        }
        if (seen >= current) {
            return
        }
        fetch(activity, seen, current) { notes ->
            if (notes != null) {
                prefs.edit().putInt(SEEN, current).apply()
                if (notes.isNotBlank()) {
                    show(activity, activity.getString(R.string.whats_new_title, BuildConfig.VERSION_NAME), notes)
                }
            }
        }
    }

    /** The notes for the version installed now, any time (Settings, Updates). */
    fun showCurrent(activity: Activity) {
        fetch(activity, BuildConfig.VERSION_CODE - 1, BuildConfig.VERSION_CODE) { notes ->
            if (notes.isNullOrBlank()) {
                android.widget.Toast.makeText(activity, R.string.whats_new_unavailable, android.widget.Toast.LENGTH_LONG).show()
            } else {
                show(activity, activity.getString(R.string.whats_new_title, BuildConfig.VERSION_NAME), notes)
            }
        }
    }

    /** Notes for releases after [from] up to [to], newest first; null if GitHub couldn't be reached. */
    private fun fetch(activity: Activity, from: Int, to: Int, done: (String?) -> Unit) {
        thread(name = "TunaHome what's new") {
            val notes = try {
                val conn = URL(RELEASES).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "TunaHome/" + BuildConfig.VERSION_NAME)
                val text = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                conn.disconnect()
                val releases = JSONArray(text)
                val parts = ArrayList<Pair<Int, String>>()
                for (i in 0 until releases.length()) {
                    val r = releases.getJSONObject(i)
                    val tag = r.optString("tag_name")
                    val code = tag.substringAfterLast('.').toIntOrNull() ?: continue
                    if (code in (from + 1)..to) {
                        parts.add(code to "## ${tag.removePrefix("v")}\n\n" + r.optString("body").trim())
                    }
                }
                parts.sortedByDescending { it.first }.joinToString("\n\n") { it.second }
            } catch (e: Exception) {
                null
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    done(notes)
                }
            }
        }
    }

    private fun show(activity: Activity, title: String, notes: String) {
        val text = TextView(activity)
        text.text = format(notes)
        text.textSize = 14f
        text.setLineSpacing(0f, 1.15f)
        text.setTextColor(activity.themeColor(R.attr.tsText))
        val pad = Math.round(Look.dp(activity, 20f))
        text.setPadding(pad, pad / 2, pad, pad)
        text.isFocusable = true
        val scroll = ScrollView(activity)
        scroll.addView(text)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** The notes' simple headings and lists as bold lines and bullets. */
    private fun format(notes: String): CharSequence {
        val html = notes.lines().joinToString("<br>") { line ->
            val t = Html.escapeHtml(line.trim())
            when {
                line.startsWith("## ") -> "<br><big><b>" + Html.escapeHtml(line.removePrefix("## ")) + "</b></big>"
                line.startsWith("### ") -> "<b>" + Html.escapeHtml(line.removePrefix("### ")) + "</b>"
                line.trimStart().startsWith("- ") -> "&#8226; " + Html.escapeHtml(line.trimStart().removePrefix("- "))
                else -> t
            }
        }
        return if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        else @Suppress("DEPRECATION") Html.fromHtml(html)
    }
}
