package com.tunastream.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** One app on the home screen. Apps made for TV have a banner; the rest show their icon. */
class AppEntry(
    val pkg: String,
    val label: String,
    val launch: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
    /** Made for TV, as opposed to a phone or tablet app that happens to be installed. */
    val tv: Boolean,
)

object Apps {
    /**
     * Every app that can be opened, one entry per app, sorted by name. Apps made for TV come
     * first so that an app with both a TV and a phone entry opens its TV one.
     * Slow (it loads pictures): call from a background thread.
     */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, AppEntry>()
        val categories = if (Prefs.showPhoneApps(context)) {
            arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        } else {
            arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        for (category in categories) {
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
                } catch (e: Exception) {
                    null
                }
                val banner: Drawable? = if (own != null) {
                    BitmapDrawable(context.resources, own)
                } else try {
                    activity.loadBanner(pm)
                } catch (e: Exception) {
                    null
                }
                val icon: Drawable? = if (banner != null) null else try {
                    info.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                found[pkg] = AppEntry(
                    pkg, label, launch, banner, icon,
                    category == Intent.CATEGORY_LEANBACK_LAUNCHER
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }
}
