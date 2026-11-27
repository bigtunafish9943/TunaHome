package com.tunastream.home

import android.app.Activity
import android.widget.Toast

/** Opening an app the same way from everywhere: the PIN if it's locked, then Recently opened. */
object Launch {
    fun open(activity: Activity, app: AppEntry, failed: () -> Unit = {}, opened: () -> Unit = {}) {
        if (AppLock.isLocked(activity, app.appPkg) || AppLock.isLocked(activity, app.pkg)) {
            AppLock.ask(activity, app.label) { start(activity, app, failed, opened) }
        } else {
            start(activity, app, failed, opened)
        }
    }

    private fun start(activity: Activity, app: AppEntry, failed: () -> Unit, opened: () -> Unit) {
        try {
            // Back full screen if Two at once had it in a window
            if (app.isInsideApp || !TwoUp.takeBack(activity, app.pkg)) activity.startActivity(app.launch)
            opened()
            Sounds.play(activity, Sounds.OPEN)
            if (!app.isInsideApp) {
                Prefs.setLastOpened(activity, app.pkg)
                Prefs.addRecent(activity, app.pkg)
                OpenApps.note(activity, app.appPkg)
                Prefs.addUse(activity, app.pkg)
                Prefs.addHourUse(activity, app.pkg)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.open_failed, app.label), Toast.LENGTH_LONG).show()
            failed()
        }
    }
}
