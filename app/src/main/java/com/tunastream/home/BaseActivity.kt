package com.tunastream.home

import android.app.Activity
import android.os.Bundle
import android.view.View

/**
 * Every screen: wears the current theme and text style, and is rebuilt if either changed while
 * it was away.
 */
abstract class BaseActivity : Activity() {
    private var themeId: String = TunaTheme.DEFAULT
    private var lookStamp = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        themeId = TunaTheme.current(this)
        lookStamp = Look.stamp(this)
        theme.applyStyle(TunaTheme.styleOf(themeId), true)
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        Look.styleText(findViewById<View>(android.R.id.content))
    }

    /** Whether this screen was built with a theme or text style that has since changed. */
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
