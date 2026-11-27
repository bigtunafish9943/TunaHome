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

/**
 * TunaHome's settings. The first page lists the areas, most used first; each opens a page of
 * its own. Every row says what it is set to now, and selecting it changes it.
 */
class SettingsActivity : BaseActivity() {
    internal lateinit var list: LinearLayout
    internal var page = MAIN
    internal var focusIndex = 0

    /** A section to open the menu of straight away (from an app's menu on the home screen). */
    internal var openSection: String? = null
    internal val rows = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        page = intent.getStringExtra(EXTRA_PAGE) ?: MAIN
        openSection = intent.getStringExtra(EXTRA_SECTION)
        list = findViewById(R.id.list)
        findViewById<TextView>(R.id.brandVersion).text =
            getString(R.string.version, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.pageTitle).setText(pageTitle(page))
        focusIndex = savedInstanceState?.getInt(STATE_FOCUS) ?: intent.getIntExtra(EXTRA_FOCUS, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FOCUS, focusIndex)
    }

    override fun onResume() {
        super.onResume()
        // Kids mode: TunaHome settings are for grown-ups
        if (Guard.kidsModeOn(this) && !Guard.grownUpRecently()) {
            list.removeAllViews()
            Guard.askGrownUp(this, cancelled = { finish() }) { recreate() }
            return
        }
        // Rebuilt each time: some settings change on other screens (the TV's, a wallpaper app's)
        IconPackStore.resumeIfWaiting(this)
        val waiting = IconPackStore.waitingFor
        if (waiting != null && IconPackStore.isInstalled(this, waiting)) {
            // Just installed through TunaHome: use it, as the viewer asked
            IconPackStore.waitingFor = null
            IconPacks.useIcons(this, waiting)
            Toast.makeText(this, R.string.pack_now_using, Toast.LENGTH_LONG).show()
            if (outOfDate()) {
                recreate()
                return
            }
        }
        build()
        Updater.resumeIfWaiting(this)
    }

    /** Takes the current look as this screen's own (for the files that extend it). */
    internal fun takeLook() = acceptLook()

    // ------------------------------------------------------------------ building a page

    internal fun build() {
        list.removeAllViews()
        rows.clear()
        buildPage(page)
        (rows.getOrNull(focusIndex) ?: rows.firstOrNull())?.requestFocus()
        openSection?.let { id ->
            openSection = null
            val all = Rows.load(this)
            val at = all.indexOfFirst { it.id == id }
            if (at >= 0) {
                list.post { sectionMenu(id, at, all.size) }
            }
        }
    }

    // ------------------------------------------------------------------ searching settings

    internal class Found(val title: String, val summary: String, val page: String, val index: Int)

    /** While searching: rows are noted here instead of shown. */
    internal var collecting: MutableList<Found>? = null
    internal var collectingPage = MAIN
    internal var collectedOnPage = 0

    // ------------------------------------------------------------------ fine tuning

    /** A few of the viewer's apps drawn with the current settings, redrawn as they change. */
    internal var preview: androidx.recyclerview.widget.RecyclerView? = null

    internal fun buildPage(page: String) {
        when (page) {
            BACKGROUND -> backgroundPage()
            COLOURS -> coloursPage()
            SECTIONS -> sectionsPage()
            TEXT -> textPage()
            PICTURES -> picturesPage()
            TOP_BAR -> topBarPage()
            WEATHER -> weatherPage()
            SHORTCUTS -> shortcutsPage()
            SOUNDS -> soundsPage()
            LAYOUTS -> layoutsPage()
            APPS -> appsPage()
            HOME_BUTTON -> homeButtonPage()
            UPDATES -> updatesPage()
            SEARCH -> searchPage()
            FINE -> finePage()
            PROFILES -> profilesPage()
            else -> mainPage()
        }
    }

    internal fun row(title: CharSequence, summary: CharSequence, action: () -> Unit): View? {
        collecting?.let {
            it.add(Found(title.toString(), summary.toString(), collectingPage, collectedOnPage++))
            return null
        }
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
        return view
    }

    internal fun row(title: Int, summary: CharSequence, action: () -> Unit) =
        row(getString(title), summary, action)

    /** A small heading between groups of rows. */
    internal fun heading(text: Int) {
        if (collecting != null) return
        val view = layoutInflater.inflate(R.layout.item_heading, list, false) as TextView
        view.setText(text)
        Look.styleText(view)
        list.addView(view)
    }

    /** A setting with named options. */
    internal fun choice(title: Int, setting: Look.Choice, then: () -> Unit = ::changed) {
        // Tuned step by step on the Fine tuning page: say so, until a named option is picked
        val fine = Look.allFine().firstOrNull { it.key == setting.fine }?.takeIf { it.isSet(this) }
        val summary = if (fine != null) getString(R.string.fine_tuned, fine.name(this)) else setting.name(this)
        row(title, summary) {
            chooseOne(getString(title), setting.names.map { getString(it) }, setting.get(this)) {
                setting.set(this, it)
                then()
            }
        }
    }

    /** A setting that is on or off. */
    internal fun switch(title: Int, setting: Look.Switch, on: Int = R.string.on, off: Int = R.string.off) {
        val value = setting.get(this)
        row(title, getString(if (value) on else off)) {
            setting.set(this, !value)
            changed()
        }
    }

    /** Opens another page of settings. */
    internal fun pageRow(page: String, summary: CharSequence) {
        row(pageTitle(page), summary) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page))
        }
    }

    /** After a setting changed: rebuild the whole screen if its look changed, else just the list. */
    internal fun changed() {
        if (outOfDate()) {
            recreate()
        } else {
            build()
        }
    }

    // ------------------------------------------------------------------ the first page

    internal fun mainPage() {
        row(R.string.settings_search, getString(R.string.settings_search_summary)) { askSearch() }
        row(R.string.phone_title, getString(R.string.phone_summary)) {
            startActivity(Intent(this, PhoneSetupActivity::class.java))
        }
        pageRow(BACKGROUND, backgroundSummary())
        pageRow(SECTIONS, sectionsSummary())
        pageRow(
            COLOURS,
            getString(TunaTheme.nameOf(TunaTheme.current(this))) + " · " + Look.ACCENT.name(this)
        )
        pageRow(TEXT, Fonts.name(this, Look.font(this)) + " · " + Look.TEXT_SIZE.name(this))
        Look.allFine().count { it.isSet(this) }.let { n ->
            pageRow(FINE, if (n == 0) getString(R.string.fine_summary_none)
                else resources.getQuantityString(R.plurals.fine_summary_count, n, n))
        }
        pageRow(PICTURES, IconPacks.currentName(this) + " · " + cardSizeName(-1))
        pageRow(TOP_BAR, Look.CLOCK.name(this))
        pageRow(SOUNDS, Sounds.SET.name(this) + " · " + Look.ANIMATIONS.name(this))
        Hotkeys.all(this).size.let { n ->
            pageRow(SHORTCUTS, if (n == 0) getString(R.string.shortcuts_none_short)
                else resources.getQuantityString(R.plurals.shortcuts_count, n, n))
        }
        pageRow(
            WEATHER,
            if (!Weather.SHOW.get(this)) getString(R.string.off)
            else Weather.place(this)?.name ?: getString(R.string.wx_place_none)
        )
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
        pageRow(PROFILES, profilesSummary())
        Layouts.saved(this).size.let { n ->
            pageRow(LAYOUTS, if (n == 0) getString(R.string.layouts_none_short)
                else resources.getQuantityString(R.plurals.layouts_count, n, n))
        }
        pageRow(UPDATES, getString(R.string.set_update_summary, BuildConfig.VERSION_NAME))
        row(R.string.tv_settings, getString(R.string.set_tv_settings_summary)) {
            HomeActivity.openTvSettings(this)
        }
        row(R.string.link_title, if (PcLink.linked(this)) getString(R.string.link_linked_to, PcLink.pcName(this))
            else getString(R.string.link_summary)) {
            PcLink.manage(this) { build() }
        }
        row(R.string.two_up_title, getString(R.string.two_up_summary)) { TwoUpActivity.open(this) }
        row(R.string.remote_title, getString(if (PhoneRemote.ON.get(this)) R.string.remote_summary_on else R.string.remote_summary)) {
            PhoneRemote.manage(this) { build() }
        }
        row(R.string.usage_title, getString(R.string.usage_summary)) { UsageLog.show(this) }
        row(R.string.picture_test, getString(R.string.picture_test_summary)) {
            startActivity(android.content.Intent(this, PictureTestActivity::class.java))
        }
        row(R.string.welcome_again, getString(R.string.welcome_again_summary)) { WelcomeActivity.start(this) }
    }

    // ------------------------------------------------------------------ background

    // ------------------------------------------------------------------ theme and colours

    // ------------------------------------------------------------------ sections

    internal fun linesName(lines: Int): String =
        if (lines == Rows.WRAP) getString(R.string.lines_wrap)
        else resources.getQuantityString(R.plurals.lines_count, lines, lines)

    // ------------------------------------------------------------------ text

    // ------------------------------------------------------------------ app pictures

    // ------------------------------------------------------------------ layouts and backup

    internal var exportedName = ""

    // ------------------------------------------------------------------ sounds and motion

    // ------------------------------------------------------------------ top of the screen

    // ------------------------------------------------------------------ shortcuts

    // ------------------------------------------------------------------ weather

    // ------------------------------------------------------------------ apps

    internal fun appName(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        pkg
    }

    // ------------------------------------------------------------------ Home button

    // ------------------------------------------------------------------ updates

    // ------------------------------------------------------------------ picking files

    internal fun pick(request: Int, type: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (e: ActivityNotFoundException) {
            QuietDialog.Builder(this)
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
            REQUEST_LAYOUT -> confirmImport {
                work(R.string.layout_import_failed, { ctx ->
                    ctx.contentResolver.openInputStream(uri)?.use { Layouts.read(ctx, it) }
                        ?: throw IllegalStateException("unreadable")
                }) { Layouts.restart(this) }
            }
        }
    }

    /** Runs [work] off the main thread; on success runs [then], otherwise says [failure]. */
    internal fun inBackground(failure: Int, work: (Context) -> Boolean, then: () -> Unit) {
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
    internal fun copyOwn(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        val isVideo = !type.startsWith("image/")
        val extension = when {
            type.contains("png") -> "png"
            type.startsWith("image/") -> "jpg"
            type.contains("webm") -> "webm"
            type.contains("matroska") -> "mkv"
            else -> "mp4"
        }
        val progress = QuietDialog.Builder(this)
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

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // TV listings (Watch Next) or Bluetooth just allowed: show it on the page
        build()
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_SECTION = "section"
        const val EXTRA_FOCUS = "focus"
        internal const val EXTRA_QUERY = "query"
        const val SEARCH = "search"

        /** The pages a settings search looks through. */
        internal val SEARCHABLE = listOf(
            "background", "sections", "colours", "text", "pictures", "top_bar", "sounds", "shortcuts",
            "weather", "apps", "home_button", "layouts", "updates", "fine", "profiles",
        )
        internal const val STATE_FOCUS = "focus"

        const val MAIN = "main"
        const val BACKGROUND = "background"
        const val COLOURS = "colours"
        const val SECTIONS = "sections"
        const val TEXT = "text"
        const val PICTURES = "pictures"
        const val TOP_BAR = "top_bar"
        const val WEATHER = "weather"
        const val SHORTCUTS = "shortcuts"
        const val SOUNDS = "sounds"
        const val LAYOUTS = "layouts"
        const val APPS = "apps"
        const val HOME_BUTTON = "home_button"
        const val UPDATES = "updates"
        const val FINE = "fine"
        const val PROFILES = "profiles"

        fun pageTitle(page: String): Int = when (page) {
            BACKGROUND -> R.string.page_background
            COLOURS -> R.string.page_colours
            SECTIONS -> R.string.page_sections
            TEXT -> R.string.page_text
            PICTURES -> R.string.page_pictures
            TOP_BAR -> R.string.page_top_bar
            WEATHER -> R.string.page_weather
            SHORTCUTS -> R.string.page_shortcuts
            SOUNDS -> R.string.page_sounds
            LAYOUTS -> R.string.page_layouts
            APPS -> R.string.page_apps
            HOME_BUTTON -> R.string.page_home_button
            UPDATES -> R.string.page_updates
            FINE -> R.string.page_fine
            PROFILES -> R.string.page_profiles
            else -> R.string.settings
        }

        internal val DPAD = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        internal const val REQUEST_WALLPAPER = 2
        internal const val REQUEST_FONT = 3
        internal const val REQUEST_OWN_DOCUMENT = 4
        internal const val REQUEST_OWN_COPY = 5
        internal const val REQUEST_LAYOUT = 6
    }
}
