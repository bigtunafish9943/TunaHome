package com.tunastream.home

import android.app.Activity
import android.os.Bundle

/** Every screen: wears the current theme, and is rebuilt if the theme changed while it was away. */
abstract class BaseActivity : Activity() {
    protected var themeId: String = TunaTheme.DEFAULT
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        themeId = TunaTheme.current(this)
        theme.applyStyle(TunaTheme.styleOf(themeId), true)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Also catches a season starting while the home screen stayed open
        if (TunaTheme.current(this) != themeId) {
            recreate()
        }
    }
}
