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

// TunaHome settings: the Sections page and each section's menu.

internal fun SettingsActivity.sectionsSummary(): String {
    val shown = Rows.load(this).count { it.visible }
    return resources.getQuantityString(R.plurals.sections_shown, shown, shown)
}

internal fun SettingsActivity.sectionsPage() {
    // Now playing: off until switched on, and it needs notification access
    val npOn = NowPlaying.SHOW.get(this)
    val npAllowed = NowPlaying.allowed(this)
    row(
        R.string.np_title,
        getString(when {
            !npOn -> R.string.np_off
            npAllowed -> R.string.np_on
            else -> R.string.np_needs_access
        })
    ) {
        NowPlaying.SHOW.set(this, !npOn)
        if (!npOn && !NowPlaying.allowed(this)) explainNowPlaying()
        changed()
    }
    if (npOn && !npAllowed) {
        row(R.string.np_allow, getString(R.string.np_allow_summary)) { explainNowPlaying() }
    }
    val all = Rows.load(this)
    all.forEachIndexed { index, section ->
        val lines = linesName(section.lines)
        var summary = if (section.visible) getString(R.string.section_shown, lines)
        else getString(R.string.section_hidden)
        if (section.visible && section.cardSize >= 0) {
            summary += getString(R.string.section_own_size, cardSizeName(section.cardSize))
        }
        if (section.visible && !section.ownOrder && section.sort != Rows.SORT_OWN) {
            summary += " · " + getString(Rows.SORT_NAMES[section.sort])
        }
        row(section.name(this), summary) { sectionMenu(section.id, index, all.size) }
    }
    row(R.string.section_add, getString(R.string.section_add_summary)) {
        askForText(R.string.section_add, "") { title ->
            Rows.add(this, title)
            changed()
        }
    }
    heading(R.string.heading_all_sections)
    choice(R.string.set_header_gap, Look.HEADER_GAP)
    switch(R.string.set_double_press, Look.DOUBLE_PRESS, R.string.double_press_on, R.string.off)
    switch(R.string.set_controller_jumps, Look.CONTROLLER_JUMPS, R.string.controller_jumps_on, R.string.off)
    switch(R.string.set_mic, Look.MIC_TUNAHOME, R.string.mic_tunahome, R.string.mic_google)
    if (Look.MIC_TUNAHOME.get(this)) {
        switch(R.string.set_mic_everywhere, Look.MIC_EVERYWHERE, R.string.mic_everywhere_on, R.string.mic_everywhere_off)
    }
    switch(R.string.set_previews, Previews.SHOW, R.string.previews_on, R.string.off)
    // Other apps' shows: the TV only shares them once TunaHome may read TV listings
    row(R.string.set_watch_next_access, getString(if (WatchNext.allowed(this)) R.string.watch_next_access_on else R.string.watch_next_access_off)) {
        if (WatchNext.allowed(this)) {
            Toast.makeText(this, R.string.watch_next_access_on, Toast.LENGTH_LONG).show()
        } else {
            WatchNext.askAgain(this, HomeActivity.LISTINGS_REQUEST)
        }
    }
    switch(R.string.set_up_next, Look.UP_NEXT, R.string.up_next_on, R.string.off)
    switch(R.string.set_remember_place, Look.REMEMBER_PLACE, R.string.remember_place_on, R.string.remember_place_off)
    heading(R.string.heading_side_menu)
    choice(R.string.set_side_menu, Look.SIDE_MENU)
    if (Look.SIDE_MENU.get(this) != Look.MENU_OFF) {
        switch(R.string.set_side_menu_shortcuts, Look.SIDE_MENU_SHORTCUTS, R.string.shown, R.string.hidden)
    }
    heading(R.string.heading_section_look)
    choice(R.string.set_row_titles, Look.ROW_TITLES)
    choice(R.string.set_row_gap, Look.ROW_GAP)
    choice(R.string.set_section_position, Look.SECTION_POSITION)
    choice(R.string.set_section_panel, Look.SECTION_PANEL)
    choice(R.string.set_row_scroll, Look.ROW_SCROLL)
    choice(R.string.set_start_on, Look.START_ON)
}

/** A card size by name; -1 is the size set for every card. */
internal fun SettingsActivity.cardSizeName(index: Int): String {
    val i = if (index in Look.CARD_SCALES.indices) index
    else Look.CARD_SCALES.indexOfFirst { it == Look.tileScale(this) }.coerceAtLeast(0)
    return getString(Look.CARD_SIZE.names[i])
}

/** How to give TunaHome notification access: the TV's screen if it has one, or one adb command. */
internal fun SettingsActivity.explainNowPlaying() {
    QuietDialog.Builder(this)
        .setTitle(R.string.np_allow)
        .setMessage(getString(R.string.np_allow_body, NowPlaying.ADB_COMMAND))
        .setPositiveButton(R.string.np_open_access) { _, _ ->
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.np_no_screen, Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton(R.string.close, null)
        .show()
}

internal fun SettingsActivity.sectionMenu(id: String, index: Int, count: Int) {
    val section = Rows.load(this).firstOrNull { it.id == id } ?: return
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()

    labels.add(getString(if (section.visible) R.string.section_hide else R.string.section_show))
    actions.add { Rows.update(this, id) { it.visible = !it.visible }; changed() }

    if (section.kind != Rows.NOW_PLAYING) labels.add(getString(R.string.section_lines, linesName(section.lines)))
    if (section.kind != Rows.NOW_PLAYING) actions.add {
        val options = (1..Rows.MAX_LINES).toList() + Rows.WRAP
        chooseOne(
            getString(R.string.section_lines_title, section.name(this)),
            options.map { linesName(it) }, options.indexOf(section.lines)
        ) { which ->
            Rows.update(this, id) { it.lines = options[which] }
            changed()
        }
    }

    if (section.kind == Rows.FAVOURITES || section.custom) {
        labels.add(getString(R.string.link_add_here))
        actions.add {
            addLink { key ->
                if (section.kind == Rows.FAVOURITES) {
                    Prefs.setFavourites(this, Prefs.favourites(this).apply { add(key) })
                } else {
                    Rows.update(this, id) { it.apps.add(key) }
                }
                changed()
            }
        }
    }

    if (!section.ownOrder) {
        labels.add(getString(R.string.section_sort, getString(Rows.SORT_NAMES[section.sort])))
        actions.add {
            chooseOne(
                getString(R.string.section_sort_title, section.name(this)),
                Rows.SORT_NAMES.map { getString(it) }, section.sort
            ) { which ->
                Rows.update(this, id) { it.sort = which }
                changed()
            }
        }
    }

    if (section.kind != Rows.NOW_PLAYING) {
        val styles = listOf(getString(R.string.section_style_same)) + Look.TILE_STYLE.names.map { getString(it) }
        labels.add(getString(R.string.section_style, styles[section.style + 1]))
        actions.add {
            chooseOne(getString(R.string.section_style_title, section.name(this)), styles, section.style + 1) { which ->
                Rows.update(this, id) { it.style = which - 1 }
                changed()
            }
        }
    }

    if (section.kind != Rows.NOW_PLAYING && section.lines == 1) {
        labels.add(getString(R.string.section_spotlight, getString(if (section.spotlight) R.string.on else R.string.off)))
        actions.add {
            Rows.update(this, id) { it.spotlight = !it.spotlight }
            changed()
        }
    }

    if (section.kind == Rows.RECENT) {
        labels.add(getString(R.string.recent_clear))
        actions.add {
            Prefs.clearRecent(this)
            Look.touch(this)
            Toast.makeText(this, R.string.recent_cleared, Toast.LENGTH_LONG).show()
        }
    }

    if (section.kind != Rows.NOW_PLAYING) labels.add(getString(R.string.section_card_size, cardSizeName(section.cardSize)))
    if (section.kind != Rows.NOW_PLAYING) actions.add {
        val names = listOf(getString(R.string.card_size_usual, cardSizeName(-1))) +
            Look.CARD_SIZE.names.map { getString(it) }
        chooseOne(getString(R.string.section_card_size_title, section.name(this)), names, section.cardSize + 1) { which ->
            Rows.update(this, id) { it.cardSize = which - 1 }
            changed()
        }
    }

    labels.add(getString(R.string.section_rename))
    actions.add {
        askForText(R.string.section_rename, section.name(this)) { title ->
            Rows.update(this, id) { it.title = title }
            changed()
        }
    }
    if (!section.custom && section.title != null) {
        labels.add(getString(R.string.section_usual_name))
        actions.add { Rows.update(this, id) { it.title = null }; changed() }
    }
    if (index > 0) {
        labels.add(getString(R.string.section_up))
        actions.add { moveSection(id, -1) }
    }
    if (index < count - 1) {
        labels.add(getString(R.string.section_down))
        actions.add { moveSection(id, 1) }
    }
    if (section.custom) {
        labels.add(getString(R.string.section_delete))
        actions.add {
            QuietDialog.Builder(this)
                .setTitle(section.name(this))
                .setMessage(R.string.section_delete_confirm)
                .setPositiveButton(R.string.section_delete) { _, _ ->
                    val all = Rows.load(this)
                    all.removeAll { it.id == id }
                    Rows.save(this, all)
                    focusIndex = 0
                    changed()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    QuietDialog.Builder(this)
        .setTitle(section.name(this))
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

internal fun SettingsActivity.moveSection(id: String, by: Int) {
    val all = Rows.load(this)
    val from = all.indexOfFirst { it.id == id }
    val to = from + by
    if (from < 0 || to < 0 || to >= all.size) {
        return
    }
    all.add(to, all.removeAt(from))
    Rows.save(this, all)
    focusIndex = to
    changed()
}
