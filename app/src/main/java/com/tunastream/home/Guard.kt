package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen-time limits and kids mode, kept by the Home button service:
 *  - a daily limit for an app (everyone's time in it counts), or for a person (all their apps);
 *  - kids mode, where only the apps a grown-up allowed may open.
 * Five minutes before a limit a message warns; at the limit the app gives way to the home screen
 * and Time's up asks a grown-up for more time.
 */
object Guard {
    private const val APP_LIMITS = "app_limits"
    private const val EXTRA = "limit_extra"
    private const val WARNED = "limit_warned"
    const val WARN_S = 5 * 60L

    /** Minutes a day for each app that has a limit. */
    val CHOICES = intArrayOf(0, 15, 30, 45, 60, 90, 120, 180, 240)

    /** Never blocked: the TV's own pop-ups and keyboards don't count as opening an app. */
    private val ALWAYS = setOf("android", "com.android.systemui")

    /** Blocked in kids mode unless allowed, launchable or not. */
    private val KIDS_BLOCKED = setOf(
        "com.android.tv.settings", "com.android.vending", "com.google.android.packageinstaller",
        "com.android.packageinstaller", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
    )

    fun appLimits(context: Context): Map<String, Int> {
        val o = try {
            JSONObject(Prefs.of(context).getString(APP_LIMITS, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        return o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 }
    }

    fun appLimit(context: Context, pkg: String): Int = appLimits(context)[pkg] ?: 0

    fun setAppLimit(context: Context, pkg: String, minutes: Int) {
        val all = appLimits(context).toMutableMap()
        if (minutes > 0) all[pkg] = minutes else all.remove(pkg)
        Prefs.of(context).edit().putString(APP_LIMITS, JSONObject(all as Map<*, *>).toString()).apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    /** More minutes a grown-up gave today, for an app ("app:<pkg>") or person ("person:<id>"). */
    private fun extra(context: Context, key: String): Int {
        val o = try {
            JSONObject(Prefs.of(context).getString(EXTRA, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        return if (o.optString("day") == today()) o.optInt(key) else 0
    }

    fun giveMore(context: Context, key: String, minutes: Int) {
        val o = try {
            JSONObject(Prefs.of(context).getString(EXTRA, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val fresh = if (o.optString("day") == today()) o else JSONObject().put("day", today())
        fresh.put(key, fresh.optInt(key) + minutes)
        Prefs.of(context).edit().putString(EXTRA, fresh.toString()).apply()
    }

    /** What stops [pkg] now, if anything: a key ("app:", "person:" or "kids:") and seconds left. */
    class Limit(val key: String, val left: Long, val label: String)

    fun kidsBlocks(context: Context, pkg: String): Boolean {
        val person = Profiles.current(context) ?: return false
        if (!person.kids || pkg == context.packageName || pkg in ALWAYS || pkg in person.allowed || isKeyboard(context, pkg)) return false
        return pkg in KIDS_BLOCKED || launchable(context, pkg)
    }

    /** The tightest limit on [pkg] right now, or null when none applies. */
    fun limitFor(context: Context, pkg: String): Limit? {
        if (pkg == context.packageName || pkg in ALWAYS || isKeyboard(context, pkg) || !launchable(context, pkg)) return null
        var best: Limit? = null
        val app = appLimit(context, pkg)
        if (app > 0) {
            val key = "app:$pkg"
            val left = (app + extra(context, key)) * 60L - UsageLog.appToday(context, pkg)
            best = Limit(key, left, appName(context, pkg))
        }
        val person = Profiles.current(context)
        if (person != null && person.dailyMinutes > 0) {
            val key = "person:${person.id}"
            val left = (person.dailyMinutes + extra(context, key)) * 60L - UsageLog.profileToday(context, person.id)
            if (best == null || left < best.left) best = Limit(key, left, person.name)
        }
        return best
    }

    /**
     * Called as apps come to the front and twice a minute while one is up. Returns true when
     * [pkg] had to give way to the home screen.
     */
    fun check(context: Context, pkg: String): Boolean {
        if (kidsBlocks(context, pkg)) {
            stop(context, TimesUpActivity.REASON_KIDS, "kids:$pkg", appName(context, pkg))
            return true
        }
        val limit = limitFor(context, pkg) ?: return false
        if (limit.left <= 0) {
            stop(context, TimesUpActivity.REASON_TIME, limit.key, limit.label)
            return true
        }
        if (limit.left <= WARN_S) {
            val tag = today() + limit.key
            val prefs = Prefs.of(context)
            if (prefs.getString(WARNED, "") != tag) {
                prefs.edit().putString(WARNED, tag).apply()
                val minutes = ((limit.left + 59) / 60).toInt()
                Toast.makeText(context, context.resources.getQuantityString(R.plurals.limit_warning, minutes, limit.label, minutes),
                    Toast.LENGTH_LONG).show()
            }
        }
        return false
    }

    private fun stop(context: Context, reason: String, key: String, label: String) {
        try {
            context.startActivity(
                Intent(context, TimesUpActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(TimesUpActivity.EXTRA_REASON, reason)
                    .putExtra(TimesUpActivity.EXTRA_KEY, key)
                    .putExtra(TimesUpActivity.EXTRA_LABEL, label)
            )
        } catch (e: Exception) {
            // Couldn't show it: the home screen at least
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * The on-screen keyboard or the screen saver coming up isn't an app being opened, even if it
     * has an icon of its own.
     */
    fun isKeyboard(context: Context, pkg: String): Boolean = try {
        val keyboard = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == pkg
        // The screen saver (Aerial Views, TunaReceive's covers) isn't an app being opened either
        keyboard || android.provider.Settings.Secure.getString(context.contentResolver, "screensaver_components")
            ?.split(',')?.any { it.substringBefore('/') == pkg } == true
    } catch (e: Exception) {
        false
    }

    private val launchable = HashMap<String, Boolean>()

    private fun launchable(context: Context, pkg: String): Boolean = synchronized(launchable) {
        launchable.getOrPut(pkg) {
            val pm = context.packageManager
            try {
                pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    fun appName(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        pkg
    }

    // ------------------------------------------------------------------ grown-ups

    /** Unlocked by a grown-up's PIN until then (elapsed time), for TunaHome settings in kids mode. */
    private var grownUpUntil = 0L

    fun kidsModeOn(context: Context): Boolean = Profiles.current(context)?.kids == true

    fun grownUpRecently(): Boolean = android.os.SystemClock.elapsedRealtime() < grownUpUntil

    /** Asks a grown-up's PIN (any of them), then [then]. */
    fun askGrownUp(activity: android.app.Activity, cancelled: () -> Unit = {}, then: () -> Unit) {
        val grownUps = Profiles.grownUps(activity)
        if (grownUps.isEmpty()) {
            Toast.makeText(activity, R.string.kids_need_grown_up_pin, Toast.LENGTH_LONG).show()
            cancelled()
            return
        }
        val box = android.widget.EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(activity.themeColor(R.attr.tsText))
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0) }
        frame.addView(box)
        QuietDialog.Builder(activity)
            .setTitle(R.string.kids_grown_up_pin)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = box.text.toString()
                if (grownUps.any { Profiles.pinMatches(it, pin) }) {
                    grownUpUntil = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L
                    then()
                } else {
                    Toast.makeText(activity, R.string.lock_wrong, Toast.LENGTH_LONG).show()
                    cancelled()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled() }
            .setOnCancelListener { cancelled() }
            .show()
        box.requestFocus()
    }
}
