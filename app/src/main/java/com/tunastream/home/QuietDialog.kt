package com.tunastream.home

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * TunaHome's pop-ups, made the same way as Android's but without Android's click on every
 * D-pad move between their buttons and items, which played whatever was chosen in Sounds (see
 * BaseActivity.moveFocusQuietly for the screens themselves).
 */
object QuietDialog {
    class Builder(context: Context) : AlertDialog.Builder(context) {
        override fun create(): AlertDialog = super.create().also { install(it) }
    }

    private fun install(dialog: AlertDialog) {
        dialog.setOnKeyListener { d, keyCode, event ->
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> return@setOnKeyListener false
            }
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            val root = (d as? AlertDialog)?.window?.decorView as? ViewGroup ?: return@setOnKeyListener false
            val focused = root.findFocus() ?: return@setOnKeyListener false
            // A list or text box uses the D-pad itself (scrolling, moving the cursor): let it
            if (focused.onKeyDown(keyCode, KeyEvent(event))) {
                return@setOnKeyListener true
            }
            val next = focused.focusSearch(direction) ?: return@setOnKeyListener false
            if (next === focused) {
                return@setOnKeyListener false
            }
            try {
                val rect = Rect()
                focused.getFocusedRect(rect)
                root.offsetDescendantRectToMyCoords(focused, rect)
                root.offsetRectIntoDescendantCoords(next, rect)
                next.requestFocus(direction, rect)
            } catch (e: IllegalArgumentException) {
                next.requestFocus(direction)
            }
        }
    }
}
