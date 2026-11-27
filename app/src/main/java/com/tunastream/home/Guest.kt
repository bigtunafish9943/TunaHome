package com.tunastream.home

import android.app.Activity
import android.content.Context
import android.widget.Toast

/**
 * Guest mode: a clean home screen for visitors. Watch Next, recently used apps, now playing and
 * PC games stay out of sight, Open apps is empty, and nobody is asked who's watching. It ends
 * when the TV goes to sleep, or from the side menu (with the settings PIN if there is one).
 */
object Guest {
    private const val KEY = "guest_mode"

    /** Rows that show what someone watched or played. */
    private val PRIVATE_ROWS = setOf(Rows.WATCH_NEXT, Rows.RECENT, Rows.NOW_PLAYING, Rows.PC_GAMES, Rows.FOR_YOU)

    fun on(context: Context): Boolean = Prefs.of(context).getBoolean(KEY, false)

    fun hides(context: Context, rowKind: String): Boolean = on(context) && rowKind in PRIVATE_ROWS

    fun start(context: Context) {
        Prefs.of(context).edit().putBoolean(KEY, true).apply()
        Look.touch(context)
        Toast.makeText(context, R.string.guest_started, Toast.LENGTH_LONG).show()
    }

    /** Back to normal; asks the settings PIN first when there is one. */
    fun leave(activity: Activity) {
        val go = {
            end(activity)
            Toast.makeText(activity, R.string.guest_ended, Toast.LENGTH_SHORT).show()
        }
        if (AppLock.hasPin(activity)) AppLock.ask(activity, activity.getString(R.string.guest_mode)) { go() } else go()
    }

    /** The TV went to sleep (or someone left guest mode). */
    fun end(context: Context) {
        if (!on(context)) return
        Prefs.of(context).edit().putBoolean(KEY, false).apply()
        Look.touch(context)
    }
}
