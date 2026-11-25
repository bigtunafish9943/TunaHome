package com.tunastream.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** One app on the home screen. */
class AppEntry(
    val pkg: String,
    val label: String,
    val launch: Intent,
    /** Its wide TV picture; null when it has none. */
    val banner: Drawable?,
    /** Its icon, from the chosen icon pack when that has one. */
    val icon: Drawable?,
    /** Made for TV, as opposed to a phone or tablet app that happens to be installed. */
    val tv: Boolean,
    /** A picture the viewer chose for this app; shown instead of its own in every style. */
    val own: Drawable? = null,
    /** The app it belongs to: itself, or for a Watch Next card the app that plays it. */
    val appPkg: String = pkg,
    /** How far through a Watch Next show you are, 0 to 1; below 0 for none. */
    val progress: Float = -1f,
    /** When it was first installed and last updated, for sorting a section. */
    val installed: Long = 0,
    val updated: Long = 0,
    /** Installed from a file or another app rather than from the Play Store. */
    val sideloaded: Boolean = false,
    /** What sort of app it is: one of [Apps.GAME], [Apps.VIDEO], [Apps.MUSIC], or [Apps.OTHER]. */
    val group: Int = Apps.OTHER,
    /** The main colour of its picture, for a glow in its own colour; 0 for none. */
    val colour: Int = 0,
) {
    val isWatchNext: Boolean
        get() = pkg.startsWith("watchnext:")

    val isPcGame: Boolean
        get() = pkg.startsWith(PcGames.PREFIX)

    /** Something to play inside another app (a show, a PC game), rather than an app itself. */
    val isInsideApp: Boolean
        get() = isWatchNext || isPcGame

    val isLink: Boolean
        get() = pkg.startsWith(Links.PREFIX)

    val isFolder: Boolean
        get() = pkg.startsWith(Folders.PREFIX)
}

object Apps {
    const val OTHER = 0
    const val GAME = 1
    const val VIDEO = 2
    const val MUSIC = 3

    // For apps that don't say what they are: well-known names
    private val GAME_WORDS = listOf(
        "game", "geforcenow", "tegrazone", "steamlink", "limelight", "moonlight", "retroarch", "dolphinemu",
        "ppsspp", "xcloud", "gamepass", "emulator", "parsec", "rainway", "chiaki", "lemuroid",
    )
    private val VIDEO_WORDS = listOf(
        "netflix", "youtube.tv", "youtube.tvkids", "tvunplugged", "plex", "kodi", "xbmc", "disney", "hulu",
        "amazonvideo", "primevideo", "wbd", "hbo", "paramount", "cbs", "peacock", "twitch", "jellyfin", "emby",
        "stremio", "videolan", "smarttube", "tivimate", "crunchyroll", "pluto", "tubitv", "apple.atve",
        "espn", "sling", "mxtech", "fubo", "philo", "vudu", "starz", "showtime", "britbox", "curiosity",
        "dazn", "mubi", "criterion", "vimeo", "dailymotion", "nebula", "dropout", "iplayer", "channel4",
    )
    private val MUSIC_WORDS = listOf(
        "spotify", "pandora", "tidal", "tvmusic", "amazonmp3", "amazon.music", "plexamp", "soundcloud",
        "deezer", "iheart", "sirius", "apple.android.music", "audible", "qobuz", "tunein", "napster", "shazam",
        "musicolet", "poweramp", "radio", "podcast",
    )

    /** What sort of app [pkg] is, from what it says about itself or else its name. */
    private fun groupOf(info: android.content.pm.ApplicationInfo?, pkg: String): Int {
        if (info != null) {
            @Suppress("DEPRECATION")
            if (info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0) {
                return GAME
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                when (info.category) {
                    android.content.pm.ApplicationInfo.CATEGORY_GAME -> return GAME
                    android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> return VIDEO
                    android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> return MUSIC
                }
            }
        }
        val name = pkg.lowercase()
        return when {
            MUSIC_WORDS.any { it in name } -> MUSIC
            GAME_WORDS.any { it in name } -> GAME
            VIDEO_WORDS.any { it in name } -> VIDEO
            else -> OTHER
        }
    }

    /** App stores: an app installed by anything else was sideloaded. */
    private val STORES = setOf("com.android.vending", "com.amazon.venezia", "com.google.android.feedback")

    private fun installer(pm: android.content.pm.PackageManager, pkg: String): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }

    /** The last list loaded, for screens that want it straight away (home, search). */
    @Volatile
    var cached: List<AppEntry> = emptyList()

    /** The look the cached list was loaded with, and when: a different look or an old list loads again. */
    @Volatile
    var cachedStamp = -1
    @Volatile
    var cachedAt = 0L

    /** Whether the kept list can be shown as it is, without loading everything again. */
    fun cacheFresh(context: Context): Boolean =
        cached.isNotEmpty() && cachedStamp == Look.stamp(context) &&
            android.os.SystemClock.elapsedRealtime() - cachedAt < 10 * 60 * 1000L

    /** Lets go of the kept list (the TV is short of memory). */
    fun forget() {
        cached = emptyList()
        cachedStamp = -1
    }

    /**
     * Every app that can be opened, one entry per app, sorted by name. Apps made for TV come
     * first so that an app with both a TV and a phone entry opens its TV one.
     * Slow (it loads pictures): call from a background thread.
     */
    fun load(context: Context, accent: Int = android.graphics.Color.WHITE): List<AppEntry> {
        val stampAtStart = Look.stamp(context)
        val pm = context.packageManager
        val pack = IconPacks.chosen(context)?.let { IconPacks.load(context, it) }
        val missing = Look.PACK_MISSING.get(context)
        val found = LinkedHashMap<String, AppEntry>()
        var packHits = 0
        // Edge-to-edge icons, also when only some sections show icons
        val fullBleed = Look.fullBleed(context) || (
            Look.ICON_FILL.get(context) == Look.FILL_EDGE && Look.ICON_TINT.get(context) == Look.TINT_NONE &&
                Rows.load(context).any { it.style == Look.TILE_ICON || it.style == Look.TILE_ICON_NAME })
        val tint = Look.iconTint(context, accent)
        val names = Prefs.appNames(context)
        val wide = Look.TILE_STYLE.get(context) == Look.TILE_BANNER
        for (category in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) {
                    continue
                }
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(ComponentName(pkg, activity.name))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                // A picture the viewer chose for this app wins over the app's own
                val own = try {
                    Pictures.loadTile(context, pkg)
                } catch (e: Throwable) {
                    null
                }
                val banner: Drawable? = try {
                    activity.loadBanner(pm)
                } catch (e: Exception) {
                    null
                }
                val ownLabel = try {
                    info.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                // A name the viewer gave it wins; the pack still looks it up by its own name
                val label = names[pkg] ?: ownLabel
                val fromPack = pack?.icon(pkg, activity.name, ownLabel)
                if (fromPack != null) {
                    packHits++
                }
                var icon: Drawable? = fromPack ?: try {
                    info.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                if (tint != null) {
                    // Every icon in one colour: a pack's drawing keeps its shape, an app's own
                    // icon becomes line art
                    icon = if (fromPack != null) IconShape.tinted(context.resources, icon, tint)
                    else IconShape.outline(context.resources, icon, tint)
                } else if (pack != null && fromPack == null) {
                    // An app the pack doesn't have: dressed to match it, as the viewer chose
                    icon = when (missing) {
                        Look.MISSING_FRAME -> pack.framed(pkg, icon) ?: icon
                        Look.MISSING_WHITE -> IconShape.outline(context.resources, icon, android.graphics.Color.WHITE)
                        Look.MISSING_ACCENT -> IconShape.outline(context.resources, icon, accent)
                        else -> icon
                    }
                }
                if (fullBleed && (fromPack != null || pack == null || missing == Look.MISSING_OWN)) {
                    icon = IconShape.fullBleed(context.resources, icon)
                }
                val app = activity.applicationInfo
                val system = app != null && app.flags and
                    (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val times = try {
                    pm.getPackageInfo(pkg, 0).let { it.firstInstallTime to it.lastUpdateTime }
                } catch (e: Exception) {
                    0L to 0L
                }
                found[pkg] = AppEntry(
                    pkg, label, launch, banner, icon,
                    category == Intent.CATEGORY_LEANBACK_LAUNCHER,
                    own?.let { BitmapDrawable(context.resources, it) },
                    installed = times.first,
                    updated = times.second,
                    sideloaded = !system && (installer(pm, pkg) ?: "") !in STORES,
                    group = groupOf(app, pkg),
                    colour = IconShape.mainColour(own?.let { BitmapDrawable(context.resources, it) }
                        ?: if (wide) banner ?: icon else icon),
                )
            }
        }
        IconPacks.covered = if (pack != null) packHits to found.size else null
        // The viewer's own links, after the count of apps the pack covers
        try {
            Links.entries(context, names).forEach { found[it.pkg] = it }
        } catch (e: Exception) {
            // Left out
        }
        return found.values.sortedBy { it.label.lowercase() }.also {
            cached = it
            cachedStamp = stampAtStart
            cachedAt = android.os.SystemClock.elapsedRealtime()
        }
    }
}
