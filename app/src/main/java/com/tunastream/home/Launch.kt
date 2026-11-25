package com.tunastream.home

import android.app.Activity
import android.widget.Toast

/** Opening an app the same way from everywhere: the PIN if it's locked, then Recently opened. */
object Launch {
    fun open(activity: Activity, app: AppEntry, failed: () -> Unit = {}) {
        if (AppLock.isLocked(activity, app.appPkg) || AppLock.isLocked(activity, app.pkg)) {
            AppLock.ask(activity, app.label) { start(activity, app, failed) }
        } else {
            start(activity, app, failed)
        }
    }

    private fun start(activity: Activity, app: AppEntry, failed: () -> Unit) {
        try {
            activity.startActivity(app.launch)
            Sounds.play(activity, Sounds.OPEN)
            if (!app.isInsideApp) {
                Prefs.setLastOpened(activity, app.pkg)
                Prefs.addRecent(activity, app.pkg)
                Prefs.addUse(activity, app.pkg)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.open_failed, app.label), Toast.LENGTH_LONG).show()
            failed()
        }
    }
}
