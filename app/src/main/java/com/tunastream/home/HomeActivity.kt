package com.tunastream.home

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
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

/**
 * The home screen: the name, a few small buttons and the clock, then sections of apps that run
 * the width of the screen, in the order and style the viewer chose, over the chosen background.
 */
class HomeActivity : BaseActivity() {
    /** One section of apps on screen. Hidden altogether while it has nothing in it. */
    private class Section(val row: Rows.Row, val view: View, val list: RecyclerView, val adapter: AppAdapter) {
        fun show(apps: List<AppEntry>) {
            adapter.submit(apps)
            view.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private lateinit var scroll: ScrollView
    private lateinit var appsHint: TextView
    private lateinit var backdrop: Backdrop
    private lateinit var tiles: TileStyle
    private val sections = ArrayList<Section>()

    private var apps: List<AppEntry> = emptyList()
    private var loaded = false
    private var loading = false
    private var loadAgain = false
    private var placedFocus = false

    /** The app whose picture is being chosen, while the TV's picture chooser is open. */
    private var choosingPictureFor: String? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        choosingPictureFor = savedInstanceState?.getString(STATE_PICTURE_FOR)

        scroll = findViewById(R.id.scroll)
        appsHint = findViewById(R.id.appsHint)
        backdrop = Backdrop(
            this,
            findViewById<ImageView>(R.id.backdropImage),
            findViewById<PlayerView>(R.id.backdropVideo),
            findViewById(R.id.backdropShade),
            findViewById(R.id.backdropCaption),
        )
        tiles = TileStyle(this)

        setUpTopBar()
        buildSections()

        // Apps installed, removed or updated while TunaHome is open
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }

        show()
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PICTURE_FOR, choosingPictureFor)
    }

    override fun onDestroy() {
        backdrop.stop()
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        backdrop.start()
    }

    /** Another app is in front: let go of the video player so that app can use the decoder. */
    override fun onStop() {
        backdrop.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.appsChanged) {
            Prefs.appsChanged = false
            reload()
        }
        Updater.resumeIfWaiting(this)
        if (Prefs.autoUpdateCheck(this)) {
            Updater.check(this, false)
        }
    }

    /** Home pressed while already here: back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (HomeButtonService.ACTION_HOME_PRESSED == intent.action && hasWindowFocus()) {
            goToTop()
        }
    }

    /** A home screen has nowhere to go back to, so Back returns to the top instead of closing. */
    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goToTop()
    }

    private fun goToTop() {
        scroll.smoothScrollTo(0, 0)
        focusFirst()
    }

    private fun focusFirst() {
        val first = sections.firstOrNull { it.adapter.appCount > 0 } ?: return
        focusIn(first, 0)
    }

    private fun focusIn(section: Section, position: Int) {
        section.list.scrollToPosition(position)
        section.list.post { section.list.layoutManager?.findViewByPosition(position)?.requestFocus() }
    }

    // ------------------------------------------------------------------ top of the screen

    private fun setUpTopBar() {
        findViewById<View>(R.id.brand).visibility = if (Look.SHOW_BRAND.get(this)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.brandVersion).apply {
            text = getString(R.string.version, BuildConfig.VERSION_NAME)
            visibility = if (Look.SHOW_VERSION.get(this@HomeActivity)) View.VISIBLE else View.GONE
        }

        val update = findViewById<View>(R.id.checkUpdate)
        update.visibility = if (Look.SHOW_UPDATE_BUTTON.get(this)) View.VISIBLE else View.GONE
        update.setOnClickListener { Updater.check(this, true) }
        val tvSettings = findViewById<View>(R.id.openTvSettings)
        tvSettings.visibility = if (Look.SHOW_TV_SETTINGS_BUTTON.get(this)) View.VISIBLE else View.GONE
        tvSettings.setOnClickListener { openTvSettings(this) }
        // Always shown: it is the way back to every other setting
        findViewById<View>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val clock = findViewById<TextClock>(R.id.clock)
        when (Look.CLOCK.get(this)) {
            Look.CLOCK_24 -> {
                clock.format12Hour = "HH:mm"
                clock.format24Hour = "HH:mm"
            }
            Look.CLOCK_12 -> {
                clock.format12Hour = "h:mm"
                clock.format24Hour = "h:mm"
            }
            Look.CLOCK_12_AMPM -> {
                clock.format12Hour = "h:mm a"
                clock.format24Hour = "h:mm a"
            }
            Look.CLOCK_HIDDEN -> clock.visibility = View.GONE
            else -> {
                clock.format12Hour = "h:mm"
                clock.format24Hour = "HH:mm"
            }
        }
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize * Look.clockScale(this))
        Look.clockFont(this)?.let { id -> Fonts.typeface(this, id, 300)?.let { clock.typeface = it } }

        val date = findViewById<TextClock>(R.id.date)
        val skeleton = when (Look.DATE.get(this)) {
            Look.DATE_SHORT -> "EEEdMMM"
            Look.DATE_LONG -> "EEEEdMMMM"
            else -> null
        }
        if (skeleton != null) {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
            date.format12Hour = pattern
            date.format24Hour = pattern
            date.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ sections

    private fun buildSections() {
        val holder = findViewById<LinearLayout>(R.id.sections)
        holder.removeAllViews()
        sections.clear()
        val titles = Look.ROW_TITLES.get(this)
        val rowGap = Math.round(Look.dp(this, Look.rowGapDp(this)))

        for (row in Rows.load(this)) {
            if (!row.visible) {
                continue
            }
            val view = layoutInflater.inflate(R.layout.item_section, holder, false)
            val title = view.findViewById<TextView>(R.id.sectionTitle)
            val name = row.name(this)
            title.text = if (titles == Look.TITLES_CAPS) name.uppercase(Locale.getDefault()) else name
            when (titles) {
                Look.TITLES_BOLD -> title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
                Look.TITLES_HIDDEN -> title.visibility = View.GONE
            }
            (title.layoutParams as LinearLayout.LayoutParams).topMargin = rowGap

            val list = view.findViewById<RecyclerView>(R.id.sectionApps)
            val lines = row.lines
            val adapter = AppAdapter(
                tiles, lines, ::open, { app -> showMenu(app, row) }, { order -> saveOrder(row, order) }
            )
            list.layoutManager = when {
                lines == Rows.WRAP -> {
                    val sidePadding = list.paddingStart + list.paddingEnd
                    val across = (resources.displayMetrics.widthPixels - sidePadding) /
                        (tiles.width + 2 * tiles.gap)
                    adapter.wrapColumns = across
                    list.isNestedScrollingEnabled = false
                    GridLayoutManager(this, maxOf(1, across), GridLayoutManager.VERTICAL, false)
                }
                lines > 1 -> GridLayoutManager(this, lines, GridLayoutManager.HORIZONTAL, false)
                else -> LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            }
            list.adapter = adapter
            list.itemAnimator = null
            Look.styleText(view)
            holder.addView(view)
            sections.add(Section(row, view, list, adapter))
        }
    }

    /** A section's apps were rearranged by moving one. */
    private fun saveOrder(row: Rows.Row, order: List<String>) {
        when (row.kind) {
            Rows.FAVOURITES -> Prefs.setFavourites(this, order)
            Rows.CUSTOM -> Rows.update(this, row.id, restyle = false) {
                it.apps.clear()
                it.apps.addAll(order)
            }
            else -> Prefs.setOrder(this, row.id, order)
        }
    }

    /** A section in the viewer's own order; apps they haven't placed follow in A to Z order. */
    private fun ordered(apps: List<AppEntry>, order: List<String>): List<AppEntry> {
        if (order.isEmpty()) {
            return apps
        }
        val place = HashMap<String, Int>()
        order.forEachIndexed { index, pkg -> place[pkg] = index }
        return apps.sortedBy { place[it.pkg] ?: Int.MAX_VALUE }
    }

    // ------------------------------------------------------------------ the list of apps

    private fun reload() {
        if (loading) {
            loadAgain = true
            return
        }
        loading = true
        thread(name = "TunaHome apps") {
            val found = try {
                Apps.load(applicationContext)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                loading = false
                if (isDestroyed) {
                    return@runOnUiThread
                }
                apps = found
                loaded = true
                show()
                if (loadAgain) {
                    loadAgain = false
                    reload()
                }
            }
        }
    }

    /** Puts the loaded apps into their sections, leaving out hidden ones. */
    private fun show() {
        val hidden = Prefs.hidden(this)
        val visible = apps.filter { it.pkg !in hidden }
        val byPackage = visible.associateBy { it.pkg }
        // Custom rows may have been changed from another screen: read them fresh
        val rows = Rows.load(this).associateBy { it.id }

        for (section in sections) {
            val list = when (section.row.kind) {
                Rows.FAVOURITES -> Prefs.favourites(this).mapNotNull { byPackage[it] }
                Rows.TV -> ordered(visible.filter { it.tv }, Prefs.order(this, section.row.id))
                Rows.MOBILE -> ordered(visible.filter { !it.tv }, Prefs.order(this, section.row.id))
                else -> (rows[section.row.id]?.apps ?: section.row.apps).mapNotNull { byPackage[it] }
            }
            section.show(list)
        }

        val hint = when {
            !loaded -> R.string.loading_apps
            visible.isEmpty() -> R.string.no_apps
            sections.none { it.adapter.appCount > 0 } -> R.string.no_sections
            else -> 0
        }
        appsHint.visibility = if (hint == 0) View.GONE else View.VISIBLE
        if (hint != 0) {
            appsHint.setText(hint)
        }

        if (!placedFocus && sections.any { it.adapter.appCount > 0 }) {
            placedFocus = true
            placeStartFocus()
        }
    }

    /** Where the viewer starts: the first app, or the one they opened last. */
    private fun placeStartFocus() {
        if (Look.START_ON.get(this) == Look.START_LAST) {
            val last = Prefs.lastOpened(this)
            if (last != null) {
                for (section in sections) {
                    val position = section.adapter.positionOf(last)
                    if (position >= 0) {
                        focusIn(section, position)
                        return
                    }
                }
            }
        }
        focusFirst()
    }

    private fun open(app: AppEntry) {
        try {
            startActivity(app.launch)
            Prefs.setLastOpened(this, app.pkg)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.open_failed, app.label), Toast.LENGTH_LONG).show()
            reload()
        }
    }

    // ------------------------------------------------------------------ an app's menu

    private fun showMenu(app: AppEntry, row: Rows.Row) {
        val section = sections.firstOrNull { it.row.id == row.id }
        val favourites = Prefs.favourites(this)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        // First, so that hold select then select picks the app up
        if (section != null && section.adapter.appCount > 1) {
            labels.add(getString(R.string.menu_move))
            actions.add {
                if (section.adapter.startMove(app.pkg)) {
                    Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show()
                }
            }
        }

        if (app.pkg in favourites) {
            labels.add(getString(R.string.menu_remove_favourite))
            actions.add {
                favourites.remove(app.pkg)
                Prefs.setFavourites(this, favourites)
                show()
            }
        } else {
            labels.add(getString(R.string.menu_add_favourite))
            actions.add {
                favourites.add(app.pkg)
                Prefs.setFavourites(this, favourites)
                show()
            }
        }

        labels.add(getString(R.string.menu_add_to_row))
        actions.add { addToRow(app) }
        if (row.custom) {
            labels.add(getString(R.string.menu_remove_from_row, row.name(this)))
            actions.add {
                Rows.update(this, row.id, restyle = false) { it.apps.remove(app.pkg) }
                show()
            }
        }

        labels.add(getString(R.string.menu_change_picture))
        actions.add { pictureMenu(app) }

        labels.add(getString(R.string.menu_hide))
        actions.add {
            val hidden = Prefs.hidden(this)
            hidden.add(app.pkg)
            Prefs.setHidden(this, hidden)
            Prefs.appsChanged = false
            show()
            Toast.makeText(this, getString(R.string.hidden_toast, app.label), Toast.LENGTH_LONG).show()
        }

        labels.add(getString(R.string.menu_app_info))
        actions.add {
            startOrExplain(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
            )
        }

        labels.add(getString(R.string.menu_uninstall))
        actions.add {
            // Android shows its own "Do you want to uninstall this app?" before anything is removed
            startOrExplain(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Adds an app to one of the viewer's own rows, or to a new one. */
    private fun addToRow(app: AppEntry) {
        val own = Rows.load(this).filter { it.custom && app.pkg !in it.apps }
        val names = own.map { it.name(this) } + getString(R.string.row_new)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_add_to_row_title, app.label))
            .setItems(names.toTypedArray()) { _, which ->
                if (which < own.size) {
                    Rows.update(this, own[which].id, restyle = false) { it.apps.add(app.pkg) }
                    show()
                } else {
                    askForText(R.string.row_new, "") { title ->
                        val made = Rows.add(this, title)
                        Rows.update(this, made.id) { it.apps.add(app.pkg) }
                        // A new section: build the screen again
                        recreate()
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ an app's own picture

    /** Where an app's own picture comes from: the TV, a web address, or back to its own. */
    private fun pictureMenu(app: AppEntry) {
        val labels = arrayListOf(getString(R.string.picture_from_tv), getString(R.string.picture_address))
        if (Pictures.hasTile(this, app.pkg)) {
            labels.add(getString(R.string.menu_own_picture))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.picture_title, app.label))
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> choosePicture(app)
                    1 -> askForText(R.string.picture_address, "https://", url = true) { address ->
                        if (!address.startsWith("http://") && !address.startsWith("https://")) {
                            Toast.makeText(this, R.string.bg_address_bad, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, R.string.picture_fetching, Toast.LENGTH_SHORT).show()
                            saveTile(app.pkg, Uri.parse(address), R.string.picture_address_failed)
                        }
                    }
                    else -> {
                        Pictures.removeTile(this, app.pkg)
                        reload()
                    }
                }
            }
            .show()
    }

    private fun saveTile(pkg: String, uri: Uri, failure: Int) {
        thread(name = "TunaHome picture") {
            val saved = try {
                Pictures.saveTile(applicationContext, pkg, uri)
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) {
                    if (saved) reload() else Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun choosePicture(app: AppEntry) {
        choosingPictureFor = app.pkg
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Pictures.pickerIntent(), REQUEST_TILE_PICTURE)
        } catch (e: Exception) {
            choosingPictureFor = null
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
        if (requestCode != REQUEST_TILE_PICTURE) {
            return
        }
        val pkg = choosingPictureFor
        choosingPictureFor = null
        val uri = data?.data
        if (resultCode != RESULT_OK || pkg == null || uri == null) {
            return
        }
        saveTile(pkg, uri, R.string.wallpaper_failed)
    }

    private fun startOrExplain(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.action_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val STATE_PICTURE_FOR = "picture_for"
        private const val REQUEST_TILE_PICTURE = 1

        /** The TV's own settings, which a home screen has to offer a way into. */
        fun openTvSettings(context: Context) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tv_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
