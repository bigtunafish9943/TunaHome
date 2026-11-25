package com.tunastream.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A new background picture every day: Bing's photo of the day, Wikipedia's picture of the day,
 * or NASA's astronomy picture of the day. Fetched once a day and kept on the TV.
 */
object Daily {
    const val BING = 0
    const val WIKIPEDIA = 1
    const val NASA = 2
    val PROVIDER = Look.Choice(
        "daily_provider", BING, intArrayOf(R.string.daily_bing, R.string.daily_wikipedia, R.string.daily_nasa)
    )

    private fun file(context: Context) = File(context.filesDir, "daily.jpg")

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Today's picture and its caption, from what's kept if it's today's. Slow: background thread. */
    fun picture(context: Context, force: Boolean = false): Pair<Bitmap?, String?> {
        val prefs = Prefs.of(context)
        val provider = PROVIDER.get(context)
        val key = "${today()}/$provider"
        val kept = file(context)
        if (!force && prefs.getString("daily_key", null) == key && kept.exists()) {
            return BitmapFactory.decodeFile(kept.path) to prefs.getString("daily_caption", null)
        }
        val (address, caption) = try {
            when (provider) {
                WIKIPEDIA -> wikipedia()
                NASA -> nasa() ?: bing()
                else -> bing()
            }
        } catch (e: Exception) {
            // No connection: yesterday's picture is better than none
            return (if (kept.exists()) BitmapFactory.decodeFile(kept.path) else null) to prefs.getString("daily_caption", null)
        }
        val bitmap = Pictures.decode(context, Uri.parse(address), 1920, 1080) ?: return null to null
        FileOutputStream(kept).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        prefs.edit().putString("daily_key", key).putString("daily_caption", caption).apply()
        return bitmap to caption
    }

    private fun bing(): Pair<String, String?> {
        val json = JSONObject(read("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=" +
            Locale.getDefault().toLanguageTag()))
        val image = json.getJSONArray("images").getJSONObject(0)
        val base = image.optString("urlbase")
        val address = if (base.isNotEmpty()) "https://www.bing.com${base}_UHD.jpg" else "https://www.bing.com" + image.getString("url")
        return address to (image.optString("title").ifBlank { image.optString("copyright") })
    }

    private fun wikipedia(): Pair<String, String?> {
        val date = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val json = JSONObject(read("https://api.wikimedia.org/feed/v1/wikipedia/en/featured/$date"))
        val image = json.getJSONObject("image")
        // A large rendering rather than the full original, which can be huge
        val thumb = image.optJSONObject("thumbnail")?.optString("source")
        val full = image.getJSONObject("image").getString("source")
        val address = thumb?.replace(Regex("/\\d+px-"), "/1920px-") ?: full
        val caption = image.optJSONObject("description")?.optString("text")?.replace(Regex("<[^>]*>"), "")
        return address to caption
    }

    /** NASA's picture, or null on days it's a video. */
    private fun nasa(): Pair<String, String?>? {
        val json = JSONObject(read("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY"))
        if (json.optString("media_type") != "image") {
            return null
        }
        return json.optString("hdurl").ifBlank { json.getString("url") } to json.optString("title")
    }

    private fun read(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "TunaHome/" + BuildConfig.VERSION_NAME + " (github.com/bigtunafish9943/TunaHome)")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("answered ${conn.responseCode}")
            }
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
