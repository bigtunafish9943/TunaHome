package com.tunastream.home

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import java.util.Calendar

/**
 * Night dimming: from the chosen hour the home screen darkens and warms a little over half an
 * hour, like a phone's night light, until 6 in the morning. TunaHome's own screens only: apps
 * and games are left as they are.
 */
object NightDim {
    /** Off, or the hour it starts. */
    val FROM = Look.Choice(
        "look_night_dim", 0, intArrayOf(R.string.off, R.string.night_dim_21, R.string.night_dim_22, R.string.night_dim_23),
    )
    private val HOURS = intArrayOf(0, 21, 22, 23)
    private const val RAMP_MIN = 30
    private const val TAG = "tuna-night-dim"

    /** How far into the night it is, 0 (day) to 1 (fully dimmed). */
    fun amount(activity: Activity): Float {
        val start = HOURS.getOrElse(FROM.get(activity)) { 0 }
        if (start == 0) return 0f
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        if (hour in 6 until start) return 0f
        val minutesIn = if (hour >= start) (hour - start) * 60 + c.get(Calendar.MINUTE) else 24 * 60
        return (minutesIn.toFloat() / RAMP_MIN).coerceIn(0f, 1f)
    }

    /** Puts the dimming over [activity]'s window as it should be now (or takes it off). */
    fun apply(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        var layer = decor.findViewWithTag<View>(TAG)
        val a = amount(activity)
        if (a <= 0f) {
            layer?.let { decor.removeView(it) }
            return
        }
        if (layer == null) {
            layer = View(activity).apply {
                tag = TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            decor.addView(layer, ViewGroup.LayoutParams(-1, -1))
        }
        // Darker, and a warm amber, at most about 40%
        layer.setBackgroundColor(Color.argb((a * 105).toInt(), 40, 18, 0))
        layer.bringToFront()
    }
}
