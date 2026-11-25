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
    private var watchNext: List<AppEntry> = emptyList()
    private var loadingWatchNext = false
    private var loaded = false
    private var loading = false
    private var loadAgain = false
    private var placedFocus = false

    // Idle: after a while with no button pressed, everything but the background fades away
    private val idleHandler = Handler(Looper.getMainLooper())
    private val goIdle = Runnable { enterIdle() }
    private var idle = false
    private var swallowKeyUp = false
    private var shadeAlpha = 0f
    private val fadeWhenIdle = ArrayList<View>()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var sideMenu: SideMenu

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
        sideMenu = SideMenu(this)
        if (sideMenu.showsRail) {
            // Leave room down the left for the closed menu's icons
            scroll.setPadding(Math.round(Look.dp(this, 56f)), 0, 0, 0)
        }

        setUpTopBar()
        buildSections()
        fadeWhenIdle.clear()
        listOf(R.id.sections, R.id.appsHint, R.id.brand, R.id.networkIcon, R.id.checkUpdate,
            R.id.openTvSettings, R.id.openSettings, R.id.sideMenu, R.id.vpnIcon).forEach { fadeWhenIdle.add(findViewById(it)) }
        if (!Look.IDLE_CLOCK.get(this)) {
            fadeWhenIdle.add(findViewById(R.id.clockBox))
            fadeWhenIdle.add(findViewById(R.id.weatherBox))
        }

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
        watchNetwork()
    }

    /** Another app is in front: let go of the video player so that app can use the decoder. */
    override fun onStop() {
        backdrop.stop()
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Already gone
            }
        }
        networkCallback = null
        super.onStop()
    }

    // The weather is checked when the home screen shows and every half hour while it stays up
    private val weatherHandler = Handler(Looper.getMainLooper())
    private val weatherTick: Runnable = object : Runnable {
        override fun run() {
            Weather.refresh(this@HomeActivity) { showWeather() }
            weatherHandler.postDelayed(this, 30L * 60 * 1000)
        }
    }

    /** Puts the weather beside or under the clock, in the chosen detail, picture and size. */
    private fun showWeather() {
        val box = findViewById<LinearLayout>(R.id.weatherBox)
        val now = Weather.current
        if (!Weather.SHOW.get(this) || Weather.place(this) == null || now == null) {
            box.visibility = View.GONE
            return
        }
        val icon = findViewById<ImageView>(R.id.weatherIcon)
        val temp = findViewById<TextView>(R.id.weatherTemp)
        val detail = findViewById<TextView>(R.id.weatherDetail)
        temp.text = getString(R.string.wx_degrees, now.temperature)
        val condition = getString(Weather.condition(now.code))
        val parts = ArrayList<String>()
        when (Weather.DETAIL.get(this)) {
            Weather.DETAIL_CONDITION -> parts.add(condition)
            Weather.DETAIL_HIGH_LOW -> {
                parts.add(condition)
                parts.add(getString(R.string.wx_high_low, now.high, now.low))
            }
            Weather.DETAIL_ALL -> {
                parts.add(condition)
                parts.add(getString(R.string.wx_high_low, now.high, now.low))
                parts.add(getString(R.string.wx_feels, now.feelsLike))
                if (now.humidity >= 0) parts.add(getString(R.string.wx_humidity, now.humidity))
                if (now.wind >= 0) parts.add(getString(
                    if (Weather.UNITS.get(this) == Weather.UNITS_F) R.string.wx_wind_mph else R.string.wx_wind_kmh, now.wind))
            }
        }
        detail.text = parts.joinToString(" · ")
        detail.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        val style = Weather.ICONS.get(this)
        icon.visibility = if (style == Weather.ICONS_NONE) View.GONE else View.VISIBLE
        icon.setImageResource(Weather.icon(now.code, now.day))
        when (style) {
            Weather.ICONS_WHITE -> icon.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            Weather.ICONS_ACCENT -> icon.setColorFilter(themeColor(R.attr.tsAccent), android.graphics.PorterDuff.Mode.SRC_IN)
            else -> icon.clearColorFilter()
        }

        if (!weatherSized) {
            weatherSized = true
            val scale = Weather.scale(this)
            icon.layoutParams = icon.layoutParams.apply {
                width = Math.round(width * scale)
                height = Math.round(height * scale)
            }
            temp.setTextSize(TypedValue.COMPLEX_UNIT_PX, temp.textSize * scale)
            detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, detail.textSize * maxOf(scale, 0.9f))
            if (Weather.PLACE.get(this) == Weather.PLACE_UNDER) {
                // Under the clock instead of beside it
                (box.parent as ViewGroup).removeView(box)
                findViewById<LinearLayout>(R.id.clockBox).addView(box)
                (box.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = 0
                    marginEnd = 0
                    topMargin = Math.round(Look.dp(this@HomeActivity, 4f))
                }
            }
        }
        if (box.visibility != View.VISIBLE) {
            box.alpha = 0f
            box.visibility = View.VISIBLE
            box.animate().alpha(1f).setDuration(400).start()
        }
    }

    private var weatherSized = false

    override fun onPause() {
        weatherHandler.removeCallbacks(weatherTick)
        // Leaving mid-move: keep the app where it was put
        sections.forEach { it.adapter.finishMove() }
        sideMenu.close(false)
        idleHandler.removeCallbacks(goIdle)
        if (idle) {
            leaveIdle(false)
        }
        super.onPause()
    }

    // ------------------------------------------------------------------ idle

    private fun scheduleIdle() {
        idleHandler.removeCallbacks(goIdle)
        val after = Look.idleMs(this)
        if (after > 0) {
            idleHandler.postDelayed(goIdle, after)
        }
    }

    private fun enterIdle() {
        // Not while a menu or another window is in front
        if (!hasWindowFocus() || idle) {
            scheduleIdle()
            return
        }
        idle = true
        fadeWhenIdle.forEach { it.animate().alpha(0f).setDuration(1200).start() }
        val shade = findViewById<View>(R.id.backdropShade)
        shadeAlpha = shade.alpha
        shade.animate().alpha(0f).setDuration(1200).start()
    }

    private fun leaveIdle(animate: Boolean) {
        idle = false
        val time = if (animate) 300L else 0L
        fadeWhenIdle.forEach { it.animate().alpha(1f).setDuration(time).start() }
        findViewById<View>(R.id.backdropShade).animate().alpha(shadeAlpha).setDuration(time).start()
    }

    /**
     * Any button wakes the home screen; the press that wakes it does nothing else. While an app
     * is picked up, the D-pad, select and Back move it and put it down.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (sideMenu.isOpen) {
            scheduleIdle()
            if (sideMenu.key(event)) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Left on the first app of a line opens the side menu
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
            sideMenu.enabled && !idle && sections.none { it.adapter.isMoving }
        ) {
            val focused = currentFocus
            val list = focused?.parent as? RecyclerView
            val section = sections.firstOrNull { it.list === list }
            if (focused != null && list != null && section != null &&
                section.adapter.isLineStart(list.getChildAdapterPosition(focused))
            ) {
                refreshMenu()
                sideMenu.open(section.row.id)
                return true
            }
        }
        sections.firstOrNull { it.adapter.isMoving }?.let { section ->
            scheduleIdle()
            if (section.adapter.moveKey(event)) {
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (idle) {
                leaveIdle(true)
                swallowKeyUp = true
                scheduleIdle()
                return true
            }
            scheduleIdle()
        } else if (event.action == KeyEvent.ACTION_UP && swallowKeyUp) {
            swallowKeyUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ network icon

    private fun watchNetwork() {
        val icon = findViewById<ImageView>(R.id.networkIcon)
        val vpn = findViewById<View>(R.id.vpnIcon)
        val showNetwork = Look.SHOW_NETWORK.get(this)
        val showVpn = Look.SHOW_VPN.get(this)
        if (!showNetwork && !showVpn) {
            icon.visibility = View.GONE
            vpn.visibility = View.GONE
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        fun update() {
            val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            vpn.visibility = if (showVpn && caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) View.VISIBLE else View.GONE
            if (!showNetwork) {
                icon.visibility = View.GONE
                return
            }
            val picture = when {
                caps == null -> R.drawable.ic_net_none
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_net_wired
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_net_wifi
                else -> R.drawable.ic_net_wired
            }
            icon.setImageResource(picture)
            icon.visibility = View.VISIBLE
        }
        update()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { update() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { update() }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runOnUiThread { update() }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            // The icon just won't change until the home screen is shown again
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from watching something: its place in Watch Next has changed
        reloadWatchNext()
        showWeather()
        weatherHandler.removeCallbacks(weatherTick)
        weatherTick.run()
        if (Prefs.appsChanged) {
            Prefs.appsChanged = false
            reload()
        } else if (loaded) {
            // Back from an app: it is now the most recent. Only that section changes, so the
            // rest of the screen keeps your place.
            val hidden = Prefs.hidden(this)
            val byPackage = apps.filter { it.pkg !in hidden }.associateBy { it.pkg }
            sections.filter { it.row.kind == Rows.RECENT }.forEach { section ->
                section.show(Prefs.recent(this).mapNotNull { byPackage[it] })
            }
        }
        scheduleIdle()
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

    // ------------------------------------------------------------------ side menu

    /** The side menu's entries: every section showing, then the shortcuts. */
    private fun refreshMenu() {
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
                Rows.WATCH_NEXT -> R.drawable.ic_play
                else -> R.drawable.ic_folder
            }
            entries.add(SideMenu.Entry(icon, section.row.name(this), section.row.id) { goToSection(section) })
        }
        if (Look.SIDE_MENU_SHORTCUTS.get(this)) {
            entries.add(SideMenu.Entry(0, "", null) {})
            entries.add(SideMenu.Entry(R.drawable.ic_settings, getString(R.string.tunahome_settings), null) {
                openTunaHomeSettings()
            })
            entries.add(SideMenu.Entry(R.drawable.ic_display, getString(R.string.tv_settings), null) {
                openTvSettings(this)
            })
            entries.add(SideMenu.Entry(R.drawable.ic_net_wifi, getString(R.string.menu_network), null) {
                openSetting(Settings.ACTION_WIFI_SETTINGS)
            })
            entries.add(SideMenu.Entry(R.drawable.ic_apps, getString(R.string.menu_installed_apps), null) {
                openSetting(Settings.ACTION_APPLICATION_SETTINGS)
            })
            entries.add(SideMenu.Entry(R.drawable.ic_info, getString(R.string.menu_about_tv), null) {
                openSetting(Settings.ACTION_DEVICE_INFO_SETTINGS)
            })
        }
        sideMenu.setEntries(entries)
    }

    /** A page of the TV's own settings, or the TV's settings if this TV doesn't have that page. */
    private fun openSetting(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            openTvSettings(this)
        }
    }

    private fun goToSection(section: Section) {
        scroll.post {
            scroll.smoothScrollTo(0, maxOf(0, section.view.top - Math.round(Look.dp(this, 24f))))
            focusIn(section, 0)
        }
    }

    private fun openTunaHomeSettings() {
        if (AppLock.settingsLocked(this)) {
            AppLock.ask(this, getString(R.string.tunahome_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        } else {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
        setUpBrand()

        val update = findViewById<View>(R.id.checkUpdate)
        update.visibility = if (Look.SHOW_UPDATE_BUTTON.get(this)) View.VISIBLE else View.GONE
        update.setOnClickListener { Updater.check(this, true) }
        val tvSettings = findViewById<View>(R.id.openTvSettings)
        tvSettings.visibility = if (Look.SHOW_TV_SETTINGS_BUTTON.get(this)) View.VISIBLE else View.GONE
        tvSettings.setOnClickListener { openTvSettings(this) }
        // Always shown: it is the way back to every other setting
        findViewById<View>(R.id.openSettings).setOnClickListener { openTunaHomeSettings() }

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

    /** The fish and the name: shown or not, which parts, and how big. */
    private fun setUpBrand() {
        findViewById<View>(R.id.brand).visibility = if (Look.SHOW_BRAND.get(this)) View.VISIBLE else View.GONE
        val parts = Look.BRAND_PARTS.get(this)
        val logo = findViewById<ImageView>(R.id.brandLogo)
        val text = findViewById<View>(R.id.brandText)
        logo.visibility = if (parts == Look.BRAND_NAME) View.GONE else View.VISIBLE
        text.visibility = if (parts == Look.BRAND_LOGO) View.GONE else View.VISIBLE
        if (parts == Look.BRAND_NAME) {
            (text.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }
        val version = findViewById<TextView>(R.id.brandVersion)
        version.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        version.visibility = if (Look.SHOW_VERSION.get(this)) View.VISIBLE else View.GONE

        val scale = Look.brandScale(this)
        if (scale != 1f) {
            logo.layoutParams = logo.layoutParams.apply { height = Math.round(height * scale) }
            (text.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = Math.round(marginStart * scale)
            }
            for (id in intArrayOf(R.id.brandTuna, R.id.brandWord)) {
                val word = findViewById<TextView>(id)
                word.setTextSize(TypedValue.COMPLEX_UNIT_PX, word.textSize * scale)
            }
            // The version stays readable even when the rest is small
            version.setTextSize(TypedValue.COMPLEX_UNIT_PX, version.textSize * maxOf(scale, 0.8f))
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
            (title.layoutParams as LinearLayout.LayoutParams).topMargin = 0

            val list = view.findViewById<RecyclerView>(R.id.sectionApps)
            val lines = row.lines
            val sectionTiles = if (row.cardSize >= 0) TileStyle(this, row.cardSize) else tiles
            val adapter = AppAdapter(
                sectionTiles, lines, ::open, { app -> showMenu(app, row) }, { order -> saveOrder(row, order) }
            )
            list.layoutManager = when {
                lines == Rows.WRAP -> {
                    val sidePadding = list.paddingStart + list.paddingEnd
                    val across = (resources.displayMetrics.widthPixels - sidePadding) /
                        (sectionTiles.width + 2 * sectionTiles.gap)
                    adapter.wrapColumns = across
                    list.isNestedScrollingEnabled = false
                    GridLayoutManager(this, maxOf(1, across), GridLayoutManager.VERTICAL, false)
                }
                lines > 1 -> GridLayoutManager(this, lines, GridLayoutManager.HORIZONTAL, false)
                else -> LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            }
            adapter.canMove = row.kind != Rows.RECENT && row.kind != Rows.WATCH_NEXT
            adapter.onMoveStart = {
                Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show()
            }
            list.adapter = adapter
            // No slide animations: with apps swapping places quickly they could leave the list
            // unsure which tile was which, and Android stopped TunaHome
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

    // ------------------------------------------------------------------ Watch Next

    private fun reloadWatchNext() {
        if (loadingWatchNext || sections.none { it.row.kind == Rows.WATCH_NEXT }) {
            return
        }
        loadingWatchNext = true
        thread(name = "TunaHome Watch Next") {
            val found = try {
                WatchNext.load(applicationContext)
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                loadingWatchNext = false
                if (isDestroyed) {
                    return@runOnUiThread
                }
                watchNext = found
                sections.filter { it.row.kind == Rows.WATCH_NEXT }.forEach { it.show(found) }
                applyGaps()
                if (!sideMenu.isOpen) {
                    refreshMenu()
                }
            }
        }
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
                Rows.RECENT -> Prefs.recent(this).mapNotNull { byPackage[it] }
                Rows.WATCH_NEXT -> watchNext
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

        applyGaps()
        if (!sideMenu.isOpen) {
            refreshMenu()
        }

        if (!placedFocus && sections.any { it.adapter.appCount > 0 }) {
            placedFocus = true
            placeStartFocus()
        }
    }

    /** Space above each section: the header gap above the first one showing, the section gap above the rest. */
    private fun applyGaps() {
        val headerGap = Math.round(Look.dp(this, Look.headerGapDp(this)))
        val rowGap = Math.round(Look.dp(this, Look.rowGapDp(this)))
        var first = true
        for (section in sections) {
            if (section.view.visibility != View.VISIBLE) {
                continue
            }
            val params = section.view.layoutParams as LinearLayout.LayoutParams
            val want = if (first) headerGap else rowGap
            if (params.topMargin != want) {
                params.topMargin = want
                section.view.layoutParams = params
            }
            first = false
        }
        (appsHint.layoutParams as LinearLayout.LayoutParams).topMargin = headerGap
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
        if (AppLock.isLocked(this, app.appPkg)) {
            AppLock.ask(this, app.label) { launch(app) }
        } else {
            launch(app)
        }
    }

    private fun launch(app: AppEntry) {
        try {
            startActivity(app.launch)
            if (!app.isWatchNext) {
                Prefs.setLastOpened(this, app.pkg)
                Prefs.addRecent(this, app.pkg)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.open_failed, app.label), Toast.LENGTH_LONG).show()
            reload()
        }
    }

    // ------------------------------------------------------------------ an app's menu

    private fun showMenu(app: AppEntry, row: Rows.Row) {
        if (app.isWatchNext) {
            watchNextMenu(app)
            return
        }
        val section = sections.firstOrNull { it.row.id == row.id }
        val favourites = Prefs.favourites(this)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        // First, so that hold select then select picks the app up. Recently opened keeps its
        // own order, newest first.
        if (section != null && section.adapter.appCount > 1 && row.kind != Rows.RECENT) {
            labels.add(getString(R.string.menu_move))
            actions.add { section.adapter.startMove(app.pkg) }
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

        if (AppLock.isLocked(this, app.pkg)) {
            labels.add(getString(R.string.menu_unlock))
            actions.add {
                AppLock.ask(this, app.label) {
                    AppLock.setLocked(this, app.pkg, false)
                    show()
                }
            }
        } else {
            labels.add(getString(R.string.menu_lock))
            actions.add {
                val lock = {
                    AppLock.setLocked(this, app.pkg, true)
                    show()
                    Toast.makeText(this, getString(R.string.lock_locked, app.label), Toast.LENGTH_LONG).show()
                }
                if (AppLock.hasPin(this)) lock() else AppLock.choosePin(this, lock)
            }
        }

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

    /** A Watch Next card belongs to its app: offer to open it, or that app's info. */
    private fun watchNextMenu(item: AppEntry) {
        val appName = apps.firstOrNull { it.pkg == item.appPkg }?.label ?: item.appPkg
        val labels = arrayOf(getString(R.string.watch_next_play), getString(R.string.watch_next_open_app, appName),
            getString(R.string.menu_app_info))
        AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> open(item)
                    1 -> apps.firstOrNull { it.pkg == item.appPkg }?.let { open(it) }
                        ?: Toast.makeText(this, getString(R.string.open_failed, appName), Toast.LENGTH_LONG).show()
                    else -> startOrExplain(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + item.appPkg))
                    )
                }
            }
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
