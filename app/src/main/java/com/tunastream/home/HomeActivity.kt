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
    internal class Section(val row: Rows.Row, val view: View, val list: RecyclerView, val adapter: AppAdapter) {
        fun show(apps: List<AppEntry>) {
            adapter.submit(apps)
            view.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    internal lateinit var scroll: ScrollView
    internal lateinit var appsHint: TextView
    internal lateinit var backdrop: Backdrop
    internal lateinit var tiles: TileStyle
    internal lateinit var ambient: Ambient
    internal lateinit var previews: Previews
    internal val sections = ArrayList<Section>()

    internal var apps: List<AppEntry> = emptyList()
    internal var watchNext: List<AppEntry> = emptyList()
    internal var pcGames: List<AppEntry> = emptyList()
    /** Watch Next without the apps left out of this section (its list of apps, for this kind). */
    internal fun withoutLeftOut(list: List<AppEntry>, row: Rows.Row): List<AppEntry> =
        if (row.apps.isEmpty()) list else list.filter { it.appPkg !in row.apps }

    internal var loadingWatchNext = false
    internal var watchNextAgain = false
    internal var loaded = false
    internal var loading = false
    internal var loadAgain = false
    internal var placedFocus = false

    // Idle: after a while with no button pressed, everything but the background fades away
    internal val idleHandler = Handler(Looper.getMainLooper())
    internal val goIdle = Runnable { enterIdle() }
    internal var idle = false
    internal var swallowKeyUp = false
    internal var shadeAlpha = 0f
    internal val fadeWhenIdle = ArrayList<View>()

    internal var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Shortcuts pressed on the home screen
    internal var replaying = false
    internal val hotkeys = HotkeyEngine(
        { Hotkeys.all(this).filter { !it.everywhere } },
        { HotkeyActions.run(this, it.action, it.arg, this) },
        { code, taps -> replay(code, taps) },
    )

    /** A press that wasn't a shortcut after all: let it do its ordinary job. */
    internal fun replay(code: Int, taps: Int) {
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
            HotkeyActions.QUICK -> quickCustomise()
            HotkeyActions.PHONE -> openPhoneSetup()
            HotkeyActions.WHO -> WhoActivity.start(this)
            HotkeyActions.SETTINGS_PAGE -> openTunaHomeSettings(arg)
        }
    }

    /** A shortcut pressed elsewhere that needs the home screen: do it once it shows. */
    internal fun hotkeyFromIntent(intent: Intent?) {
        val action = intent?.getStringExtra(HotkeyActions.EXTRA_ACTION) ?: return
        val arg = intent.getStringExtra(HotkeyActions.EXTRA_ARG)
        intent.removeExtra(HotkeyActions.EXTRA_ACTION)
        scroll.postDelayed({ if (!isDestroyed) hotkey(action, arg) }, 250)
    }
    internal lateinit var sideMenu: SideMenu
    internal var nowPlaying: NowPlaying? = null

    /** The app whose picture is being chosen, while the TV's picture chooser is open. */
    internal var choosingPictureFor: String? = null

    /** Installing or updating an app sends a burst of changes: load once, after the burst. */
    internal val reloadSoon = Runnable { reload() }

    internal val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            idleHandler.removeCallbacks(reloadSoon)
            idleHandler.postDelayed(reloadSoon, 1200)
        }
    }

    /** Sections with the same card style share their spare cards, so scrolling builds fewer. */
    internal val pools = HashMap<String, RecyclerView.RecycledViewPool>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        choosingPictureFor = savedInstanceState?.getString(STATE_PICTURE_FOR)
        if (savedInstanceState?.getBoolean(STATE_QUICK) == true) {
            // Rebuilt after a quick change: offer the list again once the screen is up
            window.decorView.postDelayed({ if (!isDestroyed) quickCustomise() }, 350)
        }

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
        previews = Previews(findViewById(R.id.previewVideo))
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
            if (now != null && now.parent !is SectionList) {
                if (ambient.enabled) ambient.focused(null)
                previews.focused(null)
            }
        }
        fadeWhenIdle.clear()
        listOf(R.id.sections, R.id.appsHint, R.id.brand, R.id.networkIcon, R.id.checkUpdate,
            R.id.openTvSettings, R.id.openSettings, R.id.sideMenu, R.id.vpnIcon, R.id.openSearch, R.id.systemInfo).forEach { fadeWhenIdle.add(findViewById(it)) }
        if (!Look.IDLE_CLOCK.get(this)) {
            fadeWhenIdle.add(findViewById(R.id.clockFace))
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
        } else {
            // Just after the TV starts: the copy kept on storage, while the real list loads
            thread(name = "TunaHome kept apps") {
                val kept = AppsOnDisk.read(applicationContext)
                runOnUiThread {
                    if (kept != null && !loaded && !isDestroyed) {
                        apps = kept
                        loaded = true
                        show()
                    }
                }
            }
        }
        show()
        if (!Apps.cacheFresh(this) || Prefs.appsChanged) {
            Prefs.appsChanged = false
            reload()
        }
        hotkeyFromIntent(intent)
        WelcomeActivity.showIfNew(this)
        Profiles.askIfWanted(this, Profiles.ASK_START)
    }

    /** Snow, rain, leaves or whatever is chosen, following the weather or season if asked. */
    internal fun applyEffects() {
        val view = findViewById<EffectsView>(R.id.effects)
        view.amount = Effects.AMOUNT.get(this) / 100f
        view.kind = Effects.kind(this)
        view.visibility = if (view.kind == Effects.Kind.NONE) View.GONE else View.VISIBLE
    }

    /** Whether the look changed since this screen was built (for the files that extend it). */
    internal fun lookChanged(): Boolean = outOfDate()

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
        outState.putBoolean(STATE_QUICK, reopenQuick)
    }

    // ------------------------------------------------------------------ quick changes

    /** Set when a quick change rebuilds the screen, so the list comes back to try another. */
    internal var reopenQuick = false

    internal var quickUnlocked = false

    override fun onDestroy() {
        backdrop.stop()
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        backdrop.start()
        applyEffects()
        findViewById<EffectsView>(R.id.effects).start()
        watchNetwork()
        nowPlaying?.start()
        arrive()
    }

    /** Sections glide up into place, one after another, as the home screen appears. */
    internal fun arrive() {
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
        findViewById<EffectsView>(R.id.effects).stop()
        ambient.clear()
        previews.clear()
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
    internal val weatherHandler = Handler(Looper.getMainLooper())
    internal val weatherTick: Runnable = object : Runnable {
        override fun run() {
            Weather.refresh(this@HomeActivity) {
                showWeather()
                // The weather changed: so may the effect following it
                applyEffects()
            }
            weatherHandler.postDelayed(this, 30L * 60 * 1000)
        }
    }

    internal var weatherSized = false

    // Free storage and memory, every half minute while the home screen is up
    internal val infoTick: Runnable = object : Runnable {
        override fun run() {
            NightDim.apply(this@HomeActivity)
            if (showSystemInfo() || NightDim.FROM.get(this@HomeActivity) != 0) {
                weatherHandler.postDelayed(this, 30_000L)
            }
        }
    }

    // Watch Next changes while you watch on another device, or as shows are added: look again
    // every five minutes while the home screen is up
    internal val watchNextTick: Runnable = object : Runnable {
        override fun run() {
            reloadWatchNext()
            weatherHandler.postDelayed(this, 5L * 60 * 1000)
        }
    }

    // Apps write where you got to as you leave them, often a few seconds after the home screen
    // is back: look again as soon as the TV's list changes, and once more shortly after coming back
    private val watchNextSoon = Runnable { reloadWatchNext() }
    private var watchNextObserver: android.database.ContentObserver? = null

    private fun watchWatchNext(on: Boolean) {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        watchNextObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                // Already gone
            }
        }
        watchNextObserver = null
        weatherHandler.removeCallbacks(watchNextSoon)
        if (!on || sections.none { it.row.kind == Rows.WATCH_NEXT }) {
            return
        }
        val observer = object : android.database.ContentObserver(weatherHandler) {
            override fun onChange(selfChange: Boolean) {
                // A few changes usually come together: once, after they settle
                weatherHandler.removeCallbacks(watchNextSoon)
                weatherHandler.postDelayed(watchNextSoon, 1500L)
            }
        }
        try {
            contentResolver.registerContentObserver(android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, true, observer)
            watchNextObserver = observer
        } catch (e: Exception) {
            // This TV doesn't say: the checks below still catch up
        }
        weatherHandler.postDelayed(watchNextSoon, 15_000L)
    }

    override fun onPause() {
        weatherHandler.removeCallbacks(weatherTick)
        weatherHandler.removeCallbacks(infoTick)
        weatherHandler.removeCallbacks(watchNextTick)
        watchWatchNext(false)
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

    internal fun scheduleIdle() {
        idleHandler.removeCallbacks(goIdle)
        val after = Look.idleMs(this)
        if (after > 0) {
            idleHandler.postDelayed(goIdle, after)
        }
    }

    internal fun enterIdle() {
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

    internal fun leaveIdle(animate: Boolean) {
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

    // ------------------------------------------------------------------ network icon

    override fun onResume() {
        super.onResume()
        // A rename or a new photo in settings shows straight away
        showWho()
        // Back from watching something: its place in Watch Next has changed
        weatherHandler.removeCallbacks(watchNextTick)
        watchNextTick.run()
        watchWatchNext(true)
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
            show(sections.filter { it.row.kind == Rows.RECENT || it.row.kind == Rows.FOR_YOU || it.row.sort == Rows.SORT_USED }
                .map { it.row.id }.toSet())
        }
        scheduleIdle()
        Updater.resumeIfWaiting(this)
        ChangeLog.showIfUpdated(this)
        MorningActivity.showIfDue(this)
        Occasions.greetIfDue(this)
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

    /** The voice or search button: TunaHome's own voice commands, if the viewer chose them. */
    override fun onSearchRequested(): Boolean {
        if (Look.MIC_TUNAHOME.get(this)) {
            openSearch(true)
            return true
        }
        return super.onSearchRequested()
    }

    /** A home screen has nowhere to go back to, so Back returns to the top instead of closing. */
    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goToTop()
    }

    // ------------------------------------------------------------------ side menu

    // ------------------------------------------------------------------ where the section you're on sits

    // ------------------------------------------------------------------ top of the screen

    // ------------------------------------------------------------------ sections

    internal fun buildSections() {
        val holder = findViewById<LinearLayout>(R.id.sections)
        holder.removeAllViews()
        sections.clear()
        val titles = Look.ROW_TITLES.get(this)
        val rowGap = Math.round(Look.dp(this, Look.rowGapDp(this)))

        for (row in Rows.load(this)) {
            if (!row.visible || Guest.hides(this, row.kind)) {
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
            dressSection(view)
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
            val sectionTiles = if (row.cardSize >= 0 || row.style >= 0) TileStyle(this, row.cardSize, row.style) else tiles
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
            adapter.onFocused = { app ->
                ambient.focused(app)
                previews.focused(app)
            }
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
    internal fun saveOrder(row: Rows.Row, order: List<String>) {
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
    internal fun ordered(apps: List<AppEntry>, order: List<String>): List<AppEntry> {
        if (order.isEmpty()) {
            return apps
        }
        val place = HashMap<String, Int>()
        order.forEachIndexed { index, pkg -> place[pkg] = index }
        return apps.sortedBy { place[it.pkg] ?: Int.MAX_VALUE }
    }

    /**
     * The apps you usually open around this time of day, most likely first: each app's opens in
     * this hour count fully, the hours either side half, two hours away a quarter.
     */
    internal fun forYou(everyApp: Map<String, AppEntry>): List<AppEntry> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val weights = mapOf(0 to 1f, 1 to 0.5f, -1 to 0.5f, 2 to 0.25f, -2 to 0.25f)
        return Prefs.hourUses(this).mapNotNull { (pkg, counts) ->
            val app = everyApp[pkg] ?: return@mapNotNull null
            val score = weights.entries.sumOf { (k, w) -> (counts[(hour + k + 24) % 24] * w).toDouble() }
            if (score >= 1.0) app to score else null
        }.sortedByDescending { it.second }.take(FOR_YOU_MAX).map { it.first }
    }

    /** A section sorted the way the viewer chose for it. */
    internal fun sorted(list: List<AppEntry>, sort: Int): List<AppEntry> = when (sort) {
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

    internal fun reloadWatchNext() {
        val wantShows = sections.any { it.row.kind == Rows.WATCH_NEXT }
        // Up next mixes the PC game you were playing in with Watch Next
        val wantGames = sections.any { it.row.kind == Rows.PC_GAMES } || (wantShows && Look.UP_NEXT.get(this))
        if (!wantShows && !wantGames) {
            return
        }
        if (loadingWatchNext) {
            // Still fetching the last look: look again when it's done, so this change isn't missed
            watchNextAgain = true
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
                if (watchNextAgain) {
                    watchNextAgain = false
                    weatherHandler.post { reloadWatchNext() }
                }
                // Box art can arrive after the list (TunaReceive fetches it later): show it then too
                var gamesChanged = false
                if (games.map { it.pkg to (it.banner != null) } != pcGames.map { it.pkg to (it.banner != null) }) {
                    pcGames = games
                    gamesChanged = true
                    sections.filter { it.row.kind == Rows.PC_GAMES }.forEach { it.show(games) }
                    applyGaps()
                }
                // Nothing new: leave the section alone, so it doesn't jump under the viewer. An
                // entry the app changed in place (a new game, the next episode) counts as new.
                fun picture(e: AppEntry) = (e.own as? android.graphics.drawable.BitmapDrawable)?.bitmap
                val same = found.size == watchNext.size && found.indices.all {
                    val a = found[it]
                    val b = watchNext[it]
                    a.pkg == b.pkg && a.progress == b.progress && a.label == b.label &&
                        a.preview == b.preview && picture(a) === picture(b)
                }
                if (same && found.isNotEmpty() && !(gamesChanged && Look.UP_NEXT.get(this))) {
                    return@runOnUiThread
                }
                watchNext = found
                val current = Rows.load(this).associateBy { it.id }
                sections.filter { it.row.kind == Rows.WATCH_NEXT }
                    .forEach { it.show(upNext(withoutLeftOut(found, current[it.row.id] ?: it.row))) }
                applyGaps()
                if (!sideMenu.isOpen) {
                    refreshMenu()
                }
            }
        }
    }

    /**
     * Up next (when it's on): the PC games played in the last two weeks join Watch Next, all in
     * the order you left them, so the game you were in the middle of sits beside your shows.
     */
    internal fun upNext(shows: List<AppEntry>): List<AppEntry> {
        if (!Look.UP_NEXT.get(this) || Guest.on(this)) return shows
        val since = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
        val games = pcGames.filter { it.updated > since }.take(3)
        if (games.isEmpty()) return shows
        return (shows + games).sortedByDescending { it.updated }
    }

    // ------------------------------------------------------------------ the list of apps

    internal fun reload() {
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
            if (found.isNotEmpty()) {
                // Kept for the next time the TV starts (only written when something changed)
                thread(name = "TunaHome keep apps") { AppsOnDisk.save(applicationContext, found) }
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
    internal fun show(only: Set<String>? = null) {
        val hidden = Prefs.hidden(this)
        // Kids mode: only the apps a grown-up allowed (no web links)
        val kidsAllowed = Profiles.current(this)?.takeIf { it.kids }?.allowed
        val visible = apps.filter { it.pkg !in hidden && (kidsAllowed == null || (!it.isLink && it.pkg in kidsAllowed)) }
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
                Rows.FOR_YOU -> forYou(everyApp)
                Rows.WATCH_NEXT -> upNext(withoutLeftOut(watchNext, rows[section.row.id] ?: section.row))
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
    internal fun applyGaps() {
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
    internal fun placeStartFocus() {
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

    internal fun open(app: AppEntry) {
        if (app.isFolder) {
            openFolder(app.pkg.removePrefix(Folders.PREFIX))
            return
        }
        Launch.open(this, app, failed = { reload() })
    }

    // ------------------------------------------------------------------ folders

    // ------------------------------------------------------------------ an app's menu

    // ------------------------------------------------------------------ an app's own picture

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

    companion object {
        internal const val STATE_PICTURE_FOR = "picture_for"
        internal const val STATE_QUICK = "quick"
        internal const val FOR_YOU_MAX = 12
        internal const val REQUEST_TILE_PICTURE = 1

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
