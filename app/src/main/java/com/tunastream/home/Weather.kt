package com.tunastream.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The weather beside the clock, from Open-Meteo (free, no account, no key). The place is one
 * the viewer searched for, or one found from the TV's internet address if they ask for that.
 * Nothing else about the TV is sent.
 */
object Weather {
    private const val TAG = "TunaHome"
    private const val KEEP_MS = 30L * 60 * 1000

    val SHOW = Look.Switch("wx_show", true)

    const val UNITS_C = 0
    const val UNITS_F = 1
    val UNITS = Look.Choice(
        "wx_units", if (Locale.getDefault().country in setOf("US", "LR", "MM")) UNITS_F else UNITS_C,
        intArrayOf(R.string.wx_units_c, R.string.wx_units_f)
    )

    const val DETAIL_TEMP = 0
    const val DETAIL_CONDITION = 1
    const val DETAIL_HIGH_LOW = 2
    const val DETAIL_ALL = 3
    val DETAIL = Look.Choice(
        "wx_detail", DETAIL_HIGH_LOW, intArrayOf(
            R.string.wx_detail_temp, R.string.wx_detail_condition, R.string.wx_detail_high_low, R.string.wx_detail_all,
        )
    )

    const val ICONS_COLOUR = 0
    const val ICONS_WHITE = 1
    const val ICONS_ACCENT = 2
    const val ICONS_NONE = 3
    val ICONS = Look.Choice(
        "wx_icons", ICONS_COLOUR,
        intArrayOf(R.string.wx_icons_colour, R.string.wx_icons_white, R.string.wx_icons_accent, R.string.wx_icons_none)
    )

    val SIZE = Look.Choice(
        "wx_size", 1, intArrayOf(R.string.size_small, R.string.size_medium, R.string.size_large)
    )
    private val SIZES = floatArrayOf(0.8f, 1f, 1.3f)
    fun scale(context: Context): Float = SIZES[SIZE.get(context)]

    const val PLACE_BESIDE = 0
    const val PLACE_UNDER = 1
    val PLACE = Look.Choice(
        "wx_place", PLACE_BESIDE, intArrayOf(R.string.wx_place_beside, R.string.wx_place_under)
    )

    // ------------------------------------------------------------------ where

    class Place(val name: String, val latitude: Double, val longitude: Double)

    fun place(context: Context): Place? {
        val prefs = Prefs.of(context)
        val name = prefs.getString("wx_place_name", null) ?: return null
        return Place(
            name,
            java.lang.Double.longBitsToDouble(prefs.getLong("wx_lat", 0)),
            java.lang.Double.longBitsToDouble(prefs.getLong("wx_lon", 0)),
        )
    }

    fun setPlace(context: Context, place: Place?) {
        val edit = Prefs.of(context).edit()
        if (place == null) {
            edit.remove("wx_place_name").remove("wx_lat").remove("wx_lon")
        } else {
            edit.putString("wx_place_name", place.name)
                .putLong("wx_lat", java.lang.Double.doubleToRawLongBits(place.latitude))
                .putLong("wx_lon", java.lang.Double.doubleToRawLongBits(place.longitude))
        }
        edit.apply()
        current = null
        Look.touch(context)
    }

    /** Towns matching what was typed. Slow: background thread. */
    fun search(text: String): List<Place> {
        val json = JSONObject(read(
            "https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" +
                Locale.getDefault().language + "&name=" + URLEncoder.encode(text, "UTF-8")
        ))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val parts = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }.distinct()
            Place(parts.joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** A rough place from the TV's internet address (ipapi.co). Slow: background thread. */
    fun fromInternetAddress(): Place? {
        val json = JSONObject(read("https://ipapi.co/json/"))
        if (!json.has("latitude")) {
            return null
        }
        val parts = listOf(json.optString("city"), json.optString("region"), json.optString("country_name"))
            .filter { it.isNotBlank() }.distinct()
        return Place(parts.joinToString(", "), json.getDouble("latitude"), json.getDouble("longitude"))
    }

    // ------------------------------------------------------------------ the weather

    class Now(
        val temperature: Int,
        val feelsLike: Int,
        val high: Int,
        val low: Int,
        val code: Int,
        val day: Boolean,
        val humidity: Int,
        val wind: Int,
        val at: Long,
    )

    @Volatile
    var current: Now? = null
        private set

    /** What happened on the last try, for the Weather settings page. */
    @Volatile
    var status: String = ""

    private var fetching = false

    /** Fetches the weather if what is kept is old; [done] runs on the main thread if it changed. */
    fun refresh(context: Context, force: Boolean = false, done: () -> Unit) {
        val place = place(context) ?: return
        if (!SHOW.get(context) || fetching) {
            return
        }
        val kept = current
        if (!force && kept != null && SystemClock.elapsedRealtime() - kept.at < KEEP_MS) {
            return
        }
        fetching = true
        val fahrenheit = UNITS.get(context) == UNITS_F
        val app = context.applicationContext
        thread(name = "TunaHome weather") {
            var got: Now? = null
            try {
                val json = JSONObject(read(
                    "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
                        "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m" +
                        "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto" +
                        (if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")
                ))
                val c = json.getJSONObject("current")
                val d = json.getJSONObject("daily")
                got = Now(
                    Math.round(c.getDouble("temperature_2m")).toInt(),
                    Math.round(c.getDouble("apparent_temperature")).toInt(),
                    Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                    c.getInt("weather_code"),
                    c.optInt("is_day", 1) == 1,
                    c.optInt("relative_humidity_2m", -1),
                    Math.round(c.optDouble("wind_speed_10m", -1.0)).toInt(),
                    SystemClock.elapsedRealtime(),
                )
                status = app.getString(R.string.wx_status_ok, place.name)
            } catch (e: Exception) {
                Log.w(TAG, "Weather: $e")
                status = app.getString(R.string.wx_status_failed, e.message ?: e.toString())
            }
            Handler(Looper.getMainLooper()).post {
                fetching = false
                if (got != null) {
                    current = got
                    done()
                }
            }
        }
    }

    /** Forgets the kept weather, after units or place change. */
    fun forget() {
        current = null
    }

    // ------------------------------------------------------------------ describing it

    /** The WMO weather code as one of nine pictures. */
    fun icon(code: Int, day: Boolean): Int = when (code) {
        0 -> if (day) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        1, 2 -> if (day) R.drawable.wx_partly_day else R.drawable.wx_partly_night
        3 -> R.drawable.wx_cloudy
        45, 48 -> R.drawable.wx_fog
        in 51..67, in 80..82 -> R.drawable.wx_rain
        in 71..77, 85, 86 -> R.drawable.wx_snow
        in 95..99 -> R.drawable.wx_storm
        else -> R.drawable.wx_cloudy
    }

    fun condition(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67, in 80..82 -> R.string.wx_rain
        in 71..77, 85, 86 -> R.string.wx_snow
        in 95..99 -> R.string.wx_storm
        else -> R.string.wx_cloudy
    }

    private fun read(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "TunaHome/" + BuildConfig.VERSION_NAME)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("the weather service answered ${conn.responseCode}")
            }
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
