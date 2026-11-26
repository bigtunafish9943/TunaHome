package com.tunastream.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri

/**
 * Your PC's games, as TunaReceive lists them (it shares the list for home screens). Selecting one
 * starts it in TunaReceive, just as its own games list would. Nothing shows without TunaReceive,
 * or before it has listed a PC's games at least once.
 */
object PcGames {
    const val RECEIVER = "com.tunastream.receive"
    const val PREFIX = "pcgame:"
    private val LIST = Uri.parse("content://games.$RECEIVER/games")
    private const val MAX = 40

    /** Box art already loaded, by address: the list is read again every few minutes. */
    private val posters = android.util.LruCache<String, Drawable>(MAX + 10)

    /** Slow: call from a background thread. */
    fun load(context: Context): List<AppEntry> {
        val found = ArrayList<AppEntry>()
        val cursor = try {
            context.contentResolver.query(LIST, null, null, null, null)
        } catch (e: Exception) {
            null
        } ?: return found
        cursor.use { c ->
            fun text(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
            fun number(name: String): Long = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0
            while (c.moveToNext() && found.size < MAX) {
                val uuid = text("pc_uuid") ?: continue
                val appId = number("app_id")
                val name = text("app_name") ?: continue
                val launch = Intent(Intent.ACTION_DEFAULT)
                    .setComponent(ComponentName(RECEIVER, "com.limelight.ShortcutTrampoline"))
                    .putExtra("Name", text("pc_name"))
                    .putExtra("UUID", uuid)
                    .putExtra("AppName", name)
                    .putExtra("AppUUID", text("app_uuid"))
                    .putExtra("AppId", appId.toString())
                    .putExtra("HDR", number("hdr") != 0L)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val poster = text("poster")?.let { picture(context, it) }
                found.add(
                    AppEntry(
                        pkg = "$PREFIX$uuid/$appId",
                        label = name,
                        launch = launch,
                        banner = poster,
                        icon = poster,
                        tv = true,
                        own = poster,
                        appPkg = RECEIVER,
                        group = Apps.GAME,
                        colour = IconShape.mainColour(poster),
                    )
                )
            }
        }
        return found
    }

    /** A game's box art, no bigger than a card needs. */
    private fun picture(context: Context, address: String): Drawable? =
        posters.get(address) ?: loadPoster(context, address)?.also { posters.put(address, it) }

    private fun loadPoster(context: Context, address: String): Drawable? = try {
        val uri = Uri.parse(address)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 480) {
            sample *= 2
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.let { BitmapDrawable(context.resources, it) }
    } catch (e: Exception) {
        null
    }
}
