package com.tunastream.home

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Set up from your phone": shows the address and code to type on a phone on the same Wi-Fi,
 * and runs the little web server only while this screen is open. What the phone changes is
 * listed here as it happens. Back closes it; the home screen then shows the changes.
 */
class PhoneSetupActivity : BaseActivity() {
    private var server: PhoneServer? = null
    private lateinit var addressView: TextView
    private lateinit var codeView: TextView
    private lateinit var log: TextView
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val side = (96 * dp).toInt()
            setPadding(side, (54 * dp).toInt(), side, (54 * dp).toInt())
        }
        fun text(size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(colour)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val main = themeColor(R.attr.tsText)
        val soft = themeColor(R.attr.tsTextSecondary)
        val accent = themeColor(R.attr.tsAccent)
        root.addView(text(32f, main).apply { setText(R.string.phone_title) })
        root.addView(text(16f, soft).apply {
            setText(R.string.phone_step_wifi)
            setPadding(0, (24 * dp).toInt(), 0, 0)
        })
        root.addView(text(16f, soft).apply {
            setText(R.string.phone_step_open)
            setPadding(0, (18 * dp).toInt(), 0, 0)
        })
        addressView = text(40f, accent, bold = true)
        root.addView(addressView)
        root.addView(text(16f, soft).apply {
            setText(R.string.phone_step_code)
            setPadding(0, (18 * dp).toInt(), 0, 0)
        })
        codeView = text(48f, main, bold = true).apply { letterSpacing = 0.15f }
        root.addView(codeView)
        log = text(15f, soft).apply { setPadding(0, (28 * dp).toInt(), 0, 0) }
        root.addView(log)
        root.addView(text(13f, soft).apply {
            setText(R.string.phone_back)
            setPadding(0, (20 * dp).toInt(), 0, 0)
            alpha = 0.7f
        })
        setContentView(root)
        Look.styleText(root)
    }

    override fun onStart() {
        super.onStart()
        val made = PhoneServer(applicationContext) { said -> runOnUiThread { note(said) } }
        if (!made.start()) {
            addressView.setText(R.string.phone_failed)
            return
        }
        server = made
        val ip = PhoneServer.address(this)
        addressView.text = if (ip == null) getString(R.string.phone_no_network)
        else "http://${ip.hostAddress}:${made.port}"
        codeView.text = made.code
    }

    override fun onStop() {
        server?.let {
            it.stop()
            if (it.changed) {
                // The home screen loads again with everything the phone changed
                Prefs.appsChanged = true
                Look.touch(this)
            }
        }
        server = null
        super.onStop()
    }

    private fun note(said: String) {
        server?.let { codeView.text = it.code }
        lines.addFirst(said)
        while (lines.size > 6) lines.removeLast()
        log.text = lines.joinToString("\n")
    }
}
