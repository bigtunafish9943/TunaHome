package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/**
 * Puts the TV to sleep after a while, for falling asleep to a film. Works while another app is
 * in front, through the Home button service (which keeps TunaHome running), so it needs that
 * switched on. A minute before, a message says it's about to happen.
 */
object SleepTimer {
    private val CHOICES = intArrayOf(0, 15, 30, 45, 60, 90, 120, 180)
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** When the TV goes to sleep (elapsed time); 0 for no timer. */
    private var endsAt = 0L

    /** Whole minutes left, rounded up; 0 when no timer is set. */
    fun minutesLeft(): Int {
        if (endsAt == 0L) return 0
        val left = endsAt - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else ((left + 59_999) / 60_000).toInt()
    }

    private val warn = Runnable {
        app?.let { Toast.makeText(it, R.string.sleep_warning, Toast.LENGTH_LONG).show() }
    }

    private val sleep = Runnable {
        endsAt = 0
        val service = HomeButtonService.running
        val done = Build.VERSION.SDK_INT >= 28 && service != null &&
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        if (!done) {
            app?.let { Toast.makeText(it, R.string.act_failed, Toast.LENGTH_LONG).show() }
        }
    }

    fun set(context: Context, minutes: Int) {
        app = context.applicationContext
        main.removeCallbacks(warn)
        main.removeCallbacks(sleep)
        if (minutes <= 0) {
            endsAt = 0
            return
        }
        val ms = minutes * 60_000L
        endsAt = SystemClock.elapsedRealtime() + ms
        if (minutes > 1) {
            main.postDelayed(warn, ms - 60_000L)
        }
        main.postDelayed(sleep, ms)
    }

    /** Asks how long, then sets the timer; [changed] runs after. */
    fun choose(activity: Activity, changed: () -> Unit = {}) {
        if (HomeButtonService.running == null || Build.VERSION.SDK_INT < 28) {
            Toast.makeText(activity, R.string.act_needs_service, Toast.LENGTH_LONG).show()
            return
        }
        val names = CHOICES.map {
            if (it == 0) activity.getString(R.string.sleep_off)
            else activity.resources.getQuantityString(R.plurals.sleep_minutes, it, it)
        }
        val left = minutesLeft()
        val title = if (left > 0) activity.getString(R.string.sleep_title_left, left) else activity.getString(R.string.sleep_title)
        activity.chooseOne(title, names, if (left > 0) -1 else 0) { which ->
            set(activity, CHOICES[which])
            if (CHOICES[which] > 0) {
                Toast.makeText(activity, activity.getString(R.string.sleep_set, names[which]), Toast.LENGTH_LONG).show()
            }
            changed()
        }
    }
}
