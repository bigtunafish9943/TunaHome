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

// The home screen: the side menu's entries and the places they open.

/** The side menu's entries: every section showing, then the shortcuts. */
internal fun HomeActivity.refreshMenu() {
    val entries = ArrayList<SideMenu.Entry>()
    for (section in sections) {
        if (section.view.visibility != View.VISIBLE) {
            continue
        }
        val icon = when (section.row.kind) {
            Rows.FAVOURITES -> R.drawable.ic_star
            Rows.TV -> R.drawable.ic_tv
            Rows.MOBILE -> R.drawable.ic_phone
            Rows.RECENT -> R.drawable.ic_history
            Rows.FOR_YOU -> R.drawable.ic_star
            Rows.WATCH_NEXT -> R.drawable.ic_play
            Rows.SIDELOADED -> R.drawable.ic_apps
            Rows.GAMES, Rows.PC_GAMES -> R.drawable.ic_games
            Rows.VIDEO -> R.drawable.ic_tv
            Rows.MUSIC -> R.drawable.ic_music
            else -> R.drawable.ic_folder
        }
        entries.add(SideMenu.Entry(icon, section.row.name(this), section.row.id) { goToSection(section) })
    }
    if (Look.SIDE_MENU_SHORTCUTS.get(this)) {
        entries.add(SideMenu.Entry(0, "", null) {})
        entries.add(SideMenu.Entry(R.drawable.ic_search, getString(R.string.search), null) { openSearch(false) })
        if (Profiles.inUse(this)) {
            entries.add(SideMenu.Entry(R.drawable.ic_person, getString(R.string.who_menu, Profiles.current(this)?.name ?: ""), null) {
                WhoActivity.start(this)
            })
        }
        entries.add(SideMenu.Entry(R.drawable.ic_phone, getString(R.string.phone_title), null) { openPhoneSetup() })
        entries.add(SideMenu.Entry(R.drawable.ic_tune, getString(R.string.quick_title), null) {
            quickCustomise()
        })
        entries.add(SideMenu.Entry(R.drawable.ic_settings, getString(R.string.tunahome_settings), null) {
            openTunaHomeSettings()
        })
        entries.add(SideMenu.Entry(R.drawable.ic_display, getString(R.string.tv_settings), null) {
            HomeActivity.openTvSettings(this)
        })
        entries.add(SideMenu.Entry(R.drawable.ic_net_wifi, getString(R.string.menu_network), null) {
            openSetting(Settings.ACTION_WIFI_SETTINGS)
        })
        entries.add(SideMenu.Entry(R.drawable.ic_bluetooth, getString(R.string.menu_bluetooth), null) {
            openSetting(Settings.ACTION_BLUETOOTH_SETTINGS)
        })
        SleepTimer.minutesLeft().let { left ->
            entries.add(SideMenu.Entry(
                R.drawable.ic_timer,
                if (left > 0) getString(R.string.sleep_menu_left, left) else getString(R.string.sleep_title),
                null,
            ) { chooseSleep() })
        }
        entries.add(SideMenu.Entry(R.drawable.ic_screensaver, getString(R.string.menu_screensaver), null) {
            openSetting(Settings.ACTION_DREAM_SETTINGS)
        })
        // Switch between saved layouts (left out while TunaHome settings are locked)
        val layouts = Layouts.saved(this)
        if (layouts.isNotEmpty() && !AppLock.settingsLocked(this)) {
            entries.add(SideMenu.Entry(R.drawable.ic_layouts, getString(R.string.menu_layouts), null) {
                chooseOne(getString(R.string.menu_layouts), layouts, -1) { which -> useLayout(layouts[which]) }
            })
        }
        entries.add(SideMenu.Entry(R.drawable.ic_apps, getString(R.string.menu_installed_apps), null) {
            openSetting(Settings.ACTION_APPLICATION_SETTINGS)
        })
        entries.add(SideMenu.Entry(R.drawable.ic_info, getString(R.string.menu_about_tv), null) {
            openSetting(Settings.ACTION_DEVICE_INFO_SETTINGS)
        })
    }
    sideMenu.setEntries(entries)
}

/** Sets the sleep timer; the time left shows beside the clock. */
internal fun HomeActivity.chooseSleep() {
    SleepTimer.choose(this) {
        weatherHandler.removeCallbacks(infoTick)
        infoTick.run()
    }
}

/** Puts a saved layout in place and starts the home screen again with it. */
internal fun HomeActivity.useLayout(name: String) {
    thread(name = "TunaHome layout") {
        val worked = try {
            Layouts.load(applicationContext, name)
            true
        } catch (e: Exception) {
            false
        }
        runOnUiThread {
            if (worked) Layouts.restart(this)
            else Toast.makeText(this, R.string.layout_failed, Toast.LENGTH_LONG).show()
        }
    }
}

/** A page of the TV's own settings, or the TV's settings if this TV doesn't have that page. */
internal fun HomeActivity.openSetting(action: String) {
    try {
        startActivity(Intent(action))
    } catch (e: Exception) {
        HomeActivity.openTvSettings(this)
    }
}

internal fun HomeActivity.openTunaHomeSettings(page: String? = null) {
    val open = Intent(this, SettingsActivity::class.java)
    if (page != null) {
        open.putExtra(SettingsActivity.EXTRA_PAGE, page)
    }
    if (AppLock.settingsLocked(this)) {
        AppLock.ask(this, getString(R.string.tunahome_settings)) { startActivity(open) }
    } else {
        startActivity(open)
    }
}

/** Set up from your phone (behind the settings lock, as it changes settings). */
internal fun HomeActivity.openPhoneSetup() {
    val open = Intent(this, PhoneSetupActivity::class.java)
    if (AppLock.settingsLocked(this)) {
        AppLock.ask(this, getString(R.string.phone_title)) { startActivity(open) }
    } else {
        startActivity(open)
    }
}

internal fun HomeActivity.openSearch(voice: Boolean) {
    startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_VOICE, voice))
}
