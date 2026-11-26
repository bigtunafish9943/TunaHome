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

    // ------------------------------------------------------------------ any picture, anywhere

    /**
     * Reads a picture from the TV (content:// or file://) or the web (http, https), shrunk so it
     * covers [width] x [height] and no more. Slow: call from a background thread.
     */
    fun decode(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        val scheme = uri.scheme?.lowercase()
        val bytes: ByteArray? = if (scheme == "http" || scheme == "https") download(uri.toString()) else null

        fun open(): java.io.InputStream? =
            if (bytes != null) java.io.ByteArrayInputStream(bytes) else context.contentResolver.openInputStream(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val sizing = open() ?: return null
        sizing.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val reading = open() ?: return null
        return reading.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * A moving picture (an animated GIF or WebP) ready to play, or null if [uri] isn't one or the
     * TV is older than Android 9. Slow: call from a background thread.
     */
    fun animated(context: Context, uri: Uri, width: Int, height: Int): android.graphics.drawable.Drawable? {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            return null
        }
        val path = uri.toString().substringBefore('?').lowercase()
        val type = try {
            context.contentResolver.getType(uri)?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        val maybe = path.endsWith(".gif") || path.endsWith(".webp") || "gif" in type || "webp" in type
        if (!maybe) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        val source = if (scheme == "http" || scheme == "https") {
            val bytes = download(uri.toString()) ?: return null
            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        } else {
            android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        }
        val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            // No bigger than the screen: big GIFs are slow to play
            var sample = 1
            while (info.size.width / (sample * 2) >= width && info.size.height / (sample * 2) >= height) {
                sample *= 2
            }
            if (sample > 1) {
                decoder.setTargetSampleSize(sample)
            }
        }
        return if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
            drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
            drawable
        } else {
            null
        }
    }

    /**
     * A soft, blurred copy of a picture: shrunk a lot, then smoothed. The screen scales it back
     * up, which blurs it further. [strength] is 1 to 3.
     */
    fun blur(picture: Bitmap, strength: Int): Bitmap {
        val shrink = when (strength) {
            1 -> 6
            2 -> 10
            else -> 16
        }
        val w = maxOf(8, picture.width / shrink)
        val h = maxOf(8, picture.height / shrink)
        val small = Bitmap.createScaledBitmap(picture, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        // Three box blurs one after another look much like a proper (Gaussian) blur
        repeat(3) {
            boxBlur(px, w, h, 2, horizontal = true)
            boxBlur(px, w, h, 2, horizontal = false)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val line = IntArray(length)
        for (l in 0 until lines) {
            for (i in 0 until length) {
                line[i] = px[if (horizontal) l * w + i else i * w + l]
            }
            for (i in 0 until length) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in -radius..radius) {
                    val p = line[(i + k).coerceIn(0, length - 1)]
                    r += p shr 16 and 0xFF
                    g += p shr 8 and 0xFF
                    b += p and 0xFF
                    n++
                }
                px[if (horizontal) l * w + i else i * w + l] =
                    (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private const val MAX_DOWNLOAD = 25 * 1024 * 1024

    private fun download(address: String): ByteArray? {
        val conn = java.net.URL(address).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TunaHome/" + BuildConfig.VERSION_NAME)
        try {
            if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                return null
            }
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) {
                        break
                    }
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD) {
                        return null
                    }
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ your own videos

    private fun videoDir(context: Context) = File(context.filesDir, "videos")

    /**
     * Copies a chosen video into TunaHome, for TVs whose file chooser can't give lasting access.
     * Returns its address, or null. Slow: call from a background thread.
     */
    /** A new file for a background sent from elsewhere (the phone setup page). */
    fun newBackgroundFile(context: Context, extension: String): File {
        val dir = videoDir(context)
        dir.mkdirs()
        return File(dir, "background-" + System.currentTimeMillis() + "." + extension)
    }

    fun copyVideo(context: Context, uri: Uri, extension: String, progress: (Long) -> Unit): String? {
        val dir = videoDir(context)
        dir.mkdirs()
        val target = File(dir, "background-" + System.currentTimeMillis() + "." + extension)
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { source ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) {
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                    progress(total)
                }
            }
        }
        return Uri.fromFile(target).toString()
    }

    /** Deletes TunaHome's own copy of a video, if the address is one. */
    fun forgetVideo(context: Context, address: String) {
        val uri = Uri.parse(address)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return)
            if (file.parentFile == videoDir(context)) {
                file.delete()
            }
        } else if (uri.scheme == "content") {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // It wasn't kept
            }
        }
    }

    // ------------------------------------------------------------------ shared

    /**
     * Copies a chosen picture (on the TV or at a web address) to [target], scaled down so it
     * covers the given size and no more.
     */
    private fun saveShrunk(
        context: Context,
        uri: Uri,
        target: File,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
    ): Boolean {
        var bitmap = decode(context, uri, width, height) ?: return false

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
