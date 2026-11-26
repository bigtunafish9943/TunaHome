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

// TunaHome settings: Shortcut keys.

internal fun SettingsActivity.shortcutsPage() {
    val all = Hotkeys.load(this)
    row(R.string.shortcut_add, getString(R.string.shortcut_add_summary)) { addShortcut() }
    for ((index, h) in all.withIndex()) {
        val where = if (h.everywhere) getString(R.string.shortcut_everywhere_short) else getString(R.string.shortcut_home_short)
        row(Hotkeys.triggerName(this, h), HotkeyActions.name(this, h.action, h.arg) + " · " + where) {
            chooseOne(Hotkeys.triggerName(this, h),
                listOf(getString(R.string.shortcut_change_action), getString(R.string.shortcut_delete)), -1) { which ->
                val list = Hotkeys.load(this)
                if (which == 0) {
                    chooseAction { action, arg ->
                        list[index] = Hotkeys.Hotkey(h.keys, h.presses, h.everywhere, action, arg)
                        Hotkeys.save(this, list)
                        build()
                    }
                } else {
                    list.removeAt(index)
                    Hotkeys.save(this, list)
                    build()
                }
            }
        }
    }
    if (all.any { it.everywhere } && !HomeButtonService.isOn(this)) {
        row(R.string.shortcut_needs_service, getString(R.string.shortcut_needs_service_summary)) {
            explainHomeButton(false)
        }
    }
    heading(R.string.heading_shortcut_tips)
    row(R.string.shortcut_tip_title, getString(R.string.shortcut_tip)) {}
}

/** Step 1: which button (or two together). */
internal fun SettingsActivity.addShortcut() {
    val ways = listOf(getString(R.string.shortcut_way_press), getString(R.string.shortcut_way_home),
        getString(R.string.shortcut_way_two))
    chooseOne(getString(R.string.shortcut_add), ways, -1) { way ->
        when (way) {
            0 -> captureKeys(false) { keys -> choosePresses(keys) }
            1 -> choosePresses(listOf(KeyEvent.KEYCODE_HOME))
            else -> captureKeys(true) { keys -> chooseWhere(keys, 1) }
        }
    }
}

/** Waits for a button press (or two buttons together) and reports which. */
internal fun SettingsActivity.captureKeys(two: Boolean, done: (List<Int>) -> Unit) {
    val down = LinkedHashSet<Int>()
    var finished = false
    val dialog = QuietDialog.Builder(this)
        .setTitle(if (two) R.string.capture_two_title else R.string.capture_title)
        .setMessage(if (two) R.string.capture_two_body else R.string.capture_body)
        .create()
    dialog.setOnKeyListener { d, code, event ->
        if (finished) {
            return@setOnKeyListener true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            down.add(code)
            if (two && down.size >= 2) {
                finished = true
                d.dismiss()
                confirmKeys(down.take(2), two, done)
            }
        } else if (event.action == KeyEvent.ACTION_UP && !two && code in down) {
            finished = true
            d.dismiss()
            confirmKeys(listOf(code), two, done)
        } else if (event.action == KeyEvent.ACTION_UP && two) {
            down.remove(code)
        }
        true
    }
    dialog.show()
}

internal fun SettingsActivity.confirmKeys(keys: List<Int>, two: Boolean, done: (List<Int>) -> Unit) {
    val name = keys.joinToString(" + ") { Hotkeys.keyName(this, it) }
    QuietDialog.Builder(this)
        .setTitle(getString(R.string.capture_got, name))
        .setMessage(if (keys.any { it == KeyEvent.KEYCODE_DPAD_CENTER || it in SettingsActivity.DPAD }) getString(R.string.capture_dpad_warning) else null)
        .setPositiveButton(R.string.capture_use) { _, _ -> done(keys) }
        .setNeutralButton(R.string.capture_again) { _, _ -> captureKeys(two, done) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Step 2: once, twice, three or four times, or held. */
internal fun SettingsActivity.choosePresses(keys: List<Int>) {
    val options = listOf(1, 2, 3, 4, Hotkeys.HOLD)
    chooseOne(getString(R.string.shortcut_how, Hotkeys.keyName(this, keys[0])),
        options.map { Hotkeys.pressesName(this, it) }, -1) { chooseWhere(keys, options[it]) }
}

/** Step 3: only on the home screen, or everywhere (Home and Back only). */
internal fun SettingsActivity.chooseWhere(keys: List<Int>, presses: Int) {
    val key = keys[0]
    when {
        keys.size == 1 && key == KeyEvent.KEYCODE_HOME -> {
            if (presses == 1) {
                Toast.makeText(this, R.string.shortcut_home_once, Toast.LENGTH_LONG).show()
                return
            }
            chooseActionAndSave(keys, presses, true)
        }
        keys.size == 1 && Hotkeys.canBeEverywhere(key) -> {
            chooseOne(getString(R.string.shortcut_where),
                listOf(getString(R.string.shortcut_where_home), getString(R.string.shortcut_where_everywhere)), -1) {
                chooseActionAndSave(keys, presses, it == 1)
            }
        }
        else -> chooseActionAndSave(keys, presses, false)
    }
}

internal fun SettingsActivity.chooseActionAndSave(keys: List<Int>, presses: Int, everywhere: Boolean) {
    chooseAction { action, arg ->
        val list = Hotkeys.load(this)
        // The same press again replaces the old shortcut
        list.removeAll { it.keys.toSet() == keys.toSet() && it.presses == presses && it.everywhere == everywhere }
        list.add(Hotkeys.Hotkey(keys, presses, everywhere, action, arg))
        Hotkeys.save(this, list)
        if (everywhere && !HomeButtonService.isOn(this)) {
            explainHomeButton(false)
        }
        build()
    }
}

/** Step 4: what it does (and which app or section, for those). */
internal fun SettingsActivity.chooseAction(done: (String, String?) -> Unit) {
    val actions = HotkeyActions.ALL
    chooseOne(getString(R.string.shortcut_does), actions.map { getString(it.second) }, -1) { which ->
        val action = actions[which].first
        when (action) {
            HotkeyActions.OPEN_APP -> {
                val apps = launchableApps()
                chooseOne(getString(R.string.act_open_app), apps.map { it.second }, -1) { done(action, apps[it].first) }
            }
            HotkeyActions.SECTION -> {
                val rows = Rows.load(this).filter { it.visible }
                chooseOne(getString(R.string.act_section), rows.map { it.name(this) }, -1) { done(action, rows[it].id) }
            }
            else -> done(action, null)
        }
    }
}

internal fun SettingsActivity.launchableApps(): List<Pair<String, String>> {
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
    return found.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }
}
