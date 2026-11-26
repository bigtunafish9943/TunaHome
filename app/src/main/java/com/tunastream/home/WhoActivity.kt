package com.tunastream.home

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * "Who's watching?": everyone's circle in a row. Choosing one brings back that person's own
 * TunaHome (asking their PIN first, if they set one). Back keeps whoever is in now.
 */
class WhoActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val people = Profiles.all(this)
        if (people.size < 2) {
            finish()
            return
        }
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        val title = TextView(this).apply {
            text = getString(R.string.who_title)
            setTextColor(themeColor(R.attr.tsText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (40 * dp).toInt() })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        root.addView(row, LinearLayout.LayoutParams(-2, -2))
        val currentId = Profiles.currentId(this)
        var first: View? = null
        for (person in people) {
            val tile = person(person, person.id == currentId, dp)
            row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = (22 * dp).toInt()
                rightMargin = (22 * dp).toInt()
            })
            if (person.id == currentId || first == null) first = tile
        }
        val hint = TextView(this).apply {
            text = getString(R.string.who_hint)
            setTextColor(themeColor(R.attr.tsTextSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (40 * dp).toInt() })
        setContentView(root)
        Look.styleText(root)
        first?.requestFocus()
    }

    /** One person: a coloured circle with their first letter, and their name under it. */
    private fun person(person: Profiles.Profile, current: Boolean, dp: Float): View {
        val size = (120 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(person.colour)
            }
            elevation = 6 * dp
        }
        val letter = TextView(this).apply {
            text = person.name.trim().take(1).uppercase()
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        circle.addView(letter, FrameLayout.LayoutParams(-1, -1))
        // Their photo, if one was sent from a phone, in place of the letter
        Profiles.photo(this, person.id)?.let { bitmap ->
            val round = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(round).drawOval(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                })
            circle.addView(ImageView(this).apply {
                setImageBitmap(round)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            letter.visibility = View.GONE
        }
        if (person.locked) {
            val lock = ImageView(this).apply {
                setImageResource(R.drawable.ic_lock)
                setColorFilter(0xFFFFFFFF.toInt())
                // Readable on a photo too
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                val p = (3 * dp).toInt()
                setPadding(p, p, p, p)
            }
            circle.addView(lock, FrameLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt(), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = (14 * dp).toInt()
                bottomMargin = (14 * dp).toInt()
            })
        }
        box.addView(circle, LinearLayout.LayoutParams(size, size))
        val name = TextView(this).apply {
            text = person.name
            setTextColor(themeColor(R.attr.tsText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            if (current) typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((4 * dp).toInt(), themeColor(R.attr.tsAccent))
        }
        box.setOnFocusChangeListener { _, focused ->
            val scale = if (focused) 1.15f else 1f
            box.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
            circle.foreground = if (focused) ring else null
            if (focused) Sounds.play(this, Sounds.MOVE)
        }
        box.setOnClickListener { choose(person) }
        return box
    }

    private fun choose(person: Profiles.Profile) {
        Sounds.play(this, Sounds.SELECT)
        if (person.id == Profiles.currentId(this)) {
            finish()
            return
        }
        if (person.locked) {
            askPin(this, person) { switchTo(person) }
        } else {
            switchTo(person)
        }
    }

    private fun switchTo(person: Profiles.Profile) {
        Toast.makeText(this, getString(R.string.who_switching, person.name), Toast.LENGTH_SHORT).show()
        thread(name = "TunaHome profile") {
            val worked = try {
                Profiles.switchTo(applicationContext, person.id)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (worked) Layouts.restart(this)
                else Toast.makeText(this, R.string.layout_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WhoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Asks a person's own PIN; [then] runs if it's right. */
        fun askPin(activity: android.app.Activity, person: Profiles.Profile, then: () -> Unit) {
            val box = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setSingleLine()
                setTextColor(activity.themeColor(R.attr.tsText))
            }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val frame = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0) }
            frame.addView(box)
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.who_pin_title, person.name))
                .setView(frame)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (Profiles.pinMatches(person, box.text.toString())) then()
                    else Toast.makeText(activity, R.string.lock_wrong, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            box.requestFocus()
        }
    }
}
