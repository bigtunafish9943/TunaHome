package com.tunastream.home

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent

/**
 * The apps opened lately, most recent first, for Open apps (Home pressed twice). Android
 * doesn't tell other apps what's running, so this is what came to the front: seen by the Home
 * button service, or opened from TunaHome. Closing one from Open apps takes it off.
 */
object OpenApps {
    private const val KEY = "open_apps"
    private const val SHORTCUT_ADDED = "open_apps_shortcut_added"
    private const val MAX = 12

    /** Not apps you'd switch to: the TV's own screens and pop-ups. */
    private val SKIP = setOf(
        "android", "com.android.systemui", "com.android.tv.settings", "com.google.android.tungsten.setupwraith",
        "com.google.android.katniss", "com.google.android.apps.tv.dreamx", "com.android.vending",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.packageinstaller",
    )

    @Volatile
    private var cache: List<String>? = null
    private val launchable = HashMap<String, Boolean>()

    fun list(context: Context): List<String> = cache ?: (Prefs.of(context).getString(KEY, "") ?: "")
        .split('\n').filter { it.isNotEmpty() }.also { cache = it }

    private fun save(context: Context, list: List<String>) {
        cache = list
        Prefs.of(context).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    /** [pkg] came to the front. */
    fun note(context: Context, pkg: String) {
        val now = list(context)
        if (now.firstOrNull() == pkg || pkg == context.packageName || pkg in SKIP || pkg in HomeButtonService.STOCK_HOME_SCREENS) {
            return
        }
        if (!canOpen(context, pkg) || pkg == keyboard(context)) {
            return
        }
        save(context, (listOf(pkg) + now.filter { it != pkg }).take(MAX))
    }

    fun remove(context: Context, pkg: String) = save(context, list(context).filter { it != pkg })

    fun clear(context: Context) = save(context, emptyList())

    private fun canOpen(context: Context, pkg: String): Boolean = synchronized(launchable) {
        launchable.getOrPut(pkg) {
            val pm = context.packageManager
            try {
                pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun keyboard(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    } catch (e: Exception) {
        null
    }

    /**
     * Home pressed twice opens Open apps, set up once as an ordinary shortcut the viewer can
     * change or remove under Shortcut keys (and it isn't put back after that).
     */
    fun addDefaultShortcut(context: Context) {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(SHORTCUT_ADDED, false)) {
            return
        }
        prefs.edit().putBoolean(SHORTCUT_ADDED, true).apply()
        val list = Hotkeys.load(context)
        if (list.any { it.keys == listOf(KeyEvent.KEYCODE_HOME) && it.presses == 2 }) {
            return
        }
        list.add(Hotkeys.Hotkey(listOf(KeyEvent.KEYCODE_HOME), 2, true, HotkeyActions.OPEN_APPS))
        Hotkeys.save(context, list)
    }
}
