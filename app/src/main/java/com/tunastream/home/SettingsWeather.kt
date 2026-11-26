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

// TunaHome settings: the Weather page.

internal fun SettingsActivity.weatherPage() {
    switch(R.string.wx_show, Weather.SHOW, R.string.shown, R.string.hidden)
    val place = Weather.place(this)
    row(R.string.wx_location, place?.name ?: getString(R.string.wx_place_none)) { chooseWeatherPlace() }
    if (!Weather.SHOW.get(this)) {
        return
    }
    choice(R.string.wx_units, Weather.UNITS) { Weather.forget(); changed() }
    choice(R.string.wx_detail, Weather.DETAIL)
    choice(R.string.wx_icons, Weather.ICONS)
    choice(R.string.wx_size, Weather.SIZE)
    choice(R.string.wx_place, Weather.PLACE)
    if (place != null) {
        row(R.string.wx_update, Weather.status.ifEmpty { getString(R.string.wx_update_summary) }) {
            Toast.makeText(this, R.string.wx_updating, Toast.LENGTH_SHORT).show()
            Weather.refresh(this, force = true) { build() }
            // Failures don't call back: show the status after a moment either way
            list.postDelayed({ if (!isDestroyed) build() }, 4000)
        }
    }
    row(R.string.wx_credit, getString(R.string.wx_credit_summary)) {}
}

internal fun SettingsActivity.chooseWeatherPlace() {
    val options = arrayListOf(getString(R.string.wx_search), getString(R.string.wx_from_internet))
    if (Weather.place(this) != null) {
        options.add(getString(R.string.wx_remove_place))
    }
    chooseOne(getString(R.string.wx_location), options, -1) { which ->
        when (which) {
            0 -> askForText(R.string.wx_search, "") { text -> searchWeatherPlace(text) }
            1 -> inBackgroundPlace(R.string.wx_from_internet_failed) { Weather.fromInternetAddress()?.let { listOf(it) } ?: emptyList() }
            else -> {
                Weather.setPlace(this, null)
                changed()
            }
        }
    }
}

internal fun SettingsActivity.searchWeatherPlace(text: String) {
    inBackgroundPlace(R.string.wx_search_none) { Weather.search(text) }
}

/** Finds places off the main thread; one is used straight away, several are offered. */
internal fun SettingsActivity.inBackgroundPlace(failure: Int, find: () -> List<Weather.Place>) {
    Toast.makeText(this, R.string.wx_finding, Toast.LENGTH_SHORT).show()
    thread(name = "TunaHome weather place") {
        val found = try {
            find()
        } catch (e: Exception) {
            emptyList()
        }
        runOnUiThread {
            if (isDestroyed) {
                return@runOnUiThread
            }
            when {
                found.isEmpty() -> Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                found.size == 1 -> usePlace(found[0])
                else -> chooseOne(getString(R.string.wx_which), found.map { it.name }, -1) { usePlace(found[it]) }
            }
        }
    }
}

internal fun SettingsActivity.usePlace(place: Weather.Place) {
    Weather.setPlace(this, place)
    Weather.refresh(this, force = true) { build() }
    changed()
}
