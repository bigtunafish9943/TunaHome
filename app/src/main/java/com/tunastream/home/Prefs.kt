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
}
