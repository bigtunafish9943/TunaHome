package com.tunastream.home

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Time's up: shown instead of an app that has used its time today, or one kids mode doesn't
 * allow. OK goes to the home screen; a grown-up's PIN gives more time or allows the app.
 */
class TimesUpActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_TIME
        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        val kids = reason == REASON_KIDS
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (48 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(if (kids) R.string.kids_not_allowed_title else R.string.limit_title)
            setTextColor(themeColor(R.attr.tsText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = if (kids) getString(R.string.kids_not_allowed_body, label)
            else if (key.startsWith("person:")) getString(R.string.limit_person_body, label)
            else getString(R.string.limit_app_body, label)
            setTextColor(themeColor(R.attr.tsTextSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (16 * dp).toInt(); bottomMargin = (32 * dp).toInt() })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun button(text: Int, action: () -> Unit): Button = Button(this).apply {
            setText(text)
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = (10 * dp).toInt()
                rightMargin = (10 * dp).toInt()
            })
        }
        val ok = button(R.string.limit_ok) { goHome() }
        if (kids) {
            button(R.string.kids_allow) {
                Guard.askGrownUp(this) {
                    val person = Profiles.current(this) ?: return@askGrownUp
                    val pkg = key.removePrefix("kids:")
                    Profiles.setAllowed(this, person.id, person.allowed + pkg)
                    Toast.makeText(this, getString(R.string.kids_allowed_now, label), Toast.LENGTH_LONG).show()
                    goHome()
                }
            }
        } else {
            button(R.string.limit_more) {
                Guard.askGrownUp(this) {
                    val minutes = intArrayOf(15, 30, 60)
                    chooseOne(getString(R.string.limit_more_title, label),
                        minutes.map { resources.getQuantityString(R.plurals.sleep_minutes, it, it) }, -1) { which ->
                        Guard.giveMore(this, key, minutes[which])
                        Toast.makeText(this, getString(R.string.limit_more_done, minutes[which], label), Toast.LENGTH_LONG).show()
                        goHome()
                    }
                }
            }
        }
        root.addView(buttons)
        setContentView(root)
        Look.styleText(root)
        ok.requestFocus()
        Sounds.play(this, Sounds.SELECT)
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goHome()
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_KEY = "key"
        const val EXTRA_LABEL = "label"
        const val REASON_TIME = "time"
        const val REASON_KIDS = "kids"
    }
}
