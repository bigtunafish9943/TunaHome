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
) {
    val isWatchNext: Boolean
        get() = pkg.startsWith("watchnext:")
}

object Apps {
    /** The last list loaded, for screens that want it straight away (search). */
    @Volatile
    var cached: List<AppEntry> = emptyList()

    /**
     * Every app that can be opened, one entry per app, sorted by name. Apps made for TV come
     * first so that an app with both a TV and a phone entry opens its TV one.
     * Slow (it loads pictures): call from a background thread.
     */
    fun load(context: Context, accent: Int = android.graphics.Color.WHITE): List<AppEntry> {
        val pm = context.packageManager
        val pack = IconPacks.chosen(context)?.let { IconPacks.load(context, it) }
        val missing = Look.PACK_MISSING.get(context)
        val found = LinkedHashMap<String, AppEntry>()
        var packHits = 0
        val fullBleed = Look.fullBleed(context)
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
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                val fromPack = pack?.icon(pkg, activity.name, label)
                if (fromPack != null) {
                    packHits++
                }
                var icon: Drawable? = fromPack ?: try {
                    info.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                if (pack != null && fromPack == null) {
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
                found[pkg] = AppEntry(
                    pkg, label, launch, banner, icon,
                    category == Intent.CATEGORY_LEANBACK_LAUNCHER,
                    own?.let { BitmapDrawable(context.resources, it) },
                )
            }
        }
        IconPacks.covered = if (pack != null) packHits to found.size else null
        return found.values.sortedBy { it.label.lowercase() }.also { cached = it }
    }
}
