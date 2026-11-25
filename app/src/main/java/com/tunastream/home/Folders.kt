package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folders: a card in a section that opens to a small grid of apps. An app in a folder shows
 * there instead of in its section. Deleting a folder puts its apps back.
 */
object Folders {
    private const val KEY = "folders"
    const val PREFIX = "folder:"

    class Folder(val id: String, var name: String, val apps: MutableList<String>)

    fun load(context: Context): MutableList<Folder> {
        val text = Prefs.of(context).getString(KEY, null) ?: return ArrayList()
        return try {
            val a = JSONArray(text)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val apps = o.getJSONArray("apps").let { x -> (0 until x.length()).map { x.getString(it) }.toMutableList() }
                Folder(o.getString("id"), o.getString("name"), apps)
            }.toMutableList()
        } catch (e: Exception) {
            ArrayList()
        }
    }

    fun save(context: Context, list: List<Folder>) {
        val a = JSONArray()
        list.forEach { f -> a.put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps))) }
        Prefs.of(context).edit().putString(KEY, a.toString()).apply()
    }

    fun update(context: Context, id: String, change: (Folder) -> Unit) {
        val list = load(context)
        list.firstOrNull { it.id == id }?.let(change)
        // A folder left with nothing in it goes away
        save(context, list.filter { it.apps.isNotEmpty() })
    }

    fun create(context: Context, name: String, firstApp: String): Folder {
        val list = load(context)
        list.forEach { it.apps.remove(firstApp) }
        val folder = Folder("f" + System.currentTimeMillis(), name, mutableListOf(firstApp))
        list.add(folder)
        save(context, list.filter { it.apps.isNotEmpty() })
        return folder
    }

    fun key(id: String) = PREFIX + id

    /** The folder's card: up to four of its apps' icons in a square. */
    fun entry(context: Context, folder: Folder, apps: Map<String, AppEntry>): AppEntry {
        val icons = folder.apps.mapNotNull { apps[it]?.let { a -> a.own ?: a.icon } }.take(4)
        return AppEntry(
            pkg = key(folder.id),
            label = folder.name,
            launch = Intent(),
            banner = null,
            icon = grid(context, icons),
            tv = true,
        )
    }

    private fun grid(context: Context, icons: List<Drawable>): Drawable {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cell = size / 2
        val pad = size / 16
        icons.forEachIndexed { i, icon ->
            val x = (i % 2) * cell
            val y = (i / 2) * cell
            val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
            copy.setBounds(x + pad, y + pad, x + cell - pad, y + cell - pad)
            copy.draw(canvas)
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
