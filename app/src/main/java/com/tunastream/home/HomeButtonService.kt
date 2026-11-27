package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the Home button open TunaHome. Android TV has no "choose your home app" setting, so this
 * is an accessibility service the viewer switches on themselves in the TV's settings; switching
 * it off there is the whole undo. Nothing on the TV is disabled or removed.
 *
 * What it does:
 *  - a Home press opens TunaHome instead of the TV's own home screen;
 *  - if the viewer asked for it in Settings, the TV's own home screen appearing (after Back out
 *    of an app, or a restart) opens TunaHome too;
 *  - shortcuts on Home and Back, and the pointer for phone apps when the viewer switches it on.
 * It asks Android for key presses, for which app came to the front, and to tap for the pointer.
 * It cannot read the screen.
 */
class HomeButtonService : AccessibilityService() {
    private var lastReplaced = 0L
    private var filteringKeys = true

    private val guardHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Daily limits run out while an app is up, not only as one opens: checked every half minute. */
    private val guardTick: Runnable = object : Runnable {
        override fun run() {
            UsageLog.current?.let { if (it != packageName) Guard.check(this@HomeButtonService, it) }
            guardHandler.postDelayed(this, GUARD_TICK_MS)
        }
    }

    /** The pointer for phone apps (a shortcut switches it on). */
    internal val pointer by lazy { RemotePointer(this) }

    /**
     * While TunaReceive streams a game (or watches one), button presses go straight to it.
     * Filtering keys makes Android wait for this service before every press, controller buttons
     * included, which adds delay to the game, more so when TunaHome is busy. So the filter is
     * off while a stream is in front, and back on as soon as anything else is.
     */
    private fun setKeyFiltering(on: Boolean) {
        if (filteringKeys == on) {
            return
        }
        val info = serviceInfo ?: return
        info.flags = if (on) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        serviceInfo = info
        filteringKeys = on
        hotkeys.reset()
    }

    /** Shortcuts on Home and Back that work everywhere. */
    private val hotkeys = HotkeyEngine(
        { Hotkeys.all(this).filter { it.everywhere } },
        { HotkeyActions.run(this, it.action, it.arg, null) },
        { code, _ ->
            // Not a shortcut after all: do what the button normally does
            if (code == KeyEvent.KEYCODE_HOME) showHome() else performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        },
    )

    /** Opens the app the viewer chose when the TV wakes up. */
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                UsageLog.screen(context, false)
                // Guest mode ends when the TV sleeps
                Guest.end(context)
                return
            }
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                UsageLog.screen(context, true)
                // Give the TV a moment to finish waking, so its own home screen doesn't land on top
                android.os.Handler(mainLooper).postDelayed({
                    // Who's watching first, if asked for; otherwise the app chosen for waking up
                    if (!Profiles.askIfWanted(this@HomeButtonService, Profiles.ASK_WAKE)) openWakeApp()
                }, 1500)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        OpenApps.addDefaultShortcut(this)
        guardHandler.postDelayed(guardTick, GUARD_TICK_MS)
        registerReceiver(wakeReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        // Switched on by Android just after the TV started: that's a wake-up too
        if (SystemClock.elapsedRealtime() < 3 * 60 * 1000) {
            android.os.Handler(mainLooper).postDelayed({ openWakeApp() }, 4000)
        }
    }

    override fun onDestroy() {
        guardHandler.removeCallbacks(guardTick)
        pointer.off(false)
        if (running === this) {
            running = null
        }
        hotkeys.reset()
        try {
            unregisterReceiver(wakeReceiver)
        } catch (e: Exception) {
            // It was never registered
        }
        super.onDestroy()
    }

    private fun openWakeApp() {
        val pkg = Prefs.wakeApp(this) ?: return
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
            ?: return
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $pkg on wake", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            SleepTimer.pressed()
        }
        if (pointer.onKey(event)) {
            return true
        }
        // Voice commands from any app ("open Netflix", "play Halo"), when chosen
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            event.keyCode == KeyEvent.KEYCODE_ASSIST) {
            if (Look.MIC_TUNAHOME.get(this) && Look.MIC_EVERYWHERE.get(this)) {
                if (event.action == KeyEvent.ACTION_UP) {
                    try {
                        startActivity(Intent(this, SearchActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(SearchActivity.EXTRA_VOICE, true))
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't open voice commands", e)
                    }
                }
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (hotkeys.onKey(event)) {
                return true
            }
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME) {
            return false
        }
        if (CrashLog.failingLately(this)) {
            // TunaHome keeps stopping: let the TV's own home screen open for a few minutes
            if (event.action == KeyEvent.ACTION_UP) {
                android.widget.Toast.makeText(this, R.string.safety_stock_home, android.widget.Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            showHome()
        }
        // Swallow both halves of the press so the TV's own home screen doesn't open as well
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        // For Open apps: what came to the front
        OpenApps.note(this, pkg)
        UsageLog.front(this, pkg)
        // Kids mode and daily limits: an app that may not be up gives way
        if (pkg != packageName && Guard.check(this, pkg)) {
            return
        }
        when {
            pkg == PcGames.RECEIVER && cls in STREAMING_SCREENS -> {
                pointer.off(false)
                setKeyFiltering(false)
            }
            // TunaReceive's own pop-ups over the stream leave things as they are
            pkg == PcGames.RECEIVER && !cls.startsWith("com.limelight.") -> {}
            else -> setKeyFiltering(true)
        }
        if (pkg !in STOCK_HOME_SCREENS || !Prefs.replaceStock(this) || CrashLog.failingLately(this)) {
            return
        }
        // At most once every couple of seconds, so a home screen that won't stay hidden can't
        // make the two fight
        val now = SystemClock.uptimeMillis()
        if (now - lastReplaced < 2000) {
            return
        }
        lastReplaced = now
        showHome()
    }

    override fun onInterrupt() {
    }

    private fun showHome() {
        try {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .setAction(ACTION_HOME_PRESSED)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open TunaHome", e)
        }
    }

    companion object {
        private const val TAG = "TunaHome"
        private const val GUARD_TICK_MS = 30_000L
        const val ACTION_HOME_PRESSED = "com.tunastream.home.HOME_PRESSED"

        /** The service while it is switched on, for shortcuts that need it (Recent apps, Sleep...). */
        @Volatile
        var running: HomeButtonService? = null

        /**
         * Only these are ever replaced. A fixed list on purpose: the TV's Settings app also
         * counts as a "home" app on some devices, and replacing that would lock the viewer out.
         */
        private val STREAMING_SCREENS = setOf("com.limelight.Game", "com.limelight.WatchActivity")

        internal val STOCK_HOME_SCREENS = setOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher",
        )

        /** Whether the viewer has switched the service on in the TV's accessibility settings. */
        fun isOn(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, HomeButtonService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
