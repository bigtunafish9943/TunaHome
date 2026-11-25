package com.tunastream.home

import android.content.Context
import android.util.TypedValue
import java.util.Calendar

/**
 * Themes, named the same as in TunaStream and TunaReceive. TunaHome picks its own: nothing
 * here needs either of them installed.
 *
 * A theme is laid over each screen's own theme just before the screen is built (see
 * [BaseActivity]), so every colour on it follows.
 */
object TunaTheme {
    const val DEFAULT = "deepsea"
    private const val PREF_CHOSEN = "theme"
    private const val PREF_SEASONAL = "seasonal_themes"

    val IDS = arrayOf(
        "deepsea", "reef", "kelp", "midnight", "gold",
        "halloween", "autumn", "winter", "summer",
    )
    private val STYLES = intArrayOf(
        R.style.TunaPaletteDeepSea, R.style.TunaPaletteReef, R.style.TunaPaletteKelp,
        R.style.TunaPaletteMidnight, R.style.TunaPaletteGold,
        R.style.TunaPaletteHalloween, R.style.TunaPaletteAutumn, R.style.TunaPaletteWinter,
        R.style.TunaPaletteSummer,
    )
    private val NAMES = intArrayOf(
        R.string.theme_deepsea, R.string.theme_reef, R.string.theme_kelp,
        R.string.theme_midnight, R.string.theme_gold,
        R.string.theme_halloween, R.string.theme_autumn, R.string.theme_winter,
        R.string.theme_summer,
    )

    private fun indexOf(id: String): Int = IDS.indexOf(id).let { if (it < 0) 0 else it }

    fun styleOf(id: String): Int = STYLES[indexOf(id)]

    fun nameOf(id: String): Int = NAMES[indexOf(id)]

    /** The theme picked in Settings. */
    fun chosen(context: Context): String {
        val id = Prefs.of(context).getString(PREF_CHOSEN, DEFAULT) ?: DEFAULT
        return if (IDS.contains(id)) id else DEFAULT
    }

    fun setChosen(context: Context, id: String) {
        Prefs.of(context).edit().putString(PREF_CHOSEN, id).apply()
    }

    fun seasonal(context: Context): Boolean = Prefs.of(context).getBoolean(PREF_SEASONAL, true)

    fun setSeasonal(context: Context, on: Boolean) {
        Prefs.of(context).edit().putBoolean(PREF_SEASONAL, on).apply()
    }

    /** The season's theme today, or null outside the seasons. The same dates TunaStream uses. */
    fun season(): String? {
        val now = Calendar.getInstance()
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        return when {
            month == 10 -> "halloween"
            month == 11 -> "autumn"
            month == 12 || (month == 1 && day <= 15) -> "winter"
            (month == 6 && day >= 21) || month == 7 || month == 8 -> "summer"
            else -> null
        }
    }

    /** The theme to wear now: the season's when seasonal themes are on, otherwise the chosen one. */
    fun current(context: Context): String {
        if (seasonal(context)) {
            season()?.let { return it }
        }
        return chosen(context)
    }
}

/** A colour from the theme the screen is wearing, such as R.attr.tsAccent. */
fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}
