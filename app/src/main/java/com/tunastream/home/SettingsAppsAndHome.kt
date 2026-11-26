package com.tunastream.home

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

// TunaHome settings: Apps (hidden apps, app lock), the Home button and Updates.

/** Every app needs the PIN between two hours: off, or a start then an end. */
internal fun SettingsActivity.chooseLockedHours() {
    val hourNames = (0..23).map { AppLock.hourName(this, it) }
    val current = AppLock.lockedHours(this)
    chooseOne(getString(R.string.lock_hours), listOf(getString(R.string.lock_hours_off), getString(R.string.lock_hours_set)),
        if (current == null) 0 else 1) { which ->
        if (which == 0) {
            AppLock.setLockedHours(this, null)
            Look.touch(this)
            build()
            return@chooseOne
        }
        chooseOne(getString(R.string.lock_hours_from), hourNames, current?.first ?: 21) { from ->
            chooseOne(getString(R.string.lock_hours_to), hourNames, current?.second ?: 7) { to ->
                if (from == to) {
                    Toast.makeText(this, R.string.lock_hours_same, Toast.LENGTH_LONG).show()
                } else {
                    AppLock.setLockedHours(this, from to to)
                    Look.touch(this)
                    build()
                }
            }
        }
    }
}

internal fun SettingsActivity.appsPage() {
    val hiddenCount = Prefs.hidden(this).size
    row(
        R.string.set_hidden,
        if (hiddenCount == 0) getString(R.string.set_hidden_none)
        else resources.getQuantityString(R.plurals.set_hidden_count, hiddenCount, hiddenCount)
    ) { chooseHidden() }

    heading(R.string.heading_lock)
    if (!AppLock.hasPin(this)) {
        row(R.string.lock_set_pin, getString(R.string.lock_set_pin_summary)) {
            AppLock.choosePin(this) { build() }
        }
    } else {
        val locked = AppLock.locked(this).size
        row(
            R.string.lock_apps,
            if (locked == 0) getString(R.string.lock_apps_none)
            else resources.getQuantityString(R.plurals.lock_apps_count, locked, locked)
        ) { AppLock.ask(this, getString(R.string.lock_apps)) { chooseLocked() } }
        val hours = AppLock.lockedHours(this)
        row(
            R.string.lock_hours,
            if (hours == null) getString(R.string.lock_hours_off)
            else getString(R.string.lock_hours_on, AppLock.hourName(this, hours.first), AppLock.hourName(this, hours.second))
        ) { AppLock.ask(this, getString(R.string.lock_hours)) { chooseLockedHours() } }
        val settingsLocked = AppLock.settingsLocked(this)
        row(
            R.string.lock_settings,
            getString(if (settingsLocked) R.string.lock_settings_on else R.string.lock_settings_off)
        ) {
            AppLock.ask(this, getString(R.string.lock_settings)) {
                AppLock.setSettingsLocked(this, !settingsLocked)
                build()
            }
        }
        row(R.string.lock_change_pin, getString(R.string.lock_change_pin_summary)) {
            AppLock.ask(this, getString(R.string.lock_change_pin)) { AppLock.choosePin(this) { build() } }
        }
        row(R.string.lock_remove_pin, getString(R.string.lock_remove_pin_summary)) {
            AppLock.ask(this, getString(R.string.lock_remove_pin)) {
                AppLock.removePin(this)
                Look.touch(this)
                changed()
            }
        }
    }

    heading(R.string.heading_order)
    val ownOrder = Prefs.hasOwnOrder(this)
    row(
        R.string.set_order,
        getString(if (ownOrder) R.string.set_order_own else R.string.set_order_az)
    ) {
        if (ownOrder) {
            QuietDialog.Builder(this)
                .setTitle(R.string.set_order)
                .setMessage(R.string.set_order_confirm)
                .setPositiveButton(R.string.set_order_reset) { _, _ ->
                    Prefs.clearOrder(this)
                    build()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            Toast.makeText(this, R.string.set_order_az, Toast.LENGTH_LONG).show()
        }
    }
}

internal fun SettingsActivity.chooseLocked() {
    val locked = AppLock.locked(this).toList()
    if (locked.isEmpty()) {
        Toast.makeText(this, R.string.lock_apps_none, Toast.LENGTH_LONG).show()
        return
    }
    val named = locked.map { it to appName(it) }.sortedBy { it.second.lowercase() }
    val keep = BooleanArray(named.size) { true }
    QuietDialog.Builder(this)
        .setTitle(R.string.lock_apps_title)
        .setMultiChoiceItems(named.map { it.second }.toTypedArray(), keep) { _, which, ticked ->
            keep[which] = ticked
        }
        .setPositiveButton(R.string.done) { _, _ ->
            AppLock.setLockedSet(this, named.filterIndexed { i, _ -> keep[i] }.map { it.first }.toSet())
            Look.touch(this)
            changed()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.chooseHidden() {
    val hidden = Prefs.hidden(this).toList()
    if (hidden.isEmpty()) {
        Toast.makeText(this, R.string.set_hidden_none, Toast.LENGTH_LONG).show()
        return
    }
    val pm = packageManager
    val named = hidden.map { pkg ->
        val label = try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            pkg
        }
        pkg to label
    }.sortedBy { it.second.lowercase() }
    val keep = BooleanArray(named.size) { true }
    QuietDialog.Builder(this)
        .setTitle(R.string.set_hidden_title)
        .setMultiChoiceItems(named.map { it.second }.toTypedArray(), keep) { _, which, ticked ->
            keep[which] = ticked
        }
        .setPositiveButton(R.string.done) { _, _ ->
            Prefs.setHidden(this, named.filterIndexed { i, _ -> keep[i] }.map { it.first }.toSet())
            build()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.homeButtonPage() {
    val homeOn = HomeButtonService.isOn(this)
    row(
        R.string.set_home_button,
        getString(if (homeOn) R.string.set_home_button_on else R.string.set_home_button_off)
    ) { explainHomeButton(homeOn) }

    val wake = Prefs.wakeApp(this)
    row(
        R.string.set_wake_app,
        when {
            wake == null -> getString(R.string.wake_nothing)
            homeOn -> getString(R.string.wake_opens, appName(wake))
            else -> getString(R.string.wake_needs_home, appName(wake))
        }
    ) { chooseWakeApp() }

    val replace = Prefs.replaceStock(this)
    row(
        R.string.set_replace_stock,
        getString(if (replace) R.string.set_replace_stock_on else R.string.set_replace_stock_off)
    ) {
        Prefs.setReplaceStock(this, !replace)
        build()
    }
}

/** Picks the app to open when the TV wakes up, from every app that can be opened. */
internal fun SettingsActivity.chooseWakeApp() {
    val pm = packageManager
    val found = LinkedHashMap<String, String>()
    for (category in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg != packageName && !found.containsKey(pkg)) {
                found[pkg] = info.loadLabel(pm).toString()
            }
        }
    }
    val apps = found.entries.sortedBy { it.value.lowercase() }
    val names = listOf(getString(R.string.wake_nothing)) + apps.map { it.value }
    val current = apps.indexOfFirst { it.key == Prefs.wakeApp(this) } + 1
    chooseOne(getString(R.string.set_wake_app), names, current) {
        Prefs.setWakeApp(this, if (it == 0) null else apps[it - 1].key)
        if (it != 0 && !HomeButtonService.isOn(this)) {
            explainHomeButton(false)
        }
        build()
    }
}

/** TunaHome never switches this on by itself: it explains, and opens the TV's own screen. */
internal fun SettingsActivity.explainHomeButton(on: Boolean) {
    QuietDialog.Builder(this)
        .setTitle(R.string.set_home_button)
        .setMessage(if (on) R.string.home_button_how_off else R.string.home_button_how_on)
        .setPositiveButton(R.string.open_accessibility) { _, _ ->
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                HomeActivity.openTvSettings(this)
            }
        }
        .setNegativeButton(R.string.close, null)
        .show()
}

internal fun SettingsActivity.updatesPage() {
    row(R.string.set_update, getString(R.string.set_update_summary, BuildConfig.VERSION_NAME)) {
        Updater.check(this, true)
    }
    val auto = Prefs.autoUpdateCheck(this)
    row(
        R.string.set_auto_update,
        getString(if (auto) R.string.set_auto_update_on else R.string.set_auto_update_off)
    ) {
        Prefs.setAutoUpdateCheck(this, !auto)
        build()
    }
    row(R.string.whats_new, getString(R.string.whats_new_summary, BuildConfig.VERSION_NAME)) {
        ChangeLog.showCurrent(this)
    }
    val problem = CrashLog.read(this)
    if (problem != null) {
        row(R.string.set_last_problem, problem.lineSequence().drop(1).firstOrNull() ?: "") {
            val view = android.widget.ScrollView(this)
            val text = TextView(this)
            text.text = problem
            text.textSize = 11f
            text.setTextIsSelectable(true)
            text.setTextColor(themeColor(R.attr.tsText))
            val pad = Math.round(Look.dp(this, 16f))
            text.setPadding(pad, pad, pad, pad)
            view.addView(text)
            QuietDialog.Builder(this)
                .setTitle(R.string.set_last_problem)
                .setView(view)
                .setPositiveButton(R.string.close, null)
                .setNegativeButton(R.string.problem_clear) { _, _ ->
                    CrashLog.clear(this)
                    build()
                }
                .show()
        }
    }
    row(R.string.set_about, getString(R.string.set_about_summary)) {
        QuietDialog.Builder(this)
            .setTitle(getString(R.string.version, BuildConfig.VERSION_NAME))
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
