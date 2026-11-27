package com.tunastream.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Two at once: two of the TV's own apps on screen together, each in a window of its own, side by
 * side or one big and one small. Android has had windows like these since Android 7 (freeform
 * windows), but on a TV they stay off until two developer settings are turned on; TunaHome then
 * opens each app in its window, much as the Taskbar app does. Turning the settings off and
 * restarting the TV puts everything back to full screen.
 *
 * Whether a given app keeps playing while the other plays is up to that app: many pause when
 * another starts making sound, and some protected video may not show in a window.
 */
object TwoUp {
    private const val TAG = "TunaHome"

    /** Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT and ..._FORCE_RESIZABLE_ACTIVITIES */
    private const val FREEFORM = "enable_freeform_support"
    private const val RESIZABLE = "force_resizable_activities"

    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN and _FREEFORM */
    private const val MODE_FULLSCREEN = 1
    private const val MODE_FREEFORM = 5

    private const val FIRST = "two_up_first"
    private const val SECOND = "two_up_second"
    private const val LAYOUT = "two_up_layout"

    /** The first app big and the second small beside it, or both the same size. */
    const val BIG_AND_SMALL = 0
    const val HALVES = 1

    private fun global(context: Context, key: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, key, 0) != 0
    } catch (e: Exception) {
        false
    }

    /** The two settings are on (and Android is new enough). */
    fun ready(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 && global(context, FREEFORM) && global(context, RESIZABLE)

    fun first(context: Context): String? = Prefs.of(context).getString(FIRST, null)
    fun second(context: Context): String? = Prefs.of(context).getString(SECOND, null)
    fun layout(context: Context): Int = Prefs.of(context).getInt(LAYOUT, BIG_AND_SMALL)

    fun save(context: Context, first: String, second: String, layout: Int) {
        Prefs.of(context).edit().putString(FIRST, first).putString(SECOND, second).putInt(LAYOUT, layout).apply()
    }

    fun label(context: Context, pkg: String?): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg ?: "", 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        pkg ?: "?"
    }

    /** Apps that can be opened, by name. */
    fun apps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).forEach {
                    val pkg = it.activityInfo.packageName
                    if (pkg != context.packageName && pkg !in found) found[pkg] = it.loadLabel(pm).toString()
                }
            } catch (e: Exception) {
                // That kind can't be listed
            }
        }
        return found.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    /** Where each window goes: 16:9 windows that don't overlap, so neither hides the other. */
    fun bounds(context: Context, layout: Int): Pair<Rect, Rect> {
        val size = Point()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
        val w = size.x
        val h = size.y
        val gap = w / 80
        return if (layout == HALVES) {
            val ww = (w - gap) / 2
            val wh = ww * 9 / 16
            val top = (h - wh) / 2
            Rect(0, top, ww, top + wh) to Rect(w - ww, top, w, top + wh)
        } else {
            val bw = w * 7 / 10
            val bh = bw * 9 / 16
            val top = (h - bh) / 2
            val sw = w - bw - gap
            val sh = sw * 9 / 16
            Rect(0, top, bw, top + bh) to Rect(w - sw, top, w, top + sh)
        }
    }

    private fun options(mode: Int, bounds: Rect?): Bundle? {
        val options = ActivityOptions.makeBasic()
        try {
            HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", mode)
        } catch (e: Throwable) {
            Log.w(TAG, "Two at once: no windowing mode: $e")
            return null
        }
        if (bounds != null) options.setLaunchBounds(bounds)
        return options.toBundle()
    }

    /** Apps TunaHome put in a window, until they're opened full screen again, and since when. */
    private const val WINDOWED = "two_up_windowed"
    private const val WINDOWED_BOOT = "two_up_windowed_boot"

    /** When the TV last started, to the nearest minute: a restart puts every app back full screen. */
    private fun bootMinute(): Long = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000

    private fun windowed(context: Context): MutableSet<String> {
        val prefs = Prefs.of(context)
        if (Math.abs(prefs.getLong(WINDOWED_BOOT, 0) - bootMinute()) > 1) return HashSet()
        return HashSet(prefs.getStringSet(WINDOWED, emptySet()) ?: emptySet())
    }

    private fun setWindowed(context: Context, pkgs: Set<String>) {
        Prefs.of(context).edit().putStringSet(WINDOWED, pkgs).putLong(WINDOWED_BOOT, bootMinute()).apply()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Presses one of the buttons Android draws at the top of an app's window (full screen, or
     * close), as a tap through the Home button service. The bar is 42dp tall with two 32dp
     * buttons at its right end: close at the very end, full screen beside it.
     */
    private fun pressWindowButton(context: Context, window: Rect, close: Boolean): Boolean {
        val service = HomeButtonService.running ?: return false
        if (Build.VERSION.SDK_INT < 24) return false
        val dp = context.resources.displayMetrics.density
        val x = window.right - dp * (if (close) 21f else 63f)
        val y = window.top + dp * 21f
        val path = android.graphics.Path().apply { moveTo(x, y) }
        return try {
            service.dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build(),
                null, null,
            )
        } catch (e: Exception) {
            false
        }
    }

    /** Where TunaHome put this app's window. */
    private fun windowOf(context: Context, pkg: String): Rect? {
        val (big, small) = bounds(context, layout(context))
        return when (pkg) {
            first(context) -> big
            second(context) -> small
            else -> null
        }
    }

    /**
     * An app keeps the window it was opened in for as long as Android keeps it, even when opened
     * again normally. Opening one of Two at once's apps from TunaHome brings it back full screen:
     * as a new copy, or for an app that allows only one, by pressing its window's full screen
     * button. True when it was opened here.
     */
    fun takeBack(context: Context, pkg: String): Boolean {
        val set = windowed(context)
        if (pkg !in set || !ready(context)) return false
        val app = context.applicationContext
        if (onlyOneCopy(app, pkg)) {
            val window = windowOf(app, pkg) ?: return false
            if (HomeButtonService.running == null) return false
            if (!launch(app, pkg, MODE_FREEFORM, window, newTask = false)) return false
            main.postDelayed({ pressWindowButton(app, window, close = false) }, 900)
        } else if (!launch(app, pkg, MODE_FULLSCREEN, null, newTask = true)) {
            return false
        }
        set.remove(pkg)
        setWindowed(app, set)
        return true
    }

    /**
     * Android moves an app that is already open in or out of a window only as a new copy of it.
     * Apps that allow only one copy (launch mode singleTask or singleInstance) stay where they
     * are until they're closed: the toast says so.
     */
    private fun onlyOneCopy(context: Context, pkg: String): Boolean = try {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        val mode = intent?.let { pm.resolveActivity(it, 0)?.activityInfo?.launchMode } ?: 0
        mode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK || mode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
    } catch (e: Exception) {
        false
    }

    private fun launch(context: Context, pkg: String, mode: Int, bounds: Rect?, newTask: Boolean): Boolean {
        val pm = context.packageManager
        val intent = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)) ?: return false
        val bundle = options(mode, bounds) ?: return false
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), bundle)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Two at once: couldn't open $pkg: $e")
            false
        }
    }

    /** Opens both: the second first, so the first ends up with the remote. */
    fun start(context: Context, first: String, second: String, layout: Int, done: () -> Unit = {}): Boolean {
        if (!ready(context)) return false
        val (big, small) = bounds(context, layout)
        setWindowed(context, windowed(context) + setOf(first, second))
        if (!launch(context, second, MODE_FREEFORM, small, newTask = true)) {
            Toast.makeText(context, context.getString(R.string.open_failed, label(context, second)), Toast.LENGTH_LONG).show()
            return false
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!launch(context, first, MODE_FREEFORM, big, newTask = true)) {
                Toast.makeText(context, context.getString(R.string.open_failed, label(context, first)), Toast.LENGTH_LONG).show()
            }
            listOf(first, second).firstOrNull { onlyOneCopy(context, it) }?.let {
                Toast.makeText(context, context.getString(R.string.two_up_one_copy, label(context, it)), Toast.LENGTH_LONG).show()
            }
            done()
        }, 900)
        return true
    }

    /** Gives the remote to one of the two (it comes to the front, in its own window). */
    fun focus(context: Context, which: Int) {
        val first = first(context) ?: return
        val second = second(context) ?: return
        val (big, small) = bounds(context, layout(context))
        // The window that's already there comes to the front
        if (which == 0) launch(context, first, MODE_FREEFORM, big, newTask = false)
        else launch(context, second, MODE_FREEFORM, small, newTask = false)
    }

    /**
     * Back to one app, full screen. With the Home button service on, the other app's window is
     * closed with its close button and the first one's full screen button is pressed, so both are
     * where they were before; without it, the first opens again full screen as a new copy and the
     * other is stopped once it's out of sight.
     */
    fun end(context: Context) {
        val app = context.applicationContext
        val keep = first(app) ?: return
        val other = second(app)?.takeIf { it != keep }
        val (big, small) = bounds(app, layout(app))
        val set = windowed(app)
        if (HomeButtonService.running != null && ready(app)) {
            var at = 300L
            if (other != null && other in set) {
                main.postDelayed({ launch(app, other, MODE_FREEFORM, small, newTask = false) }, at)
                main.postDelayed({ pressWindowButton(app, small, close = true) }, at + 900)
                at += 1500
            }
            if (keep in set) {
                main.postDelayed({ launch(app, keep, MODE_FREEFORM, big, newTask = false) }, at)
                main.postDelayed({ pressWindowButton(app, big, close = false) }, at + 900)
            } else {
                main.postDelayed({ launch(app, keep, MODE_FULLSCREEN, null, newTask = false) }, at)
            }
        } else {
            if (!takeBack(app, keep)) launch(app, keep, MODE_FULLSCREEN, null, newTask = true)
            if (other != null) {
                main.postDelayed({
                    try {
                        (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(other)
                    } catch (e: Exception) {
                        // Not allowed now: it stays paused behind
                    }
                }, 3000)
            }
        }
        set.remove(keep)
        other?.let { set.remove(it) }
        setWindowed(app, set)
    }
}

/**
 * The Two at once menu (a shortcut, the phone remote, TunaHome settings): start the two apps,
 * choose them, move the remote between them, or go back to one.
 */
class TwoUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        // From the automatic checks on each build: start these two straight away
        val first = intent.getStringExtra(EXTRA_FIRST)
        val second = intent.getStringExtra(EXTRA_SECOND)
        if (first != null && second != null) {
            TwoUp.save(this, first, second, intent.getIntExtra(EXTRA_LAYOUT, TwoUp.BIG_AND_SMALL))
            if (!TwoUp.start(this, first, second, TwoUp.layout(this)) { finish() }) {
                Toast.makeText(this, R.string.two_up_not_ready_short, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_END, false)) {
            TwoUp.end(this)
            finish()
            return
        }
        if (!TwoUp.ready(this)) setup() else menu()
    }

    /** The two settings aren't on: how to turn them on, and off again. */
    private fun setup() {
        QuietDialog.Builder(this)
            .setTitle(R.string.two_up_title)
            .setMessage(R.string.two_up_setup)
            .setPositiveButton(R.string.two_up_open_developer) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.two_up_no_developer, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    private var staying = false

    private fun menu() {
        val first = TwoUp.first(this)
        val second = TwoUp.second(this)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (first != null && second != null) {
            val a = TwoUp.label(this, first)
            val b = TwoUp.label(this, second)
            labels.add(getString(R.string.two_up_start, a, b))
            actions.add {
                staying = true
                if (!TwoUp.start(this, first, second, TwoUp.layout(this)) { finish() }) finish()
            }
            labels.add(getString(R.string.two_up_remote_to, a))
            actions.add { TwoUp.focus(this, 0) }
            labels.add(getString(R.string.two_up_remote_to, b))
            actions.add { TwoUp.focus(this, 1) }
            labels.add(getString(R.string.two_up_end, a))
            actions.add { TwoUp.end(this) }
        }
        labels.add(getString(R.string.two_up_choose))
        actions.add {
            staying = true
            choose()
        }
        labels.add(getString(R.string.two_up_undo_title))
        actions.add {
            staying = true
            undo()
        }
        QuietDialog.Builder(this)
            .setTitle(R.string.two_up_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setOnDismissListener { if (!staying) finish() }
            .show()
    }

    /** Big app, small app, layout, then both open. */
    private fun choose() {
        val apps = TwoUp.apps(this)
        val names = apps.map { it.second as CharSequence }
        pick(getString(R.string.two_up_pick_first), names) { a ->
            pick(getString(R.string.two_up_pick_second), names) { b ->
                pick(getString(R.string.two_up_pick_layout),
                    listOf(getString(R.string.two_up_big_small), getString(R.string.two_up_halves))) { layout ->
                    TwoUp.save(this, apps[a].first, apps[b].first, layout)
                    if (!TwoUp.start(this, apps[a].first, apps[b].first, layout) { finish() }) finish()
                }
            }
        }
    }

    private fun pick(title: String, names: List<CharSequence>, then: (Int) -> Unit) {
        var picked = false
        QuietDialog.Builder(this)
            .setTitle(title)
            .setItems(names.toTypedArray()) { _, which ->
                picked = true
                then(which)
            }
            .setOnDismissListener { if (!picked) finish() }
            .show()
    }

    private fun undo() {
        QuietDialog.Builder(this)
            .setTitle(R.string.two_up_undo_title)
            .setMessage(R.string.two_up_undo)
            .setPositiveButton(R.string.two_up_open_developer) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.two_up_no_developer, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_FIRST = "two_up_first"
        const val EXTRA_SECOND = "two_up_second"
        const val EXTRA_LAYOUT = "two_up_layout"
        const val EXTRA_END = "two_up_end"

        fun open(context: Context, arg: String? = null) {
            val intent = Intent(context, TwoUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val parts = arg?.split(',') ?: emptyList()
            if (parts.size >= 2) {
                intent.putExtra(EXTRA_FIRST, parts[0]).putExtra(EXTRA_SECOND, parts[1])
                    .putExtra(EXTRA_LAYOUT, parts.getOrNull(2)?.toIntOrNull() ?: TwoUp.BIG_AND_SMALL)
            } else if (arg == "end") {
                intent.putExtra(EXTRA_END, true)
            }
            context.startActivity(intent)
        }
    }
}
