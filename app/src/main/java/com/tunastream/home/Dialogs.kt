package com.tunastream.home

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout

/** A box to type into, such as a section's name. [done] gets the text, if any was typed. */
fun Activity.askForText(title: Int, start: String, url: Boolean = false, done: (String) -> Unit) {
    val box = EditText(this)
    box.inputType = if (url) {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    } else {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    box.setText(start)
    box.setSelection(start.length)
    box.setSingleLine()
    box.setTextColor(themeColor(R.attr.tsText))
    val pad = Math.round(Look.dp(this, 20f))
    val frame = FrameLayout(this)
    frame.setPadding(pad, pad / 2, pad, 0)
    frame.addView(box)
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(frame)
        .setPositiveButton(R.string.done) { _, _ ->
            val text = box.text.toString().trim()
            if (text.isNotEmpty()) {
                done(text)
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    box.requestFocus()
}

/** A short list of named options with the current one marked; picking one applies it. */
fun Activity.chooseOne(title: CharSequence, names: List<CharSequence>, current: Int, pick: (Int) -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
            dialog.dismiss()
            pick(which)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
