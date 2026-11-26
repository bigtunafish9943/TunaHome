package com.tunastream.home

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) {
            return true
        }
        return moveFocusQuietly(event)
    }

    /**
     * Android plays its own click whenever the D-pad moves from one thing to the next, whatever
     * TunaHome's Sounds are set to. Moving here instead, the same way Android would, leaves
     * only the sound chosen in Sounds, or none.
     */
    private fun moveFocusQuietly(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val root = window?.decorView as? ViewGroup ?: return false
        val focused = root.findFocus()
            ?: return Build.VERSION.SDK_INT >= 26 && root.restoreDefaultFocus()
        val next = focused.focusSearch(direction)
        if (next != null && next !== focused) {
            val moved = try {
                // Where the focus was, so the next thing lines up with it as Android does
                val rect = Rect()
                focused.getFocusedRect(rect)
                root.offsetDescendantRectToMyCoords(focused, rect)
                root.offsetRectIntoDescendantCoords(next, rect)
                next.requestFocus(direction, rect)
            } catch (e: IllegalArgumentException) {
                next.requestFocus(direction)
            }
            if (moved) {
                return true
            }
        }
        // The focused thing's last chance to use the D-pad (a list scrolling, say)
        return root.dispatchUnhandledMove(focused, direction)
    }

    override fun onResume() {
        super.onResume()
        // Also catches a season starting while the home screen stayed open
        if (outOfDate()) {
            recreate()
        }
    }
}
