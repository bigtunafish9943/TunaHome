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
        Intent("org.adw.ActivityStarter.THEMES"),
        Intent("com.dlto.atom.launcher.THEME"),
        Intent("ch.deletescape.lawnchair.ICONPACK"),
        Intent("app.lawnchair.icons.THEMED_ICON"),
        Intent("net.oneplus.launcher.icons.ACTION_PICK_ICON"),
        Intent("com.novalauncher.category.CUSTOM_ICON_PICKER"),
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
        "com.google.android.youtube.tvunplugged" to "com.google.android.apps.youtube.unplugged",
        "com.amazon.music.tv" to "com.amazon.mp3",
        "com.apple.atve.androidtv.appletv" to "com.apple.atve.android.appletv",
        "com.google.android.tvrecommendations" to "com.google.android.googlequicksearchbox",
        "com.nvidia.tegrazone3" to "com.nvidia.geforcenow",
    )

    /** Words too common to stand for one app on their own. */
    private val GENERIC = setOf(
        "video", "videos", "music", "player", "plus", "live", "home", "settings", "games", "game",
        "tvapp", "app", "apps", "android", "google", "amazon", "files", "store", "media", "stream",
        "streaming", "remote", "launcher", "browser", "kids", "news", "sports", "movies", "photos",
    )

    /** Endings TV apps add to their names, which pack drawings don't have. */
    private val TV_WORDS = listOf("androidtv", "fortv", "fortvs", "tv", "livingroom", "leanback")

    private fun withoutTvWords(name: String): String {
        var n = name
        for (w in TV_WORDS) {
            if (n.length > w.length + 2 && n.endsWith(w)) {
                n = n.removeSuffix(w)
            }
        }
        return n
    }

    // ------------------------------------------------------------------ choosing by hand

    private fun pickedKey(pack: String, app: String) = "pack_pick_${pack}_$app"

    /** The drawing the viewer picked from [pack] for [app], if they picked one. */
    fun picked(context: Context, pack: String, app: String): String? =
        Prefs.of(context).getString(pickedKey(pack, app), null)

    fun pick(context: Context, pack: String, app: String, drawable: String?) {
        Prefs.of(context).edit().putString(pickedKey(pack, app), drawable).apply()
        Prefs.appsChanged = true
    }

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
        // Packs fetched through TunaHome, even if they don't announce themselves as above
        for (offer in IconPackStore.CATALOGUE) {
            if (!found.containsKey(offer.pkg) && IconPackStore.isInstalled(context, offer.pkg)) {
                found[offer.pkg] = Pack(offer.pkg, offer.name)
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    /**
     * The one Icons choice: TV pictures, the apps' own icons, or an icon pack. TV pictures are
     * just another option in the same list, not a separate setting.
     */
    fun useTvPictures(context: Context) {
        choose(context, null)
        Look.TILE_STYLE.set(context, Look.TILE_BANNER)
        Prefs.appsChanged = true
    }

    /** The apps' own icons ([pkg] null) or an icon pack's, keeping the name-in-card choice. */
    fun useIcons(context: Context, pkg: String?) {
        choose(context, pkg)
        if (Look.TILE_STYLE.get(context) == Look.TILE_BANNER) {
            Look.TILE_STYLE.set(context, Look.TILE_ICON)
        } else {
            Look.touch(context)
        }
        Prefs.appsChanged = true
    }

    /** What the Icons setting is now, by name. */
    fun currentName(context: Context): String {
        if (Look.TILE_STYLE.get(context) == Look.TILE_BANNER) {
            return context.getString(R.string.icons_tv_pictures)
        }
        val pkg = chosen(context) ?: return context.getString(R.string.icons_app_icons)
        return installed(context).firstOrNull { it.pkg == pkg }?.name ?: context.getString(R.string.icons_app_icons)
    }

    /** The chosen pack's package name, or null for the apps' own icons. */
    fun chosen(context: Context): String? = Prefs.of(context).getString(KEY, null)

    fun choose(context: Context, pkg: String?) {
        Prefs.of(context).edit().putString(KEY, pkg).apply()
        Look.touch(context)
    }

    /** One pack, read once: which picture goes with which app. */
    private val loadedPacks = HashMap<String, Pair<Long, Loaded>>()

    /**
     * The pack, read once and kept: reading a big pack's list again on every reload was slow,
     * and a reload could come back with fewer icons than the first time.
     */
    fun load(context: Context, pkg: String): Loaded {
        val updated = try {
            context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        synchronized(loadedPacks) {
            loadedPacks[pkg]?.let { if (it.first == updated && it.second.size > 0) return it.second }
            val fresh = Loaded(context.applicationContext, pkg)
            loadedPacks[pkg] = updated to fresh
            return fresh
        }
    }

    class Loaded(private val context: Context, private val pkg: String) {
        private val byComponent = HashMap<String, String>()
        private val byPackage = HashMap<String, String>()
        /** Every drawable the pack names, by its plain name, for matching on an app's name. */
        private val byName = HashMap<String, String>()

        /** The pack's own frame for icons it doesn't have, if it has one. */
        private val backs = ArrayList<String>()
        private var mask: String? = null
        private var upon: String? = null
        private var scale = 1f

        val hasFrame: Boolean
            get() = backs.isNotEmpty() || mask != null || upon != null

        /** How many apps the pack lists. */
        val size: Int
            get() = byComponent.size

        /** Every drawing in the pack, by name, for choosing by hand. */
        val names: List<String>
            get() = byName.values.distinct().sorted()

        private val res = try {
            context.packageManager.getResourcesForApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        init {
            // Many packs carry their list twice (res/xml and assets), and one copy is often
            // shorter or older: read both and keep everything from either
            val res = res
            if (res != null) {
                val id = res.getIdentifier("appfilter", "xml", pkg)
                if (id != 0) {
                    try {
                        read(res.getXml(id))
                    } catch (e: Exception) {
                        // Keep what was read
                    }
                }
            }
            try {
                context.createPackageContext(pkg, 0).assets.open("appfilter.xml").use { stream ->
                    read(XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, null) })
                }
            } catch (e: Exception) {
                // No copy in assets, or it stopped part way: keep what was read
            }
        }

        private fun read(parser: XmlPullParser) {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> item(parser)
                        "iconback" -> for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i).startsWith("img")) backs.add(parser.getAttributeValue(i))
                        }
                        "iconmask" -> mask = parser.getAttributeValue(null, "img1") ?: mask
                        "iconupon" -> upon = parser.getAttributeValue(null, "img1") ?: upon
                        "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: scale
                    }
                }
                event = parser.next()
            }
        }

        private fun item(parser: XmlPullParser) {
            val component = parser.getAttributeValue(null, "component") ?: return
            val drawable = parser.getAttributeValue(null, "drawable") ?: return
            // "ComponentInfo{com.example/com.example.MainActivity}"
            val inside = component.substringAfter('{', "").substringBefore('}', "")
            if (!inside.contains('/')) {
                return
            }
            val app = inside.substringBefore('/')
            var cls = inside.substringAfter('/')
            if (cls.startsWith(".")) {
                cls = app + cls
            }
            if (!byComponent.containsKey("$app/$cls")) {
                byComponent["$app/$cls"] = drawable
            }
            if (!byName.containsKey(plain(drawable))) {
                byName[plain(drawable)] = drawable
            }
            if (!byPackage.containsKey(app)) {
                byPackage[app] = drawable
            }
        }

        /**
         * An app the pack has no drawing for, in the pack's own frame: its background, the
         * app's icon shrunk onto it, cut by its mask, with its overlay on top.
         */
        fun framed(appPkg: String, icon: Drawable?): Drawable? {
            if (!hasFrame || icon == null || res == null) {
                return null
            }
            val size = 192
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            if (backs.isNotEmpty()) {
                drawable(backs[Math.abs(appPkg.hashCode()) % backs.size])?.let {
                    it.setBounds(0, 0, size, size)
                    it.draw(canvas)
                }
            }
            val inner = Math.round(size * scale)
            val offset = (size - inner) / 2
            val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
            copy.setBounds(offset, offset, offset + inner, offset + inner)
            copy.draw(canvas)
            mask?.let { name ->
                drawable(name)?.let { m ->
                    val maskBitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    m.setBounds(0, 0, size, size)
                    m.draw(android.graphics.Canvas(maskBitmap))
                    val paint = android.graphics.Paint()
                    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                    canvas.drawBitmap(maskBitmap, 0f, 0f, paint)
                }
            }
            upon?.let { name ->
                drawable(name)?.let {
                    it.setBounds(0, 0, size, size)
                    it.draw(canvas)
                }
            }
            return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
        }

        /**
         * The pack's icon for an app: by its exact activity, then by its package, then by the
         * phone version of a TV app, then by a drawable named the same as the app.
         */
        fun icon(appPkg: String, cls: String, label: String): Drawable? {
            res ?: return null
            val name = picked(context, pkg, appPkg) ?: match(appPkg, cls, label) ?: return null
            return drawable(name)
        }

        /** The drawing that fits an app best, without asking: exact first, then by name. */
        fun match(appPkg: String, cls: String, label: String): String? {
            val phone = TV_TO_PHONE[appPkg]
            byComponent["$appPkg/$cls"]?.let { return it }
            byPackage[appPkg]?.let { return it }
            phone?.let { byPackage[it] }?.let { return it }
            val plainLabel = plain(label)
            if (plainLabel.length < 3) {
                return null
            }
            byName[plainLabel]?.let { return it }
            // "Netflix" and a drawing "netflix_tv"; "YouTube for Android TV" and "youtube"
            val bare = withoutTvWords(plainLabel)
            byName[bare]?.let { return it }
            // Any run of the name's words: "Amazon Prime Video" finds "prime_video"
            val words = label.lowercase().split(' ', '-', ':', '.', '&', '+').map { plain(it) }.filter { it.isNotEmpty() }
            for (length in words.size - 1 downTo 1) {
                for (start in 0..words.size - length) {
                    val run = words.subList(start, start + length).joinToString("")
                    if (run.length >= 4 && run !in GENERIC) {
                        byName[run]?.let { return it }
                    }
                }
            }
            if (bare.length >= 4) {
                val starts = byName.keys.filter { withoutTvWords(it) == bare || it.startsWith(bare) }
                if (starts.size == 1) {
                    return byName[starts[0]]
                }
            }
            return null
        }

        /** Drawings whose names are closest to [text], best first, for the chooser. */
        fun suggest(text: String, limit: Int = 60): List<String> {
            val wanted = withoutTvWords(plain(text))
            if (wanted.isEmpty()) {
                return names.take(limit)
            }
            return byName.entries.mapNotNull { (plainName, name) ->
                val score = when {
                    plainName == wanted -> 0
                    plainName.startsWith(wanted) -> 1
                    wanted.startsWith(plainName) && plainName.length >= 3 -> 2
                    plainName.contains(wanted) -> 3
                    else -> return@mapNotNull null
                }
                score to name
            }.sortedWith(compareBy({ it.first }, { it.second })).map { it.second }.distinct().take(limit)
        }

        fun drawable(name: String): Drawable? {
            val res = res ?: return null
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
