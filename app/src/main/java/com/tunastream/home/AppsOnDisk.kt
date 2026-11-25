package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A copy of the app list, pictures and all, kept on the TV's storage so the home screen fills in
 * at once after the TV restarts, instead of waiting for every app (and icon pack) to load again.
 * The real list loads straight after and replaces it. Thrown away if the look has changed.
 */
object AppsOnDisk {
    private const val VERSION = 1

    private fun dir(context: Context) = File(context.cacheDir, "apps")

    /** What the list was made with: a different theme or look can't use it. */
    private fun look(context: Context) = "${Look.stamp(context)}/${TunaTheme.current(context)}"

    /** Something that changes whenever the list or its pictures would. */
    private fun signature(context: Context, apps: List<AppEntry>): String =
        look(context) + "|" + apps.joinToString(",") { "${it.pkg}:${it.label}:${it.updated}:${it.colour}" }.hashCode()

    /** Slow: call from a background thread. */
    fun save(context: Context, apps: List<AppEntry>) {
        try {
            val sign = signature(context, apps)
            val index = File(dir(context), "index.json")
            if (index.exists() && JSONObject(index.readText()).optString("sign") == sign) {
                return
            }
            val fresh = File(context.cacheDir, "apps.new")
            fresh.deleteRecursively()
            fresh.mkdirs()
            val list = JSONArray()
            apps.forEachIndexed { i, app ->
                val o = JSONObject()
                    .put("pkg", app.pkg)
                    .put("label", app.label)
                    .put("launch", app.launch.toUri(Intent.URI_INTENT_SCHEME))
                    .put("tv", app.tv)
                    .put("appPkg", app.appPkg)
                    .put("installed", app.installed)
                    .put("updated", app.updated)
                    .put("sideloaded", app.sideloaded)
                    .put("group", app.group)
                    .put("colour", app.colour)
                    .put("banner", write(app.banner, File(fresh, "$i.b.png"), 640))
                    .put("icon", write(app.icon, File(fresh, "$i.i.png"), 256))
                    .put("own", write(app.own, File(fresh, "$i.o.png"), 640))
                list.put(o)
            }
            File(fresh, "index.json").writeText(
                JSONObject().put("version", VERSION).put("look", look(context)).put("sign", sign).put("apps", list).toString()
            )
            val old = dir(context)
            old.deleteRecursively()
            fresh.renameTo(old)
        } catch (e: Throwable) {
            // Kept for next time
        }
    }

    /** The kept list, or null if there isn't one this look can use. Slow: background thread. */
    fun read(context: Context): List<AppEntry>? {
        return try {
            val folder = dir(context)
            val index = JSONObject(File(folder, "index.json").readText())
            if (index.optInt("version") != VERSION || index.optString("look") != look(context)) {
                return null
            }
            val list = index.getJSONArray("apps")
            val found = ArrayList<AppEntry>()
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                fun picture(name: String, key: String): Drawable? =
                    if (o.optBoolean(key)) BitmapFactory.decodeFile(File(folder, name).path)
                        ?.let { BitmapDrawable(context.resources, it) } else null
                val launch = try {
                    Intent.parseUri(o.getString("launch"), Intent.URI_INTENT_SCHEME)
                } catch (e: Exception) {
                    continue
                }
                found.add(
                    AppEntry(
                        pkg = o.getString("pkg"),
                        label = o.optString("label"),
                        launch = launch,
                        banner = picture("$i.b.png", "banner"),
                        icon = picture("$i.i.png", "icon"),
                        tv = o.optBoolean("tv"),
                        own = picture("$i.o.png", "own"),
                        appPkg = o.optString("appPkg", o.getString("pkg")),
                        installed = o.optLong("installed"),
                        updated = o.optLong("updated"),
                        sideloaded = o.optBoolean("sideloaded"),
                        group = o.optInt("group"),
                        colour = o.optInt("colour"),
                    )
                )
            }
            found.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            null
        }
    }

    /** Saves a picture no bigger than [max] across; false if there's none. */
    private fun write(picture: Drawable?, file: File, max: Int): Boolean {
        picture ?: return false
        val bitmap = if (picture is BitmapDrawable && picture.bitmap != null) {
            val b = picture.bitmap
            if (b.width > max) Bitmap.createScaledBitmap(b, max, maxOf(1, b.height * max / b.width), true) else b
        } else {
            var w = picture.intrinsicWidth.takeIf { it > 0 } ?: 192
            var h = picture.intrinsicHeight.takeIf { it > 0 } ?: 192
            if (w > max) {
                h = maxOf(1, h * max / w)
                w = max
            }
            val made = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val copy = picture.constantState?.newDrawable()?.mutate() ?: picture
            copy.setBounds(0, 0, w, h)
            copy.draw(Canvas(made))
            made
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }
}
