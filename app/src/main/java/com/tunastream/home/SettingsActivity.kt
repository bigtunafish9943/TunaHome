package com.tunastream.home

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * TunaHome's settings. The first page lists the areas, most used first; each opens a page of
 * its own. Every row says what it is set to now, and selecting it changes it.
 */
class SettingsActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private var page = MAIN
    private var focusIndex = 0
    private val rows = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        page = intent.getStringExtra(EXTRA_PAGE) ?: MAIN
        list = findViewById(R.id.list)
        findViewById<TextView>(R.id.brandVersion).text =
            getString(R.string.version, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.pageTitle).setText(pageTitle(page))
        focusIndex = savedInstanceState?.getInt(STATE_FOCUS) ?: 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FOCUS, focusIndex)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt each time: some settings change on other screens (the TV's, a wallpaper app's)
        IconPackStore.resumeIfWaiting(this)
        val waiting = IconPackStore.waitingFor
        if (waiting != null && IconPackStore.isInstalled(this, waiting)) {
            // Just installed through TunaHome: use it, as the viewer asked
            IconPackStore.waitingFor = null
            IconPacks.choose(this, waiting)
            Look.TILE_STYLE.set(this, Look.TILE_ICON)
            Prefs.appsChanged = true
            Toast.makeText(this, R.string.pack_now_using, Toast.LENGTH_LONG).show()
            if (outOfDate()) {
                recreate()
                return
            }
        }
        build()
        Updater.resumeIfWaiting(this)
    }

    // ------------------------------------------------------------------ building a page

    private fun build() {
        list.removeAllViews()
        rows.clear()
        when (page) {
            BACKGROUND -> backgroundPage()
            COLOURS -> coloursPage()
            SECTIONS -> sectionsPage()
            TEXT -> textPage()
            PICTURES -> picturesPage()
            TOP_BAR -> topBarPage()
            APPS -> appsPage()
            HOME_BUTTON -> homeButtonPage()
            UPDATES -> updatesPage()
            else -> mainPage()
        }
        (rows.getOrNull(focusIndex) ?: rows.firstOrNull())?.requestFocus()
    }

    private fun row(title: CharSequence, summary: CharSequence, action: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.item_setting, list, false)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.summary).text = summary
        val index = rows.size
        view.setOnClickListener {
            focusIndex = index
            action()
        }
        view.setOnFocusChangeListener { _, focused ->
            if (focused) {
                focusIndex = index
            }
        }
        Look.styleText(view)
        list.addView(view)
        rows.add(view)
    }

    private fun row(title: Int, summary: CharSequence, action: () -> Unit) =
        row(getString(title), summary, action)

    /** A small heading between groups of rows. */
    private fun heading(text: Int) {
        val view = layoutInflater.inflate(R.layout.item_heading, list, false) as TextView
        view.setText(text)
        Look.styleText(view)
        list.addView(view)
    }

    /** A setting with named options. */
    private fun choice(title: Int, setting: Look.Choice, then: () -> Unit = ::changed) {
        row(title, setting.name(this)) {
            chooseOne(getString(title), setting.names.map { getString(it) }, setting.get(this)) {
                setting.set(this, it)
                then()
            }
        }
    }

    /** A setting that is on or off. */
    private fun switch(title: Int, setting: Look.Switch, on: Int = R.string.on, off: Int = R.string.off) {
        val value = setting.get(this)
        row(title, getString(if (value) on else off)) {
            setting.set(this, !value)
            changed()
        }
    }

    /** Opens another page of settings. */
    private fun pageRow(page: String, summary: CharSequence) {
        row(pageTitle(page), summary) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page))
        }
    }

    /** After a setting changed: rebuild the whole screen if its look changed, else just the list. */
    private fun changed() {
        if (outOfDate()) {
            recreate()
        } else {
            build()
        }
    }

    // ------------------------------------------------------------------ the first page

    private fun mainPage() {
        pageRow(BACKGROUND, backgroundSummary())
        pageRow(SECTIONS, sectionsSummary())
        pageRow(
            COLOURS,
            getString(TunaTheme.nameOf(TunaTheme.current(this))) + " · " + Look.ACCENT.name(this)
        )
        pageRow(TEXT, Fonts.name(this, Look.font(this)) + " · " + Look.TEXT_SIZE.name(this))
        pageRow(PICTURES, Look.TILE_STYLE.name(this) + " · " + Look.TILE_SIZE.name(this))
        pageRow(TOP_BAR, Look.CLOCK.name(this))
        val hidden = Prefs.hidden(this).size
        pageRow(
            APPS,
            if (hidden == 0) getString(R.string.set_hidden_none_short)
            else resources.getQuantityString(R.plurals.hidden_short, hidden, hidden)
        )
        pageRow(
            HOME_BUTTON,
            getString(if (HomeButtonService.isOn(this)) R.string.on else R.string.off)
        )
        pageRow(UPDATES, getString(R.string.set_update_summary, BuildConfig.VERSION_NAME))
        row(R.string.tv_settings, getString(R.string.set_tv_settings_summary)) {
            HomeActivity.openTvSettings(this)
        }
    }

    private fun backgroundSummary(): String {
        val source = Backdrop.SOURCE.get(this)
        if (source == Backdrop.SOURCE_APP) {
            WallpaperApps.find(this, Backdrop.app(this))?.let { return it.name }
        }
        return Backdrop.SOURCE.name(this)
    }

    private fun sectionsSummary(): String {
        val shown = Rows.load(this).count { it.visible }
        return resources.getQuantityString(R.plurals.sections_shown, shown, shown)
    }

    // ------------------------------------------------------------------ background

    private fun backgroundPage() {
        val source = Backdrop.SOURCE.get(this)
        row(R.string.set_background, Backdrop.SOURCE.name(this)) { chooseBackground() }

        when (source) {
            Backdrop.SOURCE_PICTURE -> {
                row(
                    R.string.wallpaper_choose,
                    getString(if (Pictures.hasWallpaper(this)) R.string.set_wallpaper_set else R.string.set_wallpaper_none)
                ) { pick(REQUEST_WALLPAPER, "image/*") }
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
            if (source != Backdrop.SOURCE_PICTURE) {
                choice(R.string.bg_change_every, Backdrop.CHANGE_EVERY)
                switch(R.string.bg_shuffle, Backdrop.SHUFFLE, R.string.bg_shuffle_on, R.string.bg_shuffle_off)
                switch(R.string.bg_play_videos, Backdrop.PLAY_VIDEOS, R.string.bg_play_videos_on, R.string.bg_play_videos_off)
            }
            if (source == Backdrop.SOURCE_APP) {
                switch(R.string.bg_caption, Backdrop.SHOW_CAPTION, R.string.shown, R.string.hidden)
            }
        }
    }

    private fun chooseBackground() {
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
                pick(REQUEST_WALLPAPER, "image/*")
            }
        }
    }

    /** No wallpaper app on the TV: say what one is and offer Aerial Views. */
    private fun offerAerialViews() {
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

    private fun chooseWallpaperApp() {
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

    private fun addOwnFromTv() {
        // Lasting access where the TV allows it; otherwise TunaHome keeps its own copy
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(open, REQUEST_OWN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            pick(REQUEST_OWN_COPY, "video/*")
        }
    }

    private fun removeOwn() {
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

    // ------------------------------------------------------------------ theme and colours

    private fun coloursPage() {
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

    // ------------------------------------------------------------------ sections

    private fun sectionsPage() {
        val all = Rows.load(this)
        all.forEachIndexed { index, section ->
            val lines = linesName(section.lines)
            val summary = if (section.visible) getString(R.string.section_shown, lines)
            else getString(R.string.section_hidden)
            row(section.name(this), summary) { sectionMenu(section.id, index, all.size) }
        }
        row(R.string.section_add, getString(R.string.section_add_summary)) {
            askForText(R.string.section_add, "") { title ->
                Rows.add(this, title)
                changed()
            }
        }
        heading(R.string.heading_all_sections)
        choice(R.string.set_row_titles, Look.ROW_TITLES)
        choice(R.string.set_row_gap, Look.ROW_GAP)
        choice(R.string.set_row_scroll, Look.ROW_SCROLL)
        choice(R.string.set_start_on, Look.START_ON)
    }

    private fun linesName(lines: Int): String =
        if (lines == Rows.WRAP) getString(R.string.lines_wrap)
        else resources.getQuantityString(R.plurals.lines_count, lines, lines)

    private fun sectionMenu(id: String, index: Int, count: Int) {
        val section = Rows.load(this).firstOrNull { it.id == id } ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(getString(if (section.visible) R.string.section_hide else R.string.section_show))
        actions.add { Rows.update(this, id) { it.visible = !it.visible }; changed() }

        labels.add(getString(R.string.section_lines, linesName(section.lines)))
        actions.add {
            val options = (1..Rows.MAX_LINES).toList() + Rows.WRAP
            chooseOne(
                getString(R.string.section_lines_title, section.name(this)),
                options.map { linesName(it) }, options.indexOf(section.lines)
            ) { which ->
                Rows.update(this, id) { it.lines = options[which] }
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
                AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
            .setTitle(section.name(this))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun moveSection(id: String, by: Int) {
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

    // ------------------------------------------------------------------ text

    private fun textPage() {
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
                pick(REQUEST_FONT, "*/*")
            } else {
                val options = listOf(getString(R.string.own_font_replace), getString(R.string.own_font_remove))
                chooseOne(getString(R.string.set_own_font), options, -1) {
                    if (it == 0) {
                        pick(REQUEST_FONT, "*/*")
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
    private fun chooseFont(title: String, current: String?, allowSame: Boolean, pick: (String?) -> Unit) {
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

    // ------------------------------------------------------------------ app pictures

    private fun picturesPage() {
        choice(R.string.set_tile_style, Look.TILE_STYLE)
        choice(R.string.set_tile_size, Look.TILE_SIZE)
        val packs = IconPacks.installed(this)
        val chosenPack = IconPacks.chosen(this)
        row(
            R.string.set_icon_pack,
            packs.firstOrNull { it.pkg == chosenPack }?.name
                ?: getString(if (packs.isEmpty()) R.string.icon_pack_none_installed else R.string.icon_pack_none)
        ) {
            if (packs.isEmpty()) {
                openPackStore()
            } else {
                val names = listOf(getString(R.string.icon_pack_none)) + packs.map { it.name }
                val current = packs.indexOfFirst { it.pkg == chosenPack } + 1
                chooseOne(getString(R.string.set_icon_pack), names, current) {
                    IconPacks.choose(this, if (it == 0) null else packs[it - 1].pkg)
                    Prefs.appsChanged = true
                    if (it != 0 && Look.TILE_STYLE.get(this) == Look.TILE_BANNER) {
                        offerIcons()
                    } else {
                        changed()
                    }
                }
            }
        }
        row(R.string.pack_get, getString(R.string.pack_get_summary)) { openPackStore() }
        heading(R.string.heading_cards)
        choice(R.string.set_tile_corners, Look.TILE_CORNERS)
        choice(R.string.set_tile_card, Look.TILE_CARD)
        switch(R.string.set_tile_edge, Look.TILE_EDGE, R.string.shown, R.string.hidden)
        choice(R.string.set_tile_gap, Look.TILE_GAP)
        choice(R.string.set_tile_names, Look.TILE_NAMES)
        heading(R.string.heading_selected)
        choice(R.string.set_focus_zoom, Look.FOCUS_ZOOM)
        choice(R.string.set_focus_outline, Look.FOCUS_OUTLINE)
        switch(R.string.set_dim_others, Look.DIM_OTHERS)
        row(R.string.set_app_picture, getString(R.string.set_app_picture_summary)) {
            Toast.makeText(this, R.string.set_app_picture_summary, Toast.LENGTH_LONG).show()
        }
    }

    /** Icon packs change icons, but TV pictures hide most of them: offer to show icons. */
    private fun offerIcons() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pack_show_icons_title)
            .setMessage(R.string.pack_show_icons_body)
            .setPositiveButton(R.string.pack_show_icons) { _, _ ->
                Look.TILE_STYLE.set(this, Look.TILE_ICON)
                changed()
            }
            .setNegativeButton(R.string.pack_keep_pictures) { _, _ -> changed() }
            .setOnCancelListener { changed() }
            .show()
    }

    /** Free icon packs from F-Droid, each marked if it's already on the TV. */
    private fun openPackStore() {
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
                    IconPacks.choose(this, offer.pkg)
                    Prefs.appsChanged = true
                    if (Look.TILE_STYLE.get(this) == Look.TILE_BANNER) offerIcons() else changed()
                    return@setItems
                }
                AlertDialog.Builder(this)
                    .setTitle(offer.name)
                    .setMessage(getString(R.string.pack_confirm, getString(offer.about)))
                    .setPositiveButton(R.string.pack_download) { _, _ -> IconPackStore.get(this, offer) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------------ top of the screen

    private fun topBarPage() {
        switch(R.string.set_show_brand, Look.SHOW_BRAND, R.string.shown, R.string.hidden)
        switch(R.string.set_show_version, Look.SHOW_VERSION, R.string.shown, R.string.hidden)
        choice(R.string.set_clock, Look.CLOCK)
        choice(R.string.set_clock_size, Look.CLOCK_SIZE)
        choice(R.string.set_date, Look.DATE)
        switch(R.string.set_show_update, Look.SHOW_UPDATE_BUTTON, R.string.shown, R.string.hidden)
        switch(R.string.set_show_tv_settings, Look.SHOW_TV_SETTINGS_BUTTON, R.string.shown, R.string.hidden)
        row(R.string.set_settings_button, getString(R.string.set_settings_button_summary)) {
            Toast.makeText(this, R.string.set_settings_button_summary, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ apps

    private fun appsPage() {
        val hiddenCount = Prefs.hidden(this).size
        row(
            R.string.set_hidden,
            if (hiddenCount == 0) getString(R.string.set_hidden_none)
            else resources.getQuantityString(R.plurals.set_hidden_count, hiddenCount, hiddenCount)
        ) { chooseHidden() }

        val ownOrder = Prefs.hasOwnOrder(this)
        row(
            R.string.set_order,
            getString(if (ownOrder) R.string.set_order_own else R.string.set_order_az)
        ) {
            if (ownOrder) {
                AlertDialog.Builder(this)
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

    private fun chooseHidden() {
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
        AlertDialog.Builder(this)
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

    // ------------------------------------------------------------------ Home button

    private fun homeButtonPage() {
        val homeOn = HomeButtonService.isOn(this)
        row(
            R.string.set_home_button,
            getString(if (homeOn) R.string.set_home_button_on else R.string.set_home_button_off)
        ) { explainHomeButton(homeOn) }

        val replace = Prefs.replaceStock(this)
        row(
            R.string.set_replace_stock,
            getString(if (replace) R.string.set_replace_stock_on else R.string.set_replace_stock_off)
        ) {
            Prefs.setReplaceStock(this, !replace)
            build()
        }
    }

    /** TunaHome never switches this on by itself: it explains, and opens the TV's own screen. */
    private fun explainHomeButton(on: Boolean) {
        AlertDialog.Builder(this)
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

    // ------------------------------------------------------------------ updates

    private fun updatesPage() {
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
        row(R.string.set_about, getString(R.string.set_about_summary)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.version, BuildConfig.VERSION_NAME))
                .setMessage(R.string.about_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ picking files

    private fun pick(request: Int, type: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setMessage(R.string.wallpaper_no_picker)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            return
        }
        when (requestCode) {
            REQUEST_WALLPAPER -> inBackground(R.string.wallpaper_failed, { Pictures.saveWallpaper(it, uri) }) {
                Backdrop.SOURCE.set(this, Backdrop.SOURCE_PICTURE)
            }
            REQUEST_FONT -> inBackground(R.string.own_font_failed, { Fonts.saveCustom(it, uri) }) {
                Look.setFont(this, Fonts.CUSTOM)
            }
            REQUEST_OWN_DOCUMENT -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // No lasting access on this TV: keep a copy instead
                    copyOwn(uri)
                    return
                }
                val isVideo = contentResolver.getType(uri)?.startsWith("image/") != true
                Backdrop.setVideos(this, Backdrop.videos(this) + Backdrop.Own(uri.toString(), isVideo))
                changed()
            }
            REQUEST_OWN_COPY -> copyOwn(uri)
        }
    }

    /** Runs [work] off the main thread; on success runs [then], otherwise says [failure]. */
    private fun inBackground(failure: Int, work: (Context) -> Boolean, then: () -> Unit) {
        val app = applicationContext
        thread(name = "TunaHome settings") {
            val ok = try {
                work(app)
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (isDestroyed) {
                    return@runOnUiThread
                }
                if (ok) {
                    then()
                    Look.touch(this)
                } else {
                    Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                }
                changed()
            }
        }
    }

    /** Copies a chosen video or picture into TunaHome, showing how far it has got. */
    private fun copyOwn(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        val isVideo = !type.startsWith("image/")
        val extension = when {
            type.contains("png") -> "png"
            type.startsWith("image/") -> "jpg"
            type.contains("webm") -> "webm"
            type.contains("matroska") -> "mkv"
            else -> "mp4"
        }
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.bg_copying)
            .setMessage(getString(R.string.bg_copied_mb, 0))
            .setCancelable(false)
            .show()
        val app = applicationContext
        thread(name = "TunaHome copy") {
            val address = try {
                Pictures.copyVideo(app, uri, extension) { bytes ->
                    runOnUiThread {
                        if (progress.isShowing) {
                            progress.setMessage(getString(R.string.bg_copied_mb, (bytes / (1024 * 1024)).toInt()))
                        }
                    }
                }
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (progress.isShowing && !isDestroyed) {
                    progress.dismiss()
                }
                if (isDestroyed) {
                    return@runOnUiThread
                }
                if (address == null) {
                    Toast.makeText(this, R.string.bg_copy_failed, Toast.LENGTH_LONG).show()
                } else {
                    Backdrop.setVideos(this, Backdrop.videos(this) + Backdrop.Own(address, isVideo))
                }
                changed()
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        private const val STATE_FOCUS = "focus"

        const val MAIN = "main"
        const val BACKGROUND = "background"
        const val COLOURS = "colours"
        const val SECTIONS = "sections"
        const val TEXT = "text"
        const val PICTURES = "pictures"
        const val TOP_BAR = "top_bar"
        const val APPS = "apps"
        const val HOME_BUTTON = "home_button"
        const val UPDATES = "updates"

        fun pageTitle(page: String): Int = when (page) {
            BACKGROUND -> R.string.page_background
            COLOURS -> R.string.page_colours
            SECTIONS -> R.string.page_sections
            TEXT -> R.string.page_text
            PICTURES -> R.string.page_pictures
            TOP_BAR -> R.string.page_top_bar
            APPS -> R.string.page_apps
            HOME_BUTTON -> R.string.page_home_button
            UPDATES -> R.string.page_updates
            else -> R.string.settings
        }

        private const val REQUEST_WALLPAPER = 2
        private const val REQUEST_FONT = 3
        private const val REQUEST_OWN_DOCUMENT = 4
        private const val REQUEST_OWN_COPY = 5
    }
}
