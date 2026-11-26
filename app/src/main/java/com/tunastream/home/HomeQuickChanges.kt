package com.tunastream.home

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.concurrent.thread

// The home screen: Quick changes, the most changed settings shown over the home screen itself.

/**
 * The most changed settings, over the home screen itself: each change shows behind the list
 * straight away, and the list comes back so the next can be tried.
 */
internal fun HomeActivity.quickCustomise() {
    if (AppLock.settingsLocked(this) && !quickUnlocked) {
        AppLock.ask(this, getString(R.string.quick_title)) {
            quickUnlocked = true
            quickCustomise()
        }
        return
    }
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()
    fun item(title: Int, value: String, action: () -> Unit) {
        labels.add(if (value.isEmpty()) getString(title) else getString(title) + ":  " + value)
        actions.add(action)
    }
    fun choice(title: Int, setting: Look.Choice) = item(title, setting.name(this)) {
        chooseOne(getString(title), setting.names.map { getString(it) }, setting.get(this)) {
            setting.set(this, it)
            quickApplied()
        }
    }
    item(R.string.set_theme, getString(TunaTheme.nameOf(TunaTheme.chosen(this)))) {
        val ids = TunaTheme.IDS
        chooseOne(getString(R.string.set_theme), ids.map { getString(TunaTheme.nameOf(it)) }, ids.indexOf(TunaTheme.chosen(this))) {
            TunaTheme.setChosen(this, ids[it])
            Look.touch(this)
            quickApplied()
        }
    }
    choice(R.string.set_accent, Look.ACCENT)
    item(R.string.set_background, Backdrop.SOURCE.name(this)) { quickBackground() }
    choice(R.string.bg_dim, Backdrop.DIM)
    choice(R.string.set_tile_style, Look.TILE_STYLE)
    choice(R.string.set_card_size, Look.CARD_SIZE)
    item(R.string.preset_title, "") {
        chooseOne(getString(R.string.preset_title), Look.PRESETS.map { getString(it) }, -1) {
            Look.applyPreset(this, it)
            quickApplied()
        }
    }
    choice(R.string.set_focus_zoom, Look.FOCUS_ZOOM)
    choice(R.string.set_focus_shadow, Look.FOCUS_SHADOW)
    choice(R.string.set_text_size, Look.TEXT_SIZE)
    choice(R.string.set_section_position, Look.SECTION_POSITION)
    choice(R.string.set_section_panel, Look.SECTION_PANEL)
    choice(R.string.set_clock_face, Look.CLOCK_FACE)
    choice(R.string.set_fx, Effects.CHOICE)
    labels.add(getString(R.string.quick_fine))
    actions.add {
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.FINE))
    }
    labels.add(getString(R.string.quick_everything))
    actions.add {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    QuietDialog.Builder(this)
        .setTitle(R.string.quick_title)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

/** Backgrounds that need nothing more chosen; the rest are in settings. */
internal fun HomeActivity.quickBackground() {
    val names = ArrayList<String>()
    val picks = ArrayList<() -> Unit>()
    names.add(getString(R.string.bg_theme)); picks.add { Backdrop.SOURCE.set(this, Backdrop.SOURCE_THEME) }
    names.add(getString(R.string.bg_daily)); picks.add { Backdrop.SOURCE.set(this, Backdrop.SOURCE_DAILY) }
    WallpaperApps.installed(this).forEach { app ->
        names.add(app.name)
        picks.add {
            Backdrop.setApp(this, app.component)
            Backdrop.SOURCE.set(this, Backdrop.SOURCE_APP)
        }
    }
    names.add(getString(R.string.quick_more_backgrounds))
    picks.add {
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.BACKGROUND))
    }
    chooseOne(getString(R.string.set_background), names, -1) { which ->
        picks[which]()
        if (which < names.size - 1) quickApplied()
    }
}

/** Rebuilds the home screen in the new look, then shows the quick list again. */
internal fun HomeActivity.quickApplied() {
    reopenQuick = true
    if (lookChanged()) {
        recreate()
    } else {
        reopenQuick = false
        quickCustomise()
    }
}
