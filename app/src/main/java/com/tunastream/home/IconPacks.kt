package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Icon packs made for phone launchers (the common ADW / Nova format): an app on the TV whose
 * appfilter.xml maps each app to a picture of its own.
 */
object IconPacks {
    private const val KEY = "icon_pack"

    private val QUERIES = listOf(
        Intent("org.adw.launcher.THEMES"),
        Intent("com.novalauncher.THEME"),
        Intent("com.gau.go.launcherex.theme"),
        Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
        Intent(Intent.ACTION_MAIN).addCategory("com.teslacoilsw.launcher.THEME"),
        Intent(Intent.ACTION_MAIN).addCategory("com.fede.launcher.THEME_ICONPACK"),
    )

    class Pack(val pkg: String, val name: String)

    /**
     * Icon packs are drawn for phone apps. Many TV apps are separate apps with their own
     * package names, so look for the phone version's icon too.
     */
    private val TV_TO_PHONE = mapOf(
        "com.netflix.ninja" to "com.netflix.mediaclient",
        "com.google.android.youtube.tv" to "com.google.android.youtube",
        "com.google.android.youtube.tvmusic" to "com.google.android.apps.youtube.music",
        "com.google.android.youtube.tvkids" to "com.google.android.apps.youtube.kids",
        "com.amazon.amazonvideo.livingroom" to "com.amazon.avod.thirdpartyclient",
        "com.hulu.livingroomplus" to "com.hulu.plus",
        "com.spotify.tv.android" to "com.spotify.music",
        "com.cbs.ott" to "com.cbs.app",
        "com.android.tv.settings" to "com.android.settings",
        "com.google.android.katniss" to "com.google.android.googlequicksearchbox",
    )

    /** How many apps the chosen pack had an icon for, the last time apps were loaded. */
    @Volatile
    var covered: Pair<Int, Int>? = null

    /** Lower case letters and digits only, so "Prime Video" and "prime_video" match. */
    fun plain(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    fun installed(context: Context): List<Pack> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pack>()
        for (query in QUERIES) {
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (!found.containsKey(pkg)) {
                    found[pkg] = Pack(pkg, info.loadLabel(pm).toString())
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    /** The chosen pack's package name, or null for the apps' own icons. */
    fun chosen(context: Context): String? = Prefs.of(context).getString(KEY, null)

    fun choose(context: Context, pkg: String?) {
        Prefs.of(context).edit().putString(KEY, pkg).apply()
        Look.touch(context)
    }

    /** One pack, read once: which picture goes with which app. */
    class Loaded(private val context: Context, private val pkg: String) {
        private val byComponent = HashMap<String, String>()
        private val byPackage = HashMap<String, String>()
        /** Every drawable the pack names, by its plain name, for matching on an app's name. */
        private val byName = HashMap<String, String>()
        private val res = try {
            context.packageManager.getResourcesForApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        init {
            try {
                read()
            } catch (e: Exception) {
                // A pack we can't read just gives no icons
            }
        }

        private fun read() {
            val res = res ?: return
            val id = res.getIdentifier("appfilter", "xml", pkg)
            val parser: XmlPullParser = if (id != 0) {
                res.getXml(id)
            } else {
                val stream = context.createPackageContext(pkg, 0).assets.open("appfilter.xml")
                XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, null) }
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (component != null && drawable != null) {
                        // "ComponentInfo{com.example/com.example.MainActivity}"
                        val inside = component.substringAfter('{', "").substringBefore('}', "")
                        if (inside.contains('/')) {
                            val app = inside.substringBefore('/')
                            var cls = inside.substringAfter('/')
                            if (cls.startsWith(".")) {
                                cls = app + cls
                            }
                            byComponent["$app/$cls"] = drawable
                            byName[plain(drawable)] = drawable
                            if (!byPackage.containsKey(app)) {
                                byPackage[app] = drawable
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }

        /**
         * The pack's icon for an app: by its exact activity, then by its package, then by the
         * phone version of a TV app, then by a drawable named the same as the app.
         */
        fun icon(appPkg: String, cls: String, label: String): Drawable? {
            val res = res ?: return null
            val phone = TV_TO_PHONE[appPkg]
            val name = byComponent["$appPkg/$cls"]
                ?: byPackage[appPkg]
                ?: phone?.let { byPackage[it] }
                ?: plain(label).takeIf { it.length >= 3 }?.let { byName[it] }
                ?: return null
            val id = res.getIdentifier(name, "drawable", pkg)
            if (id == 0) {
                return null
            }
            return try {
                res.getDrawable(id, null)
            } catch (e: Exception) {
                null
            }
        }
    }
}
