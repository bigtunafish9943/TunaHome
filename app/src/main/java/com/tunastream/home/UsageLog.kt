package com.tunastream.home

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Your week on the TV: how long each app was on screen, day by day, kept on the TV for two
 * weeks and never sent anywhere. Counted by the Home button service as apps come to the front,
 * and only while the screen is on.
 */
object UsageLog {
    private const val FILE = "usage"
    private const val KEEP_DAYS = 14
    private const val MIN_SHOWN_S = 60

    private const val PROFILE = "@p:"

    /** The app in front now, as far as the Home button service has seen. */
    var current: String? = null
        private set
    private var since = 0L
    private var screenOn = true
    private val launchable = HashMap<String, Boolean>()

    /** [pkg] came to the front (a window of it, at least). */
    fun front(context: Context, pkg: String) {
        if (pkg == current || !isApp(context, pkg)) return
        close(context)
        current = pkg
        since = SystemClock.elapsedRealtime()
    }

    fun screen(context: Context, on: Boolean) {
        if (on == screenOn) return
        if (!on) close(context)
        screenOn = on
        since = SystemClock.elapsedRealtime()
    }

    /** Adds the time since the current app came up (or the screen came on) to today. */
    private fun close(context: Context) {
        val pkg = current ?: return
        if (!screenOn || since == 0L) return
        val seconds = (SystemClock.elapsedRealtime() - since) / 1000
        since = SystemClock.elapsedRealtime()
        // A jump of many hours means the TV slept without telling us: not counted
        if (seconds <= 0 || seconds > 12 * 3600) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val key = day(0)
        val today = try {
            JSONObject(prefs.getString(key, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        today.put(pkg, today.optLong(pkg) + seconds)
        // The person's own total, for their daily limit (TunaHome itself isn't counted)
        if (pkg != context.packageName) {
            Profiles.currentId(context)?.let { id -> today.put(PROFILE + id, today.optLong(PROFILE + id) + seconds) }
        }
        val edit = prefs.edit().putString(key, today.toString())
        // Older than two weeks: gone
        for (k in prefs.all.keys) {
            if (k < day(KEEP_DAYS)) edit.remove(k)
        }
        edit.apply()
    }

    private fun isApp(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        return synchronized(launchable) {
            launchable.getOrPut(pkg) {
                val pm = context.packageManager
                try {
                    pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    /** "2026-11-26", [daysAgo] days back. */
    private fun day(daysAgo: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(c.time)
    }

    /** Seconds per app over the last [days] days (today included), most first. */
    private fun totals(context: Context, days: Int): List<Pair<String, Long>> {
        close(context)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val sum = HashMap<String, Long>()
        for (d in 0 until days) {
            val obj = try {
                JSONObject(prefs.getString(day(d), "{}") ?: "{}")
            } catch (e: Exception) {
                continue
            }
            for (k in obj.keys()) if (!k.startsWith("@")) sum[k] = (sum[k] ?: 0) + obj.optLong(k)
        }
        return sum.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    private fun dayTotal(context: Context, daysAgo: Int): Long {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val obj = try {
            JSONObject(prefs.getString(day(daysAgo), "{}") ?: "{}")
        } catch (e: Exception) {
            return 0
        }
        var total = 0L
        for (k in obj.keys()) if (!k.startsWith("@")) total += obj.optLong(k)
        return total
    }

    /** Seconds the app [pkg] has been on screen today, the time it's been up now included. */
    fun appToday(context: Context, pkg: String): Long = today(context, pkg) + if (current == pkg) openSeconds() else 0

    /** Seconds of apps the person [profileId] has had today, now included. */
    fun profileToday(context: Context, profileId: String): Long =
        today(context, PROFILE + profileId) +
            if (current != null && current != context.packageName && Profiles.currentId(context) == profileId) openSeconds() else 0

    private fun openSeconds(): Long =
        if (!screenOn || since == 0L) 0 else ((SystemClock.elapsedRealtime() - since) / 1000).coerceIn(0, 12 * 3600)

    private fun today(context: Context, key: String): Long = try {
        JSONObject(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(day(0), "{}") ?: "{}").optLong(key)
    } catch (e: Exception) {
        0
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        since = SystemClock.elapsedRealtime()
    }

    fun yesterdaySeconds(context: Context): Long = dayTotal(context, 1)

    fun durationText(context: Context, seconds: Long): String = duration(context, seconds)

    private fun duration(context: Context, seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes < 60) context.getString(R.string.usage_minutes, minutes.coerceAtLeast(1))
        else context.getString(R.string.usage_hours, minutes / 60, minutes % 60)
    }

    /** The week as text: the total, each day, and the apps used most. */
    fun show(activity: Activity) {
        if (HomeButtonService.running == null) {
            android.widget.Toast.makeText(activity, R.string.usage_needs_service, android.widget.Toast.LENGTH_LONG).show()
        }
        val apps = totals(activity, 7).filter { it.second >= MIN_SHOWN_S }
        val text = StringBuilder()
        if (apps.isEmpty()) {
            text.append(activity.getString(R.string.usage_none))
        } else {
            text.append(activity.getString(R.string.usage_total, duration(activity, apps.sumOf { it.second }))).append("\n\n")
            val dayName = SimpleDateFormat("EEE", Locale.getDefault())
            val week = (6 downTo 0).map { ago ->
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -ago)
                ago to dayName.format(Date(c.timeInMillis))
            }
            val longest = week.maxOf { dayTotal(activity, it.first) }.coerceAtLeast(1)
            for ((ago, name) in week) {
                val t = dayTotal(activity, ago)
                val bar = "▇".repeat(((t * 12) / longest).toInt())
                text.append(name).append("  ").append(bar).append(if (t > 0) "  " + duration(activity, t) else "").append('\n')
            }
            text.append('\n')
            val pm = activity.packageManager
            for ((pkg, seconds) in apps.take(8)) {
                val label = if (pkg == activity.packageName) activity.getString(R.string.usage_home_screen) else try {
                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                text.append(label).append(": ").append(duration(activity, seconds)).append('\n')
            }
        }
        QuietDialog.Builder(activity)
            .setTitle(R.string.usage_title)
            .setMessage(text.toString().trimEnd())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.usage_clear) { _, _ -> clear(activity) }
            .show()
    }
}
