package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/**
 * Puts the TV to sleep after a while, for falling asleep to a film. Works while another app is
 * in front, through the Home button service (which keeps TunaHome running), so it needs that
 * switched on. A minute before, a message says it's about to happen and the sound fades down;
 * any button in that minute puts it off by 15 minutes (as a TV asks "still watching?"). The
 * volume is put back afterwards, so the morning doesn't start silent.
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

    private const val FADE_STEPS = 12
    private const val EXTEND_MINUTES = 15

    /** The volume before the fade began; -1 when it isn't fading. */
    private var volumeBefore = -1
    private var fadeStep = 0

    private fun audio(): AudioManager? = app?.getSystemService(AudioManager::class.java)?.takeIf { !it.isVolumeFixed }

    private val warn = Runnable {
        app?.let { Toast.makeText(it, R.string.sleep_warning, Toast.LENGTH_LONG).show() }
        val audio = audio() ?: return@Runnable
        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fadeStep = 0
        main.postDelayed(fade, 5_000L)
    }

    /** Down to a quarter of the volume over the last minute. */
    private val fade: Runnable = object : Runnable {
        override fun run() {
            val audio = audio() ?: return
            if (volumeBefore <= 0) return
            fadeStep++
            val target = volumeBefore - (volumeBefore * 3 / 4) * fadeStep / FADE_STEPS
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceAtLeast(1), 0)
            } catch (e: Exception) {
                return
            }
            if (fadeStep < FADE_STEPS) main.postDelayed(this, 5_000L)
        }
    }

    private fun restoreVolume() {
        main.removeCallbacks(fade)
        val before = volumeBefore
        volumeBefore = -1
        if (before < 0) return
        try {
            audio()?.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        } catch (e: Exception) {
            // Do not disturb, or the TV keeps the volume itself
        }
    }

    /**
     * A button was pressed anywhere (from the Home button service). In the last minute, that
     * means someone's still watching: the timer moves 15 minutes on.
     */
    fun pressed() {
        if (endsAt == 0L || endsAt - SystemClock.elapsedRealtime() > 60_000L) return
        val context = app ?: return
        restoreVolume()
        set(context, EXTEND_MINUTES)
        Toast.makeText(context, context.getString(R.string.sleep_extended, EXTEND_MINUTES), Toast.LENGTH_LONG).show()
    }

    private val sleep = Runnable {
        endsAt = 0
        // Back to how loud it was, once the screen is off
        main.postDelayed({ restoreVolume() }, 4_000L)
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
        restoreVolume()
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
