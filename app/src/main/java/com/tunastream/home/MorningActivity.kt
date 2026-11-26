package com.tunastream.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The morning screen: the first time the home screen comes up each morning, a big clock, the
 * date, today's weather and what you were in the middle of, before the apps. Any button goes on.
 */
class MorningActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (48 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun text(size: Float, colour: Int, value: CharSequence, top: Int = 0): TextView = TextView(this).apply {
            text = value
            setTextColor(themeColor(colour))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.CENTER
            root.addView(this, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (top * dp).toInt() })
        }
        val name = Profiles.current(this)?.name
        text(26f, R.attr.tsTextSecondary, if (name != null && Profiles.inUse(this)) getString(R.string.morning_hello_name, name)
            else getString(R.string.morning_hello))
        root.addView(TextClock(this).apply {
            setTextColor(themeColor(R.attr.tsText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 110f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2))
        text(24f, R.attr.tsText, DateFormat.getDateInstance(DateFormat.FULL).format(Date()))

        // Today's weather, if TunaHome shows weather
        Weather.current?.let { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ImageView(this).apply { setImageResource(Weather.icon(w.code, w.day)) },
                LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { rightMargin = (12 * dp).toInt() })
            row.addView(TextView(this).apply {
                text = getString(R.string.morning_weather, w.temperature, getString(Weather.condition(w.code)), w.high, w.low)
                setTextColor(themeColor(R.attr.tsText))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
            root.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (28 * dp).toInt() })
        }

        // What you were in the middle of, and yesterday's time on the TV
        val later = text(18f, R.attr.tsTextSecondary, "", 28)
        thread(name = "TunaHome morning") {
            val shows = try {
                WatchNext.load(this).filter { it.progress in 0.02f..0.97f }.take(3).map { it.label }
            } catch (e: Exception) {
                emptyList()
            }
            val lines = ArrayList<String>()
            if (shows.isNotEmpty()) lines.add(getString(R.string.morning_continue, shows.joinToString(" · ")))
            val yesterday = UsageLog.yesterdaySeconds(this)
            if (yesterday >= 60) lines.add(getString(R.string.morning_yesterday, UsageLog.durationText(this, yesterday)))
            runOnUiThread { later.text = lines.joinToString("\n") }
        }

        text(15f, R.attr.tsTextSecondary, getString(R.string.morning_hint), 40)
        setContentView(root)
        Look.styleText(root)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            finish()
        }
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP) finish()
        return true
    }

    companion object {
        private const val SHOWN = "morning_shown"

        /** Shown once a day, the first time the home screen comes up between 5 and 11:59. */
        fun showIfDue(activity: Activity) {
            if (!Look.MORNING.get(activity)) return
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour !in 5..11) return
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            val prefs = Prefs.of(activity)
            if (prefs.getString(SHOWN, "") == today) return
            prefs.edit().putString(SHOWN, today).apply()
            activity.startActivity(Intent(activity, MorningActivity::class.java))
        }
    }
}
