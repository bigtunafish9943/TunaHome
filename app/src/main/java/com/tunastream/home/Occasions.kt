package com.tunastream.home

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Holidays and your own dates: on the day, a greeting the first time the home screen comes up,
 * with a short chime, and an effect behind the apps all day (confetti, hearts, snow, bats).
 */
object Occasions {
    val ON = Look.Switch("occasions_on", true)
    private const val DATES = "occasions_dates"
    private const val SHOWN = "occasions_shown"

    class Occasion(val greeting: String, val effect: Effects.Kind)

    /** One of your own dates: a birthday ("Happy birthday, Sam!") or anything else. */
    class Mine(val name: String, val month: Int, val day: Int, val birthday: Boolean)

    fun mine(context: Context): List<Mine> = try {
        val a = JSONArray(Prefs.of(context).getString(DATES, "[]") ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Mine(o.optString("name"), o.optInt("month"), o.optInt("day"), o.optBoolean("birthday"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(context: Context, list: List<Mine>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("month", it.month).put("day", it.day).put("birthday", it.birthday)) }
        Prefs.of(context).edit().putString(DATES, a.toString()).apply()
    }

    /** Today's occasion, if there is one and they're on. */
    fun today(context: Context): Occasion? {
        if (!ON.get(context)) return null
        val c = Calendar.getInstance()
        val month = c.get(Calendar.MONTH) + 1
        val day = c.get(Calendar.DAY_OF_MONTH)
        // Yours first: a birthday on Christmas is still a birthday
        mine(context).firstOrNull { it.month == month && it.day == day }?.let {
            return Occasion(
                if (it.birthday) context.getString(R.string.occ_birthday, it.name) else context.getString(R.string.occ_yours, it.name),
                Effects.Kind.CONFETTI,
            )
        }
        val r = context.resources
        return when {
            month == 1 && day == 1 -> Occasion(r.getString(R.string.occ_new_year), Effects.Kind.CONFETTI)
            month == 2 && day == 14 -> Occasion(r.getString(R.string.occ_valentine), Effects.Kind.HEARTS)
            month == 10 && day == 31 -> Occasion(r.getString(R.string.occ_halloween), Effects.Kind.BATS)
            month == 11 && isThanksgiving(c) -> Occasion(r.getString(R.string.occ_thanksgiving), Effects.Kind.LEAVES)
            month == 12 && (day == 24 || day == 25) -> Occasion(r.getString(R.string.occ_christmas), Effects.Kind.SNOW)
            month == 12 && day == 31 -> Occasion(r.getString(R.string.occ_new_year_eve), Effects.Kind.CONFETTI)
            else -> null
        }
    }

    /** The fourth Thursday of November. */
    private fun isThanksgiving(c: Calendar): Boolean =
        c.get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY && (c.get(Calendar.DAY_OF_MONTH) - 1) / 7 == 3

    /** The greeting, once on the day, as the home screen comes up. */
    fun greetIfDue(activity: Activity) {
        val occasion = today(activity) ?: return
        val tag = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + occasion.greeting
        val prefs = Prefs.of(activity)
        if (prefs.getString(SHOWN, "") == tag) return
        prefs.edit().putString(SHOWN, tag).apply()
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val banner = TextView(activity).apply {
            text = occasion.greeting
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
            gravity = Gravity.CENTER
            setShadowLayer(18f, 0f, 3f, Color.argb(200, 0, 0, 0))
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            alpha = 0f
            isClickable = false
            isFocusable = false
        }
        decor.addView(banner, FrameLayout.LayoutParams(-1, -1))
        banner.animate().alpha(1f).setDuration(600).start()
        banner.postDelayed({
            banner.animate().alpha(0f).setDuration(900).withEndAction { decor.removeView(banner) }.start()
        }, 5500)
        try {
            // Three rising notes
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
            banner.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_ACK, 260) }, 220)
            banner.postDelayed({ tone.release() }, 900)
        } catch (e: Exception) {
            // No sound, the greeting still shows
        }
    }

    // ------------------------------------------------------------------ your dates (settings)

    fun manage(activity: SettingsActivity) {
        val list = mine(activity)
        val labels = ArrayList<String>()
        labels.add(activity.getString(R.string.occ_add))
        val months = DateFormatSymbols.getInstance().months
        list.forEach { labels.add("${it.name} · ${it.day} ${months[it.month - 1]}") }
        QuietDialog.Builder(activity)
            .setTitle(R.string.occ_yours_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) add(activity) else {
                    val gone = list[which - 1]
                    QuietDialog.Builder(activity)
                        .setTitle(gone.name)
                        .setItems(arrayOf(activity.getString(R.string.occ_remove))) { _, _ ->
                            save(activity, list.filterIndexed { i, _ -> i != which - 1 })
                            activity.changed()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun add(activity: SettingsActivity) {
        val box = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setTextColor(activity.themeColor(R.attr.tsText))
        }
        val pad = Math.round(Look.dp(activity, 20f))
        val frame = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0) }
        frame.addView(box)
        QuietDialog.Builder(activity)
            .setTitle(R.string.occ_name)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = box.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val months = DateFormatSymbols.getInstance().months.take(12)
                activity.chooseOne(activity.getString(R.string.occ_month), months, -1) { m ->
                    val days = (1..31).map { it.toString() }
                    activity.chooseOne(activity.getString(R.string.occ_day), days, -1) { d ->
                        activity.chooseOne(name, listOf(activity.getString(R.string.occ_kind_birthday), activity.getString(R.string.occ_kind_other)), 0) { k ->
                            save(activity, mine(activity) + Mine(name, m + 1, d + 1, k == 0))
                            Toast.makeText(activity, R.string.occ_added, Toast.LENGTH_SHORT).show()
                            activity.changed()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        box.requestFocus()
    }
}
