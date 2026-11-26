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
    private lateinit var qr: android.widget.ImageView
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = (96 * dp).toInt()
            setPadding(side, (54 * dp).toInt(), side, (54 * dp).toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        screen.addView(root, LinearLayout.LayoutParams(0, -2, 1f))
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
        // The quick way: a phone's camera opens the page, already signed in
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        qr = android.widget.ImageView(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 18 * dp
            }
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        side.addView(qr, LinearLayout.LayoutParams((300 * dp).toInt(), (300 * dp).toInt()))
        side.addView(text(15f, soft).apply {
            setText(R.string.phone_qr)
            gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }, LinearLayout.LayoutParams((300 * dp).toInt(), -2))
        screen.addView(side, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (48 * dp).toInt() })
        setContentView(screen)
        Look.styleText(screen)
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
        showQr()
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

    /** The address with the code in it: scanning it opens the page signed in. */
    private fun showQr() {
        val made = server ?: return
        val ip = PhoneServer.address(this) ?: return
        val size = (280 * resources.displayMetrics.density).toInt()
        QrCode.make("http://${ip.hostAddress}:${made.port}/#code=${made.code}", size)?.let { qr.setImageBitmap(it) }
    }

    private fun note(said: String) {
        server?.let {
            if (codeView.text.toString() != it.code) {
                codeView.text = it.code
                showQr()
            }
        }
        lines.addFirst(said)
        while (lines.size > 6) lines.removeLast()
        log.text = lines.joinToString("\n")
    }
}
