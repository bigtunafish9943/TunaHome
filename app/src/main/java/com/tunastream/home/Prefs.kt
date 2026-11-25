package com.tunastream.home

import android.content.Context
import android.content.SharedPreferences

/** What TunaHome remembers: favourites in order, hidden apps, and the settings switches. */
object Prefs {
    private const val FILE = "tunahome"
    private const val FAVOURITES = "favourites"
    private const val HIDDEN = "hidden"
    private const val PHONE_APPS = "show_phone_apps"
    private const val REPLACE_STOCK = "replace_stock"
    private const val WALLPAPER_STAMP = "wallpaper_stamp"

    /** Set when a setting that changes the list of apps was touched, so Home rebuilds it. */
    @Volatile
    var appsChanged = false

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Package names, in the order shown. */
    fun favourites(context: Context): MutableList<String> =
        (of(context).getString(FAVOURITES, "") ?: "")
            .split('\n').filter { it.isNotEmpty() }.toMutableList()

    fun setFavourites(context: Context, packages: List<String>) {
        of(context).edit().putString(FAVOURITES, packages.joinToString("\n")).apply()
    }

    fun hidden(context: Context): MutableSet<String> =
        HashSet(of(context).getStringSet(HIDDEN, emptySet()) ?: emptySet())

    fun setHidden(context: Context, packages: Set<String>) {
        of(context).edit().putStringSet(HIDDEN, HashSet(packages)).apply()
        appsChanged = true
    }

    fun showPhoneApps(context: Context): Boolean = of(context).getBoolean(PHONE_APPS, true)

    fun setShowPhoneApps(context: Context, show: Boolean) {
        of(context).edit().putBoolean(PHONE_APPS, show).apply()
        appsChanged = true
    }

    fun replaceStock(context: Context): Boolean = of(context).getBoolean(REPLACE_STOCK, false)

    fun setReplaceStock(context: Context, replace: Boolean) {
        of(context).edit().putBoolean(REPLACE_STOCK, replace).apply()
    }

    /** When the wallpaper was last changed; 0 when there is none. */
    fun wallpaperStamp(context: Context): Long = of(context).getLong(WALLPAPER_STAMP, 0)

    fun setWallpaperStamp(context: Context, stamp: Long) {
        of(context).edit().putLong(WALLPAPER_STAMP, stamp).apply()
    }

    /**
     * The viewer's own order for a row ("tv" or "mobile"), as package names. Empty means A to Z.
     * Apps that are not in the list, such as ones installed later, follow it in A to Z order.
     */
    fun order(context: Context, row: String): List<String> =
        (of(context).getString("order_$row", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun setOrder(context: Context, row: String, packages: List<String>) {
        of(context).edit().putString("order_$row", packages.joinToString("\n")).apply()
    }

    fun hasOwnOrder(context: Context): Boolean =
        order(context, "tv").isNotEmpty() || order(context, "mobile").isNotEmpty()

    fun clearOrder(context: Context) {
        of(context).edit().remove("order_tv").remove("order_mobile").apply()
        appsChanged = true
    }

    /** Apps opened from TunaHome, newest first, for the Recently opened section. */
    fun recent(context: Context): List<String> =
        (of(context).getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun addRecent(context: Context, pkg: String) {
        val list = (listOf(pkg) + recent(context).filter { it != pkg }).take(20)
        of(context).edit().putString("recent", list.joinToString("\n")).apply()
    }

    fun clearRecent(context: Context) {
        of(context).edit().remove("recent").apply()
    }

    /** The app to open when the TV wakes up, or null. */
    fun wakeApp(context: Context): String? = of(context).getString("wake_app", null)

    fun setWakeApp(context: Context, pkg: String?) {
        of(context).edit().putString("wake_app", pkg).apply()
    }

    /** The app opened last, for "When TunaHome opens, go to the app you used last". */
    fun lastOpened(context: Context): String? = of(context).getString("last_opened", null)

    fun setLastOpened(context: Context, pkg: String) {
        of(context).edit().putString("last_opened", pkg).apply()
    }

    // ------------------------------------------------------------------ names and use

    private fun pairs(context: Context, key: String): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        (of(context).getString(key, "") ?: "").split('\n').forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) {
                map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return map
    }

    private fun savePairs(context: Context, key: String, map: Map<String, String>) {
        of(context).edit().putString(key, map.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
    }

    /** Names the viewer gave apps, by package. */
    fun appNames(context: Context): Map<String, String> = pairs(context, "app_names")

    /** Gives an app a name of the viewer's own; null goes back to its own name. */
    fun setAppName(context: Context, pkg: String, name: String?) {
        val map = pairs(context, "app_names")
        if (name.isNullOrBlank()) map.remove(pkg) else map[pkg] = name.replace('\n', ' ').replace('\t', ' ')
        savePairs(context, "app_names", map)
        appsChanged = true
    }

    /** How many times each app was opened from TunaHome, for sorting by most used. */
    fun uses(context: Context): Map<String, Int> =
        pairs(context, "app_uses").mapValues { it.value.toIntOrNull() ?: 0 }

    /** How often each app was opened in each hour of the day, for "For you". */
    fun hourUses(context: Context): Map<String, IntArray> =
        pairs(context, "app_hours").mapValues { (_, v) ->
            val parts = v.split(',')
            IntArray(24) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
        }

    fun addHourUse(context: Context, pkg: String) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val map = pairs(context, "app_hours")
        val counts = map[pkg]?.split(',')?.map { it.toIntOrNull() ?: 0 }?.toMutableList() ?: MutableList(24) { 0 }
        while (counts.size < 24) counts.add(0)
        counts[hour] = counts[hour] + 1
        map[pkg] = counts.joinToString(",")
        savePairs(context, "app_hours", map)
    }

    @Volatile
    private var openedCache: Set<String>? = null

    /** Whether an app has ever been opened from TunaHome (quick: kept in memory). */
    fun everOpened(context: Context, pkg: String): Boolean {
        val set = openedCache ?: (uses(context).keys + recent(context)).also { openedCache = it }
        return pkg in set
    }

    fun addUse(context: Context, pkg: String) {
        openedCache = null
        val map = pairs(context, "app_uses")
        map[pkg] = ((map[pkg]?.toIntOrNull() ?: 0) + 1).toString()
        savePairs(context, "app_uses", map)
    }

    fun autoUpdateCheck(context: Context): Boolean = of(context).getBoolean("auto_update_check", true)

    fun setAutoUpdateCheck(context: Context, on: Boolean) {
        of(context).edit().putBoolean("auto_update_check", on).apply()
    }
}
