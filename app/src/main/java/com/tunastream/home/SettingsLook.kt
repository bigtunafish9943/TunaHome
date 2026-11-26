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

// TunaHome settings: Theme and colours, Text, Icons, Sounds and Top of the screen.

internal fun SettingsActivity.coloursPage() {
    val chosenName = getString(TunaTheme.nameOf(TunaTheme.chosen(this)))
    val showing = TunaTheme.current(this)
    val themeSummary = if (showing != TunaTheme.chosen(this)) {
        getString(R.string.set_theme_season_showing, chosenName, getString(TunaTheme.nameOf(showing)))
    } else {
        chosenName
    }
    row(R.string.set_theme, themeSummary) {
        val ids = TunaTheme.IDS
        chooseOne(
            getString(R.string.set_theme), ids.map { getString(TunaTheme.nameOf(it)) },
            ids.indexOf(TunaTheme.chosen(this))
        ) {
            TunaTheme.setChosen(this, ids[it])
            changed()
        }
    }
    val seasonal = TunaTheme.seasonal(this)
    row(
        R.string.set_seasonal,
        getString(if (seasonal) R.string.set_seasonal_on else R.string.set_seasonal_off)
    ) {
        TunaTheme.setSeasonal(this, !seasonal)
        changed()
    }
    choice(R.string.set_accent, Look.ACCENT)
    choice(R.string.set_text_colour, Look.TEXT_COLOUR)
}

internal fun SettingsActivity.textPage() {
    row(R.string.set_font, Fonts.name(this, Look.font(this))) {
        chooseFont(getString(R.string.set_font), Look.font(this), false) { Look.setFont(this, it ?: Fonts.STANDARD) }
    }
    row(
        R.string.set_clock_font,
        Look.clockFont(this)?.let { Fonts.name(this, it) } ?: getString(R.string.clock_font_same)
    ) {
        chooseFont(getString(R.string.set_clock_font), Look.clockFont(this), true) { Look.setClockFont(this, it) }
    }
    choice(R.string.set_text_size, Look.TEXT_SIZE)
    switch(R.string.set_text_shadow, Look.TEXT_SHADOW)
    row(
        R.string.set_own_font,
        getString(if (Fonts.hasCustom(this)) R.string.own_font_set else R.string.own_font_none)
    ) {
        if (!Fonts.hasCustom(this)) {
            pick(SettingsActivity.REQUEST_FONT, "*/*")
        } else {
            val options = listOf(getString(R.string.own_font_replace), getString(R.string.own_font_remove))
            chooseOne(getString(R.string.set_own_font), options, -1) {
                if (it == 0) {
                    pick(SettingsActivity.REQUEST_FONT, "*/*")
                } else {
                    Fonts.removeCustom(this)
                    if (Look.font(this) == Fonts.CUSTOM) Look.setFont(this, Fonts.STANDARD)
                    if (Look.clockFont(this) == Fonts.CUSTOM) Look.setClockFont(this, null)
                    Look.touch(this)
                    changed()
                }
            }
        }
    }
}

/** The list of fonts, each name written in its own font. */
internal fun SettingsActivity.chooseFont(title: String, current: String?, allowSame: Boolean, pick: (String?) -> Unit) {
    val ids: List<String?> = (if (allowSame) listOf<String?>(null) else emptyList()) + Fonts.ids(this)
    val names = ids.map { if (it == null) getString(R.string.clock_font_same) else Fonts.name(this, it) }
    val context: Context = this
    val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, names) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val id = ids[position]
            view.typeface = if (id == null || id == Fonts.STANDARD) android.graphics.Typeface.DEFAULT
            else Fonts.typeface(context, id, 400) ?: android.graphics.Typeface.DEFAULT
            return view
        }
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(adapter, ids.indexOf(current)) { dialog, which ->
            dialog.dismiss()
            pick(ids[which])
            changed()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.picturesPage() {
    previewRow()
    // One list: TV pictures, the apps' own icons, or any icon pack on the TV
    val packs = IconPacks.installed(this)
    val usingPictures = Look.TILE_STYLE.get(this) == Look.TILE_BANNER
    val coverage = IconPacks.covered
    val chosenPack = IconPacks.chosen(this)
    row(
        R.string.set_icons,
        if (!usingPictures && chosenPack != null && coverage != null)
            getString(R.string.icon_pack_coverage, IconPacks.currentName(this), coverage.first, coverage.second)
        else IconPacks.currentName(this)
    ) {
        val names = listOf(getString(R.string.icons_tv_pictures), getString(R.string.icons_app_icons)) +
            packs.map { it.name } + getString(R.string.icons_get_more)
        val current = when {
            usingPictures -> 0
            chosenPack == null -> 1
            else -> 2 + packs.indexOfFirst { it.pkg == chosenPack }
        }
        chooseOne(getString(R.string.set_icons), names, current) { which ->
            when {
                which == 0 -> IconPacks.useTvPictures(this)
                which == 1 -> IconPacks.useIcons(this, null)
                which < 2 + packs.size -> IconPacks.useIcons(this, packs[which - 2].pkg)
                else -> {
                    openPackStore()
                    return@chooseOne
                }
            }
            changed()
        }
    }
    if (!usingPictures && chosenPack != null) {
        val loadedHasFrame = try {
            IconPacks.load(this, chosenPack).hasFrame
        } catch (e: Exception) {
            false
        }
        row(
            R.string.set_pack_missing,
            Look.PACK_MISSING.name(this) +
                if (Look.PACK_MISSING.get(this) == Look.MISSING_FRAME && !loadedHasFrame) getString(R.string.missing_no_frame) else ""
        ) {
            chooseOne(getString(R.string.set_pack_missing), Look.PACK_MISSING.names.map { getString(it) },
                Look.PACK_MISSING.get(this)) {
                Look.PACK_MISSING.set(this, it)
                Prefs.appsChanged = true
                changed()
            }
        }
    }
    if (!usingPictures) {
        val inCard = Look.TILE_STYLE.get(this) == Look.TILE_ICON_NAME
        row(R.string.set_name_in_card, getString(if (inCard) R.string.on else R.string.off)) {
            Look.TILE_STYLE.set(this, if (inCard) Look.TILE_ICON else Look.TILE_ICON_NAME)
            changed()
        }
    }
    row(R.string.set_card_size, cardSizeName(-1)) {
        chooseOne(getString(R.string.set_card_size), Look.CARD_SIZE.names.map { getString(it) },
            Look.CARD_SCALES.indexOfFirst { it == Look.tileScale(this) }) {
            Look.CARD_SIZE.set(this, it)
            changed()
        }
    }
    choice(R.string.set_card_shape, Look.CARD_SHAPE)
    if (!usingPictures) {
        choice(R.string.set_icon_fill, Look.ICON_FILL)
        choice(R.string.set_icon_tint, Look.ICON_TINT)
    }
    row(R.string.pack_get, IconPackStore.status.ifEmpty { getString(R.string.pack_get_summary) }) { openPackStore() }
    heading(R.string.heading_cards)
    choice(R.string.set_tile_corners, Look.TILE_CORNERS)
    choice(R.string.set_tile_card, Look.TILE_CARD)
    switch(R.string.set_tile_edge, Look.TILE_EDGE, R.string.shown, R.string.hidden)
    choice(R.string.set_tile_gap, Look.TILE_GAP)
    choice(R.string.set_tile_names, Look.TILE_NAMES)
    switch(R.string.set_new_badge, Look.NEW_BADGE, R.string.new_badge_on, R.string.off)
    switch(R.string.set_shine, Look.SHINE, R.string.shine_on, R.string.off)
    switch(R.string.set_scroll_names, Look.SCROLL_NAMES, R.string.scroll_names_on, R.string.off)
    heading(R.string.heading_selected)
    choice(R.string.set_focus_zoom, Look.FOCUS_ZOOM)
    choice(R.string.set_focus_motion, Look.FOCUS_MOTION)
    choice(R.string.set_focus_shadow, Look.FOCUS_SHADOW)
    choice(R.string.set_focus_outline, Look.FOCUS_OUTLINE)
    switch(R.string.set_dim_others, Look.DIM_OTHERS)
    row(R.string.set_app_picture, getString(R.string.set_app_picture_summary)) {
        Toast.makeText(this, R.string.set_app_picture_summary, Toast.LENGTH_LONG).show()
    }
}

/** Free icon packs from F-Droid, each marked if it's already on the TV. */
internal fun SettingsActivity.openPackStore() {
    val offers = IconPackStore.CATALOGUE
    val names = offers.map { offer ->
        val about = getString(offer.about)
        if (IconPackStore.isInstalled(this, offer.pkg)) getString(R.string.pack_entry_installed, offer.name, about)
        else getString(R.string.pack_entry, offer.name, about)
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.pack_get)
        .setItems(names.toTypedArray()) { _, which ->
            val offer = offers[which]
            if (IconPackStore.isInstalled(this, offer.pkg)) {
                IconPacks.useIcons(this, offer.pkg)
                changed()
                return@setItems
            }
            AlertDialog.Builder(this)
                .setTitle(offer.name)
                .setMessage(getString(R.string.pack_confirm, getString(offer.about), offer.source.label))
                .setPositiveButton(R.string.pack_download) { _, _ -> IconPackStore.get(this, offer) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        .setNegativeButton(R.string.close, null)
        .show()
}

internal fun SettingsActivity.soundsPage() {
    row(R.string.snd_set, Sounds.SET.name(this)) {
        chooseOne(getString(R.string.snd_set), Sounds.SET.names.map { getString(it) }, Sounds.SET.get(this)) {
            Sounds.SET.set(this, it)
            // Play the one chosen, to hear it
            Sounds.play(this, Sounds.SELECT)
            changed()
        }
    }
    if (Sounds.SET.get(this) != Sounds.SET_OFF) {
        choice(R.string.snd_volume, Sounds.VOLUME) {
            Sounds.play(this, Sounds.SELECT)
            changed()
        }
        switch(R.string.snd_on_move, Sounds.ON_MOVE, R.string.snd_on_move_on, R.string.snd_on_move_off)
    }
    heading(R.string.heading_motion)
    choice(R.string.set_animations, Look.ANIMATIONS)
    choice(R.string.set_focus_motion, Look.FOCUS_MOTION)
    choice(R.string.set_focus_shadow, Look.FOCUS_SHADOW)
    choice(R.string.set_focus_zoom, Look.FOCUS_ZOOM)
}

internal fun SettingsActivity.topBarPage() {
    switch(R.string.set_show_brand, Look.SHOW_BRAND, R.string.shown, R.string.hidden)
    if (Look.SHOW_BRAND.get(this)) {
        choice(R.string.set_brand_parts, Look.BRAND_PARTS)
        choice(R.string.set_brand_size, Look.BRAND_SIZE)
        if (Look.BRAND_PARTS.get(this) != Look.BRAND_LOGO) {
            switch(R.string.set_show_version, Look.SHOW_VERSION, R.string.shown, R.string.hidden)
        }
    }
    choice(R.string.set_header_gap, Look.HEADER_GAP)
    choice(R.string.set_clock, Look.CLOCK)
    choice(R.string.set_clock_face, Look.CLOCK_FACE)
    choice(R.string.set_clock_size, Look.CLOCK_SIZE)
    choice(R.string.set_date, Look.DATE)
    choice(R.string.set_system_info, Look.SYSTEM_INFO)
    switch(R.string.set_show_network, Look.SHOW_NETWORK, R.string.shown, R.string.hidden)
    switch(R.string.set_show_vpn, Look.SHOW_VPN, R.string.vpn_shown, R.string.hidden)
    switch(R.string.set_show_who, Look.SHOW_WHO, R.string.set_show_who_on, R.string.hidden)
    switch(R.string.set_show_search, Look.SHOW_SEARCH_BUTTON, R.string.shown, R.string.hidden)
    switch(R.string.set_show_update, Look.SHOW_UPDATE_BUTTON, R.string.shown, R.string.hidden)
    switch(R.string.set_show_tv_settings, Look.SHOW_TV_SETTINGS_BUTTON, R.string.shown, R.string.hidden)
    row(R.string.set_settings_button, getString(R.string.set_settings_button_summary)) {
        Toast.makeText(this, R.string.set_settings_button_summary, Toast.LENGTH_LONG).show()
    }
}
