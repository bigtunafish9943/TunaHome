package com.tunastream.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import kotlin.concurrent.thread

/**
 * Wallpaper apps: apps that hand a launcher a list of videos and pictures to show behind it,
 * through the plugin connection Projectivy Launcher published. Aerial Views is one.
 *
 * TunaHome asks the chosen app for its list, keeps it for as long as the app says to, then asks
 * again. The videos and pictures themselves are played by [Backdrop].
 */
object WallpaperApps {
    private const val TAG = "TunaHome"
    const val ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER"
    const val AERIAL_VIEWS = "com.neilturner.aerialviews"
    private const val DEFAULT_KEEP_MS = 30L * 60 * 1000
    private const val TIMEOUT_MS = 30_000L

    class Source(
        val component: ComponentName,
        val name: String,
        /** The app's own settings screen, if it has one. */
        val settings: ComponentName?,
        /** How long the app says its list stays good for. */
        val keepMs: Long,
    )

    /** Every installed wallpaper app, by name. */
    fun installed(context: Context): List<Source> {
        val pm = context.packageManager
        val found = ArrayList<Source>()
        for (info in pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)) {
            val service = info.serviceInfo ?: continue
            val meta = service.metaData
            val name = meta?.get("name")?.let { value ->
                when (value) {
                    is String -> value
                    is Int -> try {
                        pm.getResourcesForApplication(service.packageName).getString(value)
                    } catch (e: Exception) {
                        null
                    }
                    else -> null
                }
            } ?: info.loadLabel(pm).toString()

            val settingsClass = meta?.getString("settingsActivity") ?: meta?.getString("mainActivity")
            val settings = settingsClass?.let {
                ComponentName(service.packageName, if (it.startsWith(".")) service.packageName + it else it)
            }
            val keep = (meta?.get("itemsCacheDurationMillis") as? Int)?.toLong()?.takeIf { it > 0 }
                ?: DEFAULT_KEEP_MS
            found.add(Source(ComponentName(service.packageName, service.name), name, settings, keep))
        }
        return found.sortedBy { it.name.lowercase() }
    }

    fun find(context: Context, component: ComponentName?): Source? =
        component?.let { c -> installed(context).firstOrNull { it.component == c } }

    /** Opens the wallpaper app's own settings, where you pick which videos it offers. */
    fun openSettings(context: Context, source: Source): Boolean {
        val intents = ArrayList<Intent>()
        source.settings?.let { intents.add(Intent().setComponent(it)) }
        context.packageManager.getLeanbackLaunchIntentForPackage(source.component.packageName)
            ?.let { intents.add(it) }
        context.packageManager.getLaunchIntentForPackage(source.component.packageName)
            ?.let { intents.add(it) }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // Try the next way in
            }
        }
        return false
    }

    // ------------------------------------------------------------------ asking for the list

    private class Kept(val at: Long, val items: List<Wallpaper>)

    private val kept = HashMap<ComponentName, Kept>()

    /** Forgets every kept list, so the next showing asks the app again. */
    fun forget() {
        kept.clear()
    }

    /**
     * Gets [source]'s list, from what was kept if it is still good, otherwise by asking the app.
     * [done] runs on the main thread, with an empty list if the app gave nothing or didn't answer.
     */
    fun fetch(context: Context, source: Source, done: (List<Wallpaper>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        kept[source.component]?.let {
            if (SystemClock.elapsedRealtime() - it.at < source.keepMs && it.items.isNotEmpty()) {
                main.post { done(it.items) }
                return
            }
        }

        val app = context.applicationContext
        var finished = false
        lateinit var connection: ServiceConnection

        fun finish(items: List<Wallpaper>) {
            main.post {
                if (finished) {
                    return@post
                }
                finished = true
                try {
                    app.unbindService(connection)
                } catch (e: Exception) {
                    // Already gone
                }
                if (items.isNotEmpty()) {
                    kept[source.component] = Kept(SystemClock.elapsedRealtime(), items)
                }
                done(items)
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                // The app may go to the internet for its list: never on the main thread
                thread(name = "TunaHome wallpaper app") {
                    val items = try {
                        IWallpaperProviderService.Stub.asInterface(binder)
                            .getWallpapers(Event.TimeElapsed)
                            ?.filterNotNull()
                            ?: emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "Wallpaper app ${source.name} failed: $e")
                        emptyList()
                    }
                    finish(items)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(emptyList())
            }
        }

        val bound = try {
            app.bindService(Intent(ACTION).setComponent(source.component), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't reach wallpaper app ${source.name}: $e")
            false
        }
        if (!bound) {
            finish(emptyList())
            return
        }
        main.postDelayed({ finish(emptyList()) }, TIMEOUT_MS)
    }
}
