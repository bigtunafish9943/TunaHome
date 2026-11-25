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
    private lateinit var ambient: Ambient
    private val sections = ArrayList<Section>()

    private var apps: List<AppEntry> = emptyList()
    private var watchNext: List<AppEntry> = emptyList()
    private var pcGames: List<AppEntry> = emptyList()
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

    // Shortcuts pressed on the home screen
    private var replaying = false
    private val hotkeys = HotkeyEngine(
        { Hotkeys.all(this).filter { !it.everywhere } },
        { HotkeyActions.run(this, it.action, it.arg, this) },
        { code, taps -> replay(code, taps) },
    )

    /** A press that wasn't a shortcut after all: let it do its ordinary job. */
    private fun replay(code: Int, taps: Int) {
        replaying = true
        try {
            repeat(taps) {
                val now = android.os.SystemClock.uptimeMillis()
                dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
        } finally {
            replaying = false
        }
    }

    /** A shortcut about the home screen itself. */
    fun hotkey(action: String, arg: String?) {
        when (action) {
            HotkeyActions.HOME_TOP -> goToTop()
            HotkeyActions.SIDE_MENU -> {
                refreshMenu()
                sideMenu.open(null)
            }
            HotkeyActions.SECTION -> sections.firstOrNull { it.row.id == arg }?.let { goToSection(it) }
            HotkeyActions.NEXT_BACKGROUND -> backdrop.skip()
            HotkeyActions.IDLE -> if (!idle) {
                idleHandler.removeCallbacks(goIdle)
                enterIdle()
            }
            HotkeyActions.SLEEP_TIMER -> chooseSleep()
        }
    }

    /** A shortcut pressed elsewhere that needs the home screen: do it once it shows. */
    private fun hotkeyFromIntent(intent: Intent?) {
        val action = intent?.getStringExtra(HotkeyActions.EXTRA_ACTION) ?: return
        val arg = intent.getStringExtra(HotkeyActions.EXTRA_ARG)
        intent.removeExtra(HotkeyActions.EXTRA_ACTION)
        scroll.postDelayed({ if (!isDestroyed) hotkey(action, arg) }, 250)
    }
    private lateinit var sideMenu: SideMenu
    private var nowPlaying: NowPlaying? = null

    /** The app whose picture is being chosen, while the TV's picture chooser is open. */
    private var choosingPictureFor: String? = null

    /** Installing or updating an app sends a burst of changes: load once, after the burst. */
    private val reloadSoon = Runnable { reload() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            idleHandler.removeCallbacks(reloadSoon)
            idleHandler.postDelayed(reloadSoon, 1200)
        }
    }

    /** Sections with the same card style share their spare cards, so scrolling builds fewer. */
    private val pools = HashMap<String, RecyclerView.RecycledViewPool>()

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
        ambient = Ambient(findViewById(R.id.followBackdrop))
        setUpDrift()
        sideMenu = SideMenu(this)
        if (sideMenu.showsRail) {
            // Leave room down the left for the closed menu's icons
            scroll.setPadding(Math.round(Look.dp(this, 56f)), 0, 0, 0)
        }

        setUpTopBar()
        buildSections()
        setUpAlignment()
        // Off the apps (on the buttons at the top): the usual background shows again
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (ambient.enabled && now != null && now.parent !is SectionList) {
                ambient.focused(null)
            }
        }
        fadeWhenIdle.clear()
        listOf(R.id.sections, R.id.appsHint, R.id.brand, R.id.networkIcon, R.id.checkUpdate,
            R.id.openTvSettings, R.id.openSettings, R.id.sideMenu, R.id.vpnIcon, R.id.openSearch, R.id.systemInfo).forEach { fadeWhenIdle.add(findViewById(it)) }
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

        // Show the apps already loaded straight away; load again only if the look or the
        // apps may have changed since
        if (Apps.cached.isNotEmpty()) {
            apps = Apps.cached
            loaded = true
        }
        show()
        if (!Apps.cacheFresh(this) || Prefs.appsChanged) {
            Prefs.appsChanged = false
            reload()
        }
        hotkeyFromIntent(intent)
    }

    /** The TV is short of memory: let go of what can be loaded again. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            WatchNext.forgetPictures()
        }
        if (level >= TRIM_MEMORY_MODERATE) {
            Apps.forget()
        }
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
        nowPlaying?.start()
        arrive()
    }

    /** Sections glide up into place, one after another, as the home screen appears. */
    private fun arrive() {
        val mode = Look.ANIMATIONS.get(this)
        if (mode == Look.ANIM_OFF) {
            return
        }
        val rise = Look.dp(this, if (mode == Look.ANIM_FULL) 28f else 12f)
        var delay = 0L
        val views = listOf<View>(findViewById(R.id.topBar)) + sections.map { it.view }.filter { it.visibility == View.VISIBLE }
        for (view in views) {
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                .setDuration(if (mode == Look.ANIM_FULL) 420L else 220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            delay += if (mode == Look.ANIM_FULL) 55L else 20L
        }
    }

    /** Another app is in front: let go of the video player so that app can use the decoder. */
    override fun onStop() {
        backdrop.stop()
        ambient.clear()
        nowPlaying?.stop()
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

    // Free storage and memory, every half minute while the home screen is up
    private val infoTick: Runnable = object : Runnable {
        override fun run() {
            if (showSystemInfo()) {
                weatherHandler.postDelayed(this, 30_000L)
            }
        }
    }

    /** Shows free storage and memory as chosen; false when it's switched off. */
    private fun showSystemInfo(): Boolean {
        val view = findViewById<TextView>(R.id.systemInfo)
        val mode = Look.SYSTEM_INFO.get(this)
        val parts = ArrayList<String>()
        val sleepLeft = SleepTimer.minutesLeft()
        if (sleepLeft > 0) {
            parts.add(getString(R.string.sleep_in, sleepLeft))
        }
        if (mode == Look.INFO_STORAGE || mode == Look.INFO_BOTH) {
            try {
                val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                parts.add(getString(R.string.info_storage_free,
                    android.text.format.Formatter.formatShortFileSize(this, stat.availableBytes)))
            } catch (e: Exception) {
                // Left out
            }
        }
        if (mode == Look.INFO_MEMORY || mode == Look.INFO_BOTH) {
            try {
                val memory = android.app.ActivityManager.MemoryInfo()
                getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
                parts.add(getString(R.string.info_memory_free,
                    android.text.format.Formatter.formatShortFileSize(this, memory.availMem)))
            } catch (e: Exception) {
                // Left out
            }
        }
        view.text = parts.joinToString("   ")
        view.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        return mode != Look.INFO_HIDDEN || sleepLeft > 0
    }

    // Watch Next changes while you watch on another device, or as shows are added: look again
    // every five minutes while the home screen is up
    private val watchNextTick: Runnable = object : Runnable {
        override fun run() {
            reloadWatchNext()
            weatherHandler.postDelayed(this, 5L * 60 * 1000)
        }
    }

    override fun onPause() {
        weatherHandler.removeCallbacks(weatherTick)
        weatherHandler.removeCallbacks(infoTick)
        weatherHandler.removeCallbacks(watchNextTick)
        hotkeys.reset()
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
        if (!replaying && !idle && !sideMenu.isOpen && sections.none { it.adapter.isMoving } &&
            hasWindowFocus() && hotkeys.onKey(event)
        ) {
            scheduleIdle()
            return true
        }
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
        if (event.action == KeyEvent.ACTION_DOWN && Look.CONTROLLER_JUMPS.get(this) && jump(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Controller shoulder buttons (and Page up, Page down, Home and End on a keyboard): a section
     * up or down, or the first or last app of this one.
     */
    private fun jump(code: Int): Boolean {
        val shown = sections.filter { it.view.visibility == View.VISIBLE && it.adapter.appCount > 0 }
        if (shown.isEmpty()) {
            return false
        }
        val focused = currentFocus
        val here = shown.indexOfFirst { it.list === focused?.parent }
        when (code) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP ->
                goToSection(shown[maxOf(0, here - 1)])
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN ->
                goToSection(shown[if (here < 0) 0 else minOf(here + 1, shown.size - 1)])
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_MOVE_END -> {
                val section = shown.getOrNull(here) ?: return false
                val first = code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_MOVE_HOME
                val position = section.adapter.positionOfApp(if (first) 0 else section.adapter.appCount - 1)
                section.list.scrollToPosition(position)
                section.list.post { section.list.layoutManager?.findViewByPosition(position)?.requestFocus() }
            }
            else -> return false
        }
        return true
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
        weatherHandler.removeCallbacks(watchNextTick)
        watchNextTick.run()
        showWeather()
        weatherHandler.removeCallbacks(weatherTick)
        weatherTick.run()
        weatherHandler.removeCallbacks(infoTick)
        infoTick.run()
        // Back on the home screen: the background follows the app you're on again
        if (ambient.enabled) {
            ambient.focused(focusedApp())
        }
        Layouts.autoBackupIfDue(this)
        if (Prefs.appsChanged) {
            Prefs.appsChanged = false
            reload()
        } else if (loaded) {
            // Back from an app: it is now the most recent, and used once more. Only those
            // sections change, so the rest of the screen keeps your place.
            show(sections.filter { it.row.kind == Rows.RECENT || it.row.sort == Rows.SORT_USED }
                .map { it.row.id }.toSet())
        }
        scheduleIdle()
        Updater.resumeIfWaiting(this)
        ChangeLog.showIfUpdated(this)
        if (Prefs.autoUpdateCheck(this)) {
            Updater.check(this, false)
        }
    }

    /** Home pressed while already here: back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        hotkeyFromIntent(intent)
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
            entries.add(SideMenu.Entry(R.drawable.ic_settings, getString(R.string.tunahome_settings), null) {
                openTunaHomeSettings()
            })
            entries.add(SideMenu.Entry(R.drawable.ic_display, getString(R.string.tv_settings), null) {
                openTvSettings(this)
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
    private fun chooseSleep() {
        SleepTimer.choose(this) {
            weatherHandler.removeCallbacks(infoTick)
            infoTick.run()
        }
    }

    /** Puts a saved layout in place and starts the home screen again with it. */
    private fun useLayout(name: String) {
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
    private fun openSetting(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            openTvSettings(this)
        }
    }

    // ------------------------------------------------------------------ where the section you're on sits

    /**
     * As focus moves to another section, that section glides to the chosen height (near the
     * top, or the middle) instead of the screen scrolling only as far as it must. Sections too
     * tall for that scroll the usual way.
     */
    private fun setUpAlignment() {
        if (Look.SECTION_POSITION.get(this) == Look.POSITION_FREE) {
            return
        }
        (scroll as? HomeScroll)?.aligned = { rect ->
            val y = rect.centerY()
            sections.any { section ->
                section.view.visibility == View.VISIBLE && alignTarget(section) != null &&
                    sectionTop(section).let { top -> y >= top && y < top + section.view.height }
            }
        }
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val section = now?.let { view -> sections.firstOrNull { it.list === view.parent } }
            val target = section?.let { alignTarget(it) }
            if (target != null && target != scroll.scrollY) {
                scroll.smoothScrollTo(0, target)
            }
        }
    }

    /** The app the viewer is on, if they're on one. */
    private fun focusedApp(): AppEntry? {
        val view = currentFocus ?: return null
        val section = sections.firstOrNull { it.list === view.parent } ?: return null
        return section.adapter.appAtPosition(section.list.getChildAdapterPosition(view))
    }

    /** The background moves up a little as you scroll down, slower than the sections. */
    private fun setUpDrift() {
        if (!Backdrop.DRIFT.get(this)) {
            return
        }
        val layers = listOf<View>(findViewById(R.id.backdropImage), findViewById(R.id.backdropVideo), findViewById(R.id.followBackdrop))
        // A little bigger than the screen, so there is picture to move into
        layers.forEach {
            it.scaleX = 1.1f
            it.scaleY = 1.1f
        }
        val room = resources.displayMetrics.heightPixels * 0.045f
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val shift = -minOf(y * 0.12f, room)
            layers.forEach { it.translationY = shift }
        }
    }

    /** Where a section starts, measured down the whole scrolling page. */
    private fun sectionTop(section: Section): Int {
        val rect = android.graphics.Rect(0, 0, section.view.width, section.view.height)
        scroll.offsetDescendantRectToMyCoords(section.view, rect)
        return rect.top
    }

    /** How far down to scroll so [section] sits where the viewer chose; null to scroll as usual. */
    private fun alignTarget(section: Section): Int? {
        val mode = Look.SECTION_POSITION.get(this)
        val screen = scroll.height
        if (mode == Look.POSITION_FREE || screen == 0 || section.view.height > screen * 0.8f) {
            return null
        }
        val top = sectionTop(section)
        val first = sections.firstOrNull { it.view.visibility == View.VISIBLE } === section
        val target = when {
            // The first section keeps the top of the screen in view
            first -> 0
            mode == Look.POSITION_MIDDLE -> top + section.view.height / 2 - screen / 2
            else -> top - Math.round(Look.dp(this, 28f))
        }
        val most = maxOf(0, (scroll.getChildAt(0)?.height ?: 0) - screen)
        return target.coerceIn(0, most)
    }

    private fun goToSection(section: Section) {
        scroll.post {
            scroll.smoothScrollTo(0, alignTarget(section) ?: maxOf(0, section.view.top - Math.round(Look.dp(this, 24f))))
            focusIn(section, 0)
        }
    }

    fun openSearch(voice: Boolean) {
        startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_VOICE, voice))
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

        val search = findViewById<View>(R.id.openSearch)
        search.visibility = if (Look.SHOW_SEARCH_BUTTON.get(this)) View.VISIBLE else View.GONE
        search.setOnClickListener { openSearch(false) }

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
            if (row.kind == Rows.NOW_PLAYING) {
                // Not a row of apps: the Now playing card, when it's switched on
                if (NowPlaying.SHOW.get(this)) {
                    val card = layoutInflater.inflate(R.layout.item_now_playing, holder, false)
                    (card.layoutParams as LinearLayout.LayoutParams).topMargin = rowGap
                    Look.styleText(card)
                    holder.addView(card)
                    nowPlaying = NowPlaying(this, card)
                }
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

            val list = view.findViewById<SectionList>(R.id.sectionApps)
            list.remember = Look.REMEMBER_PLACE.get(this)
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
            (list.layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = 6
            list.setItemViewCacheSize(6)
            adapter.spotlight = row.spotlight && lines == 1
            // Cards beside a spotlight card sit lower, so they keep to their own spares
            val poolKey = "${System.identityHashCode(sectionTiles)}/${lines == Rows.WRAP}/${adapter.spotlight}"
            list.setRecycledViewPool(pools.getOrPut(poolKey) {
                RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 24) }
            })
            adapter.canMove = !row.ownOrder
            adapter.onFocused = { app -> ambient.focused(app) }
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
        if (row.sort != Rows.SORT_OWN) {
            // Moving an app by hand puts the section back in the viewer's own order
            row.sort = Rows.SORT_OWN
            Rows.update(this, row.id, restyle = false) { it.sort = Rows.SORT_OWN }
            Toast.makeText(this, R.string.sort_now_own, Toast.LENGTH_LONG).show()
        }
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

    /** A section sorted the way the viewer chose for it. */
    private fun sorted(list: List<AppEntry>, sort: Int): List<AppEntry> = when (sort) {
        Rows.SORT_AZ -> list.sortedBy { it.label.lowercase(Locale.getDefault()) }
        Rows.SORT_USED -> {
            val uses = Prefs.uses(this)
            list.sortedWith(compareByDescending<AppEntry> { uses[it.pkg] ?: 0 }
                .thenBy { it.label.lowercase(Locale.getDefault()) })
        }
        Rows.SORT_NEWEST -> list.sortedByDescending { it.installed }
        Rows.SORT_UPDATED -> list.sortedByDescending { it.updated }
        else -> list
    }

    // ------------------------------------------------------------------ Watch Next

    private fun reloadWatchNext() {
        val wantShows = sections.any { it.row.kind == Rows.WATCH_NEXT }
        val wantGames = sections.any { it.row.kind == Rows.PC_GAMES }
        if (loadingWatchNext || (!wantShows && !wantGames)) {
            return
        }
        loadingWatchNext = true
        thread(name = "TunaHome Watch Next") {
            val found = if (!wantShows) emptyList() else try {
                WatchNext.load(applicationContext)
            } catch (e: Throwable) {
                emptyList()
            }
            val games = if (!wantGames) emptyList() else try {
                PcGames.load(applicationContext)
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                loadingWatchNext = false
                if (isDestroyed) {
                    return@runOnUiThread
                }
                if (games.map { it.pkg } != pcGames.map { it.pkg }) {
                    pcGames = games
                    sections.filter { it.row.kind == Rows.PC_GAMES }.forEach { it.show(games) }
                    applyGaps()
                }
                // Nothing new: leave the section alone, so it doesn't jump under the viewer
                val same = found.size == watchNext.size && found.indices.all {
                    found[it].pkg == watchNext[it].pkg && found[it].progress == watchNext[it].progress
                }
                if (same && found.isNotEmpty()) {
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
                Apps.load(applicationContext, themeColor(R.attr.tsAccent))
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
    private fun show(only: Set<String>? = null) {
        val hidden = Prefs.hidden(this)
        val visible = apps.filter { it.pkg !in hidden }
        val everyApp = visible.associateBy { it.pkg }
        // Apps in a folder show in the folder instead of their section
        val folders = Folders.load(this)
        val inFolder = folders.flatMap { it.apps }.toSet()
        val folderCards = folders.map { Folders.entry(this, it, everyApp) }
        val byPackage = HashMap<String, AppEntry>()
        visible.filter { it.pkg !in inFolder }.forEach { byPackage[it.pkg] = it }
        folderCards.forEach { byPackage[it.pkg] = it }
        // Links only go where the viewer puts them, not in the sections that gather apps by kind
        val loose = visible.filter { it.pkg !in inFolder && !it.isLink }
        // Custom rows may have been changed from another screen: read them fresh
        val rows = Rows.load(this).associateBy { it.id }
        // A folder sits in the section that names it; TV apps holds the rest
        fun foldersFor(row: Rows.Row): List<AppEntry> = folderCards.filter { card ->
            val key = card.pkg
            val named = Prefs.favourites(this).contains(key) || rows.values.any { it.custom && key in it.apps } ||
                Prefs.order(this, Rows.MOBILE).contains(key)
            when (row.kind) {
                Rows.TV -> !named || Prefs.order(this, row.id).contains(key)
                Rows.MOBILE -> Prefs.order(this, row.id).contains(key)
                else -> false
            }
        }

        for (section in sections) {
            if (only != null && section.row.id !in only) {
                continue
            }
            val list = when (section.row.kind) {
                Rows.FAVOURITES -> Prefs.favourites(this).mapNotNull { byPackage[it] }
                Rows.TV -> ordered(loose.filter { it.tv } + foldersFor(section.row), Prefs.order(this, section.row.id))
                Rows.MOBILE -> ordered(loose.filter { !it.tv } + foldersFor(section.row), Prefs.order(this, section.row.id))
                Rows.SIDELOADED -> ordered(loose.filter { it.sideloaded }, Prefs.order(this, section.row.id))
                Rows.GAMES -> ordered(loose.filter { it.group == Apps.GAME }, Prefs.order(this, section.row.id))
                Rows.VIDEO -> ordered(loose.filter { it.group == Apps.VIDEO }, Prefs.order(this, section.row.id))
                Rows.MUSIC -> ordered(loose.filter { it.group == Apps.MUSIC }, Prefs.order(this, section.row.id))
                Rows.RECENT -> Prefs.recent(this).mapNotNull { everyApp[it] }
                Rows.WATCH_NEXT -> watchNext
                Rows.PC_GAMES -> pcGames
                else -> (rows[section.row.id]?.apps ?: section.row.apps).mapNotNull { byPackage[it] }
            }
            section.show(if (section.row.ownOrder) list else sorted(list, section.row.sort))
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
        if (app.isFolder) {
            openFolder(app.pkg.removePrefix(Folders.PREFIX))
            return
        }
        Launch.open(this, app) { reload() }
    }

    // ------------------------------------------------------------------ folders

    /** A folder's apps in a small grid over the home screen. */
    private fun openFolder(id: String) {
        val folder = Folders.load(this).firstOrNull { it.id == id } ?: return
        val byPackage = apps.associateBy { it.pkg }
        val inside = folder.apps.mapNotNull { byPackage[it] }
        val list = RecyclerView(this)
        val across = minOf(5, maxOf(2, inside.size))
        lateinit var dialog: AlertDialog
        val adapter = AppAdapter(tiles, Rows.WRAP, { app ->
            dialog.dismiss()
            Launch.open(this, app) { reload() }
        }, { app ->
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(arrayOf(getString(R.string.folder_take_out), getString(R.string.menu_app_info))) { _, which ->
                    if (which == 0) {
                        Folders.update(this, id) { it.apps.remove(app.pkg) }
                        dialog.dismiss()
                        show()
                    } else {
                        startOrExplain(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))
                    }
                }
                .show()
        }, { order -> Folders.update(this, id) { it.apps.clear(); it.apps.addAll(order) }; show() })
        adapter.wrapColumns = across
        list.layoutManager = GridLayoutManager(this, across)
        list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false
        val pad = Math.round(Look.dp(this, 16f))
        list.setPadding(pad, pad, pad, pad)
        adapter.submit(inside)
        dialog = AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setView(list)
            .setNegativeButton(R.string.close, null)
            .show()
        list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    /** Puts an app into a folder, or a new one in the same place in its section. */
    private fun putInFolder(app: AppEntry, row: Rows.Row) {
        val folders = Folders.load(this)
        val names = folders.map { it.name } + getString(R.string.folder_new)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_put_title, app.label))
            .setItems(names.toTypedArray()) { _, which ->
                if (which < folders.size) {
                    val list = Folders.load(this)
                    list.forEach { it.apps.remove(app.pkg) }
                    list.firstOrNull { it.id == folders[which].id }?.apps?.add(app.pkg)
                    Folders.save(this, list.filter { it.apps.isNotEmpty() })
                    show()
                } else {
                    askForText(R.string.folder_new, "") { name ->
                        val folder = Folders.create(this, name, app.pkg)
                        // The folder takes the app's place in its section
                        val section = sections.firstOrNull { it.row.id == row.id }
                        val order = section?.adapter?.order()?.map { if (it == app.pkg) Folders.key(folder.id) else it }
                        if (order != null) saveOrder(row, order)
                        show()
                    }
                }
            }
            .show()
    }

    private fun folderMenu(card: AppEntry, row: Rows.Row) {
        val id = card.pkg.removePrefix(Folders.PREFIX)
        val section = sections.firstOrNull { it.row.id == row.id }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (section != null && section.adapter.appCount > 1) {
            labels.add(getString(R.string.menu_move))
            actions.add { section.adapter.startMove(card.pkg) }
        }
        labels.add(getString(R.string.folder_open))
        actions.add { openFolder(id) }
        labels.add(getString(R.string.section_rename))
        actions.add {
            askForText(R.string.section_rename, card.label) { name ->
                Folders.update(this, id) { it.name = name }
                show()
            }
        }
        labels.add(getString(R.string.folder_delete))
        actions.add {
            val list = Folders.load(this).filter { it.id != id }
            Folders.save(this, list)
            show()
        }
        AlertDialog.Builder(this)
            .setTitle(card.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ------------------------------------------------------------------ an app's menu

    private fun showMenu(app: AppEntry, row: Rows.Row) {
        if (app.isInsideApp) {
            watchNextMenu(app)
            return
        }
        if (app.isFolder) {
            folderMenu(app, row)
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
        if (row.kind != Rows.RECENT && row.kind != Rows.WATCH_NEXT) {
            labels.add(getString(R.string.folder_put))
            actions.add { putInFolder(app, row) }
        }
        if (row.custom) {
            labels.add(getString(R.string.menu_remove_from_row, row.name(this)))
            actions.add {
                Rows.update(this, row.id, restyle = false) { it.apps.remove(app.pkg) }
                show()
            }
        }

        labels.add(getString(R.string.menu_change_picture))
        actions.add { pictureMenu(app) }

        labels.add(getString(R.string.menu_rename))
        actions.add {
            askForText(R.string.menu_rename, app.label) { name ->
                Prefs.setAppName(this, app.pkg, name)
                Prefs.appsChanged = false
                reload()
            }
        }
        if (Prefs.appNames(this).containsKey(app.pkg)) {
            labels.add(getString(R.string.menu_own_name))
            actions.add {
                Prefs.setAppName(this, app.pkg, null)
                Prefs.appsChanged = false
                reload()
            }
        }

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

        labels.add(getString(R.string.menu_edit_section, row.name(this)))
        actions.add {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.SECTIONS)
                .putExtra(SettingsActivity.EXTRA_SECTION, row.id))
        }

        if (row.kind == Rows.FAVOURITES || row.custom) {
            labels.add(getString(R.string.link_add_here))
            actions.add { addLink { key -> putInSection(key, row) } }
        }

        if (app.isLink) {
            labels.add(getString(R.string.link_change))
            actions.add {
                askForText(R.string.link_address, Links.address(this, app.pkg) ?: "", url = true) { address ->
                    if (address.isNotBlank()) {
                        Links.change(this, app.pkg, address)
                        Prefs.appsChanged = false
                        reload()
                    }
                }
            }
            labels.add(getString(R.string.link_delete))
            actions.add {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.link_delete_title, app.label))
                    .setPositiveButton(R.string.link_delete) { _, _ ->
                        Links.remove(this, app.pkg)
                        Prefs.appsChanged = false
                        reload()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .show()
            return
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

    /** Puts a new card at the end of Favourites or a section of the viewer's own. */
    private fun putInSection(key: String, row: Rows.Row) {
        if (row.kind == Rows.FAVOURITES) {
            val favourites = Prefs.favourites(this)
            favourites.add(key)
            Prefs.setFavourites(this, favourites)
        } else {
            Rows.update(this, row.id, restyle = false) { it.apps.add(key) }
        }
        Prefs.appsChanged = false
        reload()
    }

    /** A Watch Next card belongs to its app: offer to open it, or that app's info. */
    private fun watchNextMenu(item: AppEntry) {
        val appName = apps.firstOrNull { it.pkg == item.appPkg }?.label ?: item.appPkg
        val labels = arrayOf(getString(if (item.isPcGame) R.string.pc_game_play else R.string.watch_next_play),
            getString(R.string.watch_next_open_app, appName),
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
        // With an icon pack on, any of its drawings can be chosen for this app
        val pack = IconPacks.chosen(this)
        if (pack != null && Look.TILE_STYLE.get(this) != Look.TILE_BANNER) {
            labels.add(0, getString(R.string.pick_from_pack))
        }
        val offset = if (labels[0] == getString(R.string.pick_from_pack)) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.picture_title, app.label))
            .setItems(labels.toTypedArray()) { _, chosen ->
                if (offset == 1 && chosen == 0) {
                    PackPicker.show(this, app) { reload() }
                    return@setItems
                }
                when (chosen - offset) {
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
