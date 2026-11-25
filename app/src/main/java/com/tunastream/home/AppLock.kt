package com.tunastream.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A PIN on chosen apps, and optionally on TunaHome settings. It keeps an app from being opened
 * from TunaHome without the PIN; it doesn't stop the app being opened in other ways (from the
 * TV's own settings, say), so it's a child lock rather than security.
 *
 * Only a salted SHA-256 of the PIN is kept. A forgotten PIN is removed by clearing TunaHome's
 * storage in the TV's app settings, which also resets every TunaHome setting.
 */
object AppLock {
    private const val HASH = "lock_pin_hash"
    private const val SALT = "lock_pin_salt"
    private const val LOCKED = "lock_apps"
    private const val SETTINGS = "lock_settings"

    fun hasPin(context: Context): Boolean = Prefs.of(context).getString(HASH, null) != null

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray())
            .joinToString("") { String.format("%02x", it) }

    private fun setPin(context: Context, pin: String) {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { String.format("%02x", it) }
        Prefs.of(context).edit().putString(SALT, salt).putString(HASH, hash(salt, pin)).apply()
    }

    private fun matches(context: Context, pin: String): Boolean {
        val prefs = Prefs.of(context)
        val salt = prefs.getString(SALT, null) ?: return false
        return prefs.getString(HASH, null) == hash(salt, pin)
    }

    /** Takes the PIN away, and with it every lock. */
    fun removePin(context: Context) {
        Prefs.of(context).edit().remove(HASH).remove(SALT).remove(LOCKED).remove(SETTINGS).apply()
    }

    fun locked(context: Context): Set<String> =
        HashSet(Prefs.of(context).getStringSet(LOCKED, emptySet()) ?: emptySet())

    fun isLocked(context: Context, pkg: String): Boolean = hasPin(context) && pkg in locked(context)

    fun setLocked(context: Context, pkg: String, lock: Boolean) {
        val set = locked(context)
        if (lock) set.add(pkg) else set.remove(pkg)
        Prefs.of(context).edit().putStringSet(LOCKED, set).apply()
    }

    fun setLockedSet(context: Context, set: Set<String>) {
        Prefs.of(context).edit().putStringSet(LOCKED, HashSet(set)).apply()
    }

    fun settingsLocked(context: Context): Boolean = hasPin(context) && Prefs.of(context).getBoolean(SETTINGS, false)

    fun setSettingsLocked(context: Context, lock: Boolean) {
        Prefs.of(context).edit().putBoolean(SETTINGS, lock).apply()
    }

    // ------------------------------------------------------------------ asking

    private fun pinBox(activity: Activity): Pair<EditText, FrameLayout> {
        val box = EditText(activity)
        box.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        box.setSingleLine()
        box.setTextColor(activity.themeColor(R.attr.tsText))
        val pad = Math.round(Look.dp(activity, 20f))
        val frame = FrameLayout(activity)
        frame.setPadding(pad, pad / 2, pad, 0)
        frame.addView(box)
        return box to frame
    }

    /** Asks for the PIN and runs [then] if it's right. Runs [then] straight away if there's no PIN. */
    fun ask(activity: Activity, title: CharSequence, then: () -> Unit) {
        if (!hasPin(activity)) {
            then()
            return
        }
        val (box, frame) = pinBox(activity)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(R.string.lock_enter)
            .setView(frame)
            .setPositiveButton(R.string.lock_unlock) { _, _ ->
                if (matches(activity, box.text.toString())) {
                    then()
                } else {
                    Toast.makeText(activity, R.string.lock_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        box.requestFocus()
    }

    /** Sets a new PIN (typed twice), then runs [then]. */
    fun choosePin(activity: Activity, then: () -> Unit) {
        val (first, frame) = pinBox(activity)
        AlertDialog.Builder(activity)
            .setTitle(R.string.lock_new_pin)
            .setMessage(R.string.lock_new_pin_body)
            .setView(frame)
            .setPositiveButton(R.string.lock_next) { _, _ ->
                val pin = first.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(activity, R.string.lock_too_short, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val (second, frame2) = pinBox(activity)
                AlertDialog.Builder(activity)
                    .setTitle(R.string.lock_repeat_pin)
                    .setView(frame2)
                    .setPositiveButton(R.string.done) { _, _ ->
                        if (second.text.toString() == pin) {
                            setPin(activity, pin)
                            Toast.makeText(activity, R.string.lock_pin_set, Toast.LENGTH_LONG).show()
                            then()
                        } else {
                            Toast.makeText(activity, R.string.lock_mismatch, Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                second.requestFocus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        first.requestFocus()
    }
}
