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

// TunaHome settings: the Background page.

internal fun SettingsActivity.backgroundSummary(): String {
    val source = Backdrop.SOURCE.get(this)
    if (source == Backdrop.SOURCE_APP) {
        WallpaperApps.find(this, Backdrop.app(this))?.let { return it.name }
    }
    return Backdrop.SOURCE.name(this)
}

internal fun SettingsActivity.backgroundPage() {
    val source = Backdrop.SOURCE.get(this)
    row(R.string.set_background, Backdrop.SOURCE.name(this)) { chooseBackground() }

    when (source) {
        Backdrop.SOURCE_PICTURE -> {
            row(
                R.string.wallpaper_choose,
                getString(if (Pictures.hasWallpaper(this)) R.string.set_wallpaper_set else R.string.set_wallpaper_none)
            ) { pick(SettingsActivity.REQUEST_WALLPAPER, "image/*") }
            row(R.string.picture_address, getString(R.string.picture_address_summary)) {
                askForText(R.string.picture_address, "https://", url = true) { address ->
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, R.string.bg_address_bad, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, R.string.picture_fetching, Toast.LENGTH_SHORT).show()
                        inBackground(R.string.picture_address_failed, { Pictures.saveWallpaper(it, Uri.parse(address)) }) {
                            Backdrop.SOURCE.set(this, Backdrop.SOURCE_PICTURE)
                        }
                    }
                }
            }
            if (Pictures.hasWallpaper(this)) {
                row(R.string.wallpaper_remove, getString(R.string.wallpaper_remove_summary)) {
                    Pictures.removeWallpaper(this)
                    Look.touch(this)
                    changed()
                }
            }
        }
        Backdrop.SOURCE_VIDEOS -> {
            val own = Backdrop.videos(this)
            row(
                R.string.bg_add_from_tv,
                if (own.isEmpty()) getString(R.string.bg_videos_none)
                else resources.getQuantityString(R.plurals.bg_videos_count, own.size, own.size)
            ) { addOwnFromTv() }
            row(R.string.bg_add_address, getString(R.string.bg_add_address_summary)) {
                askForText(R.string.bg_add_address, "https://", url = true) { address ->
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, R.string.bg_address_bad, Toast.LENGTH_LONG).show()
                    } else {
                        Backdrop.setVideos(
                            this,
                            Backdrop.videos(this) + Backdrop.Own(address, !Backdrop.looksLikePicture(address))
                        )
                        build()
                    }
                }
            }
            if (own.isNotEmpty()) {
                row(R.string.bg_remove_own, getString(R.string.bg_remove_own_summary)) { removeOwn() }
            }
        }
        Backdrop.SOURCE_DAILY -> {
            choice(R.string.daily_from, Daily.PROVIDER)
            row(R.string.daily_again, getString(R.string.daily_again_summary)) {
                work(R.string.daily_failed, { Daily.picture(it, force = true) }) {
                    Look.touch(this)
                }
            }
        }
        Backdrop.SOURCE_APP -> {
            val app = WallpaperApps.find(this, Backdrop.app(this))
            row(R.string.bg_which_app, app?.name ?: getString(R.string.bg_no_app)) { chooseWallpaperApp() }
            if (app != null) {
                row(getString(R.string.bg_open_app, app.name), getString(R.string.bg_open_app_summary)) {
                    // Whatever is picked there shows up the next time the home screen opens
                    WallpaperApps.forget()
                    if (!WallpaperApps.openSettings(this, app)) {
                        Toast.makeText(this, R.string.action_failed, Toast.LENGTH_LONG).show()
                    }
                }
                row(R.string.bg_refresh, getString(R.string.bg_refresh_summary)) {
                    WallpaperApps.forget()
                    Toast.makeText(this, R.string.bg_refreshed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (source != Backdrop.SOURCE_THEME && Backdrop.status.isNotEmpty()) {
        row(R.string.bg_status, Backdrop.status) { build() }
    }

    if (source != Backdrop.SOURCE_THEME) {
        heading(R.string.heading_how_it_shows)
        choice(R.string.bg_dim, Backdrop.DIM)
        choice(R.string.bg_fit, Backdrop.FIT)
        choice(R.string.bg_brightness, Backdrop.BRIGHTNESS)
        choice(R.string.bg_contrast, Backdrop.CONTRAST)
        choice(R.string.bg_saturation, Backdrop.SATURATION)
        choice(R.string.bg_blur, Backdrop.BLUR)
        if (source != Backdrop.SOURCE_PICTURE && source != Backdrop.SOURCE_DAILY) {
            choice(R.string.bg_change_every, Backdrop.CHANGE_EVERY)
            switch(R.string.bg_shuffle, Backdrop.SHUFFLE, R.string.bg_shuffle_on, R.string.bg_shuffle_off)
            switch(R.string.bg_play_videos, Backdrop.PLAY_VIDEOS, R.string.bg_play_videos_on, R.string.bg_play_videos_off)
        }
        if (source == Backdrop.SOURCE_APP || source == Backdrop.SOURCE_DAILY) {
            switch(R.string.bg_caption, Backdrop.SHOW_CAPTION, R.string.shown, R.string.hidden)
        }
    }

    heading(R.string.heading_follow)
    choice(R.string.bg_follow, Ambient.FOLLOW)
    switch(R.string.bg_drift, Backdrop.DRIFT, R.string.drift_on, R.string.off)

    heading(R.string.heading_idle)
    choice(R.string.set_idle, Look.IDLE)
    if (Look.IDLE.get(this) != 0) {
        switch(R.string.set_idle_clock, Look.IDLE_CLOCK, R.string.idle_clock_on, R.string.idle_clock_off)
    }

    heading(R.string.heading_screensaver)
    row(
        R.string.set_screensaver,
        getString(if (Screensaver.isChosen(this)) R.string.screensaver_chosen else R.string.screensaver_how)
    ) {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: Exception) {
            HomeActivity.openTvSettings(this)
        }
    }
}

internal fun SettingsActivity.chooseBackground() {
    val names = Backdrop.SOURCE.names.map { getString(it) }
    chooseOne(getString(R.string.set_background), names, Backdrop.SOURCE.get(this)) { which ->
        if (which == Backdrop.SOURCE_APP) {
            val installed = WallpaperApps.installed(this)
            if (installed.isEmpty()) {
                offerAerialViews()
                return@chooseOne
            }
            if (WallpaperApps.find(this, Backdrop.app(this)) == null) {
                Backdrop.setApp(this, installed.first().component)
            }
        }
        Backdrop.SOURCE.set(this, which)
        changed()
        if (which == Backdrop.SOURCE_PICTURE && !Pictures.hasWallpaper(this)) {
            pick(SettingsActivity.REQUEST_WALLPAPER, "image/*")
        }
    }
}

/** No wallpaper app on the TV: say what one is and offer Aerial Views. */
internal fun SettingsActivity.offerAerialViews() {
    AlertDialog.Builder(this)
        .setTitle(R.string.bg_app)
        .setMessage(R.string.bg_no_apps_body)
        .setPositiveButton(R.string.bg_get_aerial) { _, _ ->
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + WallpaperApps.AERIAL_VIEWS))
            try {
                startActivity(market)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.bg_get_aerial_manual, Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton(R.string.close, null)
        .show()
}

internal fun SettingsActivity.chooseWallpaperApp() {
    val installed = WallpaperApps.installed(this)
    if (installed.isEmpty()) {
        offerAerialViews()
        return
    }
    val current = installed.indexOfFirst { it.component == Backdrop.app(this) }
    chooseOne(getString(R.string.bg_which_app), installed.map { it.name }, current) {
        Backdrop.setApp(this, installed[it].component)
        changed()
    }
}

internal fun SettingsActivity.addOwnFromTv() {
    // Lasting access where the TV allows it; otherwise TunaHome keeps its own copy
    val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(open, SettingsActivity.REQUEST_OWN_DOCUMENT)
    } catch (e: ActivityNotFoundException) {
        pick(SettingsActivity.REQUEST_OWN_COPY, "video/*")
    }
}

internal fun SettingsActivity.removeOwn() {
    val own = Backdrop.videos(this)
    val keep = BooleanArray(own.size) { true }
    AlertDialog.Builder(this)
        .setTitle(R.string.bg_remove_own_title)
        .setMultiChoiceItems(own.map { it.shortName() }.toTypedArray(), keep) { _, which, ticked ->
            keep[which] = ticked
        }
        .setPositiveButton(R.string.done) { _, _ ->
            own.forEachIndexed { i, item ->
                if (!keep[i]) {
                    Pictures.forgetVideo(this, item.uri)
                }
            }
            Backdrop.setVideos(this, own.filterIndexed { i, _ -> keep[i] })
            build()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
