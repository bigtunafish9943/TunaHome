package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the Home button open TunaHome. Android TV has no "choose your home app" setting, so this
 * is an accessibility service the viewer switches on themselves in the TV's settings; switching
 * it off there is the whole undo. Nothing on the TV is disabled or removed.
 *
 * It does two things and nothing else:
 *  - a Home press opens TunaHome instead of the TV's own home screen;
 *  - if the viewer asked for it in Settings, the TV's own home screen appearing (after Back out
 *    of an app, or a restart) opens TunaHome too.
 * It asks Android for key presses and for which app came to the front. It cannot read the screen.
 */
class HomeButtonService : AccessibilityService() {
    private var lastReplaced = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) {
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            showHome()
        }
        // Swallow both halves of the press so the TV's own home screen doesn't open as well
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in STOCK_HOME_SCREENS || !Prefs.replaceStock(this)) {
            return
        }
        // At most once every couple of seconds, so a home screen that won't stay hidden can't
        // make the two fight
        val now = SystemClock.uptimeMillis()
        if (now - lastReplaced < 2000) {
            return
        }
        lastReplaced = now
        showHome()
    }

    override fun onInterrupt() {
    }

    private fun showHome() {
        try {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .setAction(ACTION_HOME_PRESSED)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open TunaHome", e)
        }
    }

    companion object {
        private const val TAG = "TunaHome"
        const val ACTION_HOME_PRESSED = "com.tunastream.home.HOME_PRESSED"

        /**
         * Only these are ever replaced. A fixed list on purpose: the TV's Settings app also
         * counts as a "home" app on some devices, and replacing that would lock the viewer out.
         */
        private val STOCK_HOME_SCREENS = setOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher",
        )

        /** Whether the viewer has switched the service on in the TV's accessibility settings. */
        fun isOn(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, HomeButtonService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
