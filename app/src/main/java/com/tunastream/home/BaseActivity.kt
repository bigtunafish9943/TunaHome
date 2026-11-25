package com.tunastream.home

import android.app.Activity
import android.os.Bundle
import android.view.View

/**
 * Every screen: wears the current theme, accent colour, text colour and text style, and is
 * rebuilt if any of them changed while it was away.
 */
abstract class BaseActivity : Activity() {
    private var themeId: String = TunaTheme.DEFAULT
    private var lookStamp = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        themeId = TunaTheme.current(this)
        lookStamp = Look.stamp(this)
        theme.applyStyle(TunaTheme.styleOf(themeId), true)
        Look.ACCENT_STYLES[Look.ACCENT.get(this)].let { if (it != 0) theme.applyStyle(it, true) }
        Look.TEXT_COLOUR_STYLES[Look.TEXT_COLOUR.get(this)].let { if (it != 0) theme.applyStyle(it, true) }
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        Look.styleText(findViewById<View>(android.R.id.content))
    }

    /** Takes the current look as this screen's own, for changes it shows by itself (fine tuning). */
    protected fun acceptLook() {
        lookStamp = Look.stamp(this)
    }

    /** Whether this screen was built with a theme or look that has since changed. */
    protected fun outOfDate(): Boolean =
        TunaTheme.current(this) != themeId || Look.stamp(this) != lookStamp

    override fun onResume() {
        super.onResume()
        // Also catches a season starting while the home screen stayed open
        if (outOfDate()) {
            recreate()
        }
    }
}
