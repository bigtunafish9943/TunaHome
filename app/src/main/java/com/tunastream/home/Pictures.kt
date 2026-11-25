package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * The viewer's own pictures: a wallpaper behind the home screen, and a replacement picture for
 * any app. TunaHome keeps its own shrunk copies, so the originals can be moved or deleted.
 */
object Pictures {
    /** Asks the TV for an app that can choose a picture; there may be none. */
    fun pickerIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)

    // ------------------------------------------------------------------ wallpaper

    private fun wallpaperFile(context: Context) = File(context.filesDir, "wallpaper.jpg")

    /** Slow: call from a background thread. */
    fun saveWallpaper(context: Context, uri: Uri): Boolean {
        val ok = saveShrunk(context, uri, wallpaperFile(context), 1920, 1080, Bitmap.CompressFormat.JPEG)
        if (ok) {
            Prefs.setWallpaperStamp(context, System.currentTimeMillis())
        }
        return ok
    }

    fun removeWallpaper(context: Context) {
        wallpaperFile(context).delete()
        Prefs.setWallpaperStamp(context, 0)
    }

    fun hasWallpaper(context: Context): Boolean =
        Prefs.wallpaperStamp(context) != 0L && wallpaperFile(context).exists()

    /** The saved wallpaper, or null when there is none. Slow: call from a background thread. */
    fun loadWallpaper(context: Context): Bitmap? =
        if (hasWallpaper(context)) BitmapFactory.decodeFile(wallpaperFile(context).path) else null

    // ------------------------------------------------------------------ an app's own picture

    private fun tileFile(context: Context, pkg: String): File =
        File(File(context.filesDir, "tiles"), "$pkg.png")

    /** Slow: call from a background thread. */
    fun saveTile(context: Context, pkg: String, uri: Uri): Boolean {
        val file = tileFile(context, pkg)
        file.parentFile?.mkdirs()
        return saveShrunk(context, uri, file, 640, 360, Bitmap.CompressFormat.PNG)
    }

    fun removeTile(context: Context, pkg: String) {
        tileFile(context, pkg).delete()
    }

    fun hasTile(context: Context, pkg: String): Boolean = tileFile(context, pkg).exists()

    /** Slow: call from a background thread. */
    fun loadTile(context: Context, pkg: String): Bitmap? {
        val file = tileFile(context, pkg)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    // ------------------------------------------------------------------ shared

    /** Copies a chosen picture to [target], scaled down so it covers the given size and no more. */
    private fun saveShrunk(
        context: Context,
        uri: Uri,
        target: File,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): Boolean {
        val resolver = context.contentResolver

        // First pass: only the picture's size
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val sizing = resolver.openInputStream(uri) ?: return false
        sizing.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return false
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val reading = resolver.openInputStream(uri) ?: return false
        var bitmap = reading.use { BitmapFactory.decodeStream(it, null, options) } ?: return false

        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, Math.round(bitmap.width * scale)),
                maxOf(1, Math.round(bitmap.height * scale)),
                true
            )
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        val temp = File(target.path + ".tmp")
        FileOutputStream(temp).use { bitmap.compress(format, 92, it) }
        bitmap.recycle()
        target.delete()
        return temp.renameTo(target)
    }
}
