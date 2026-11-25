package com.tunastream.home

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Everything about how TunaHome looks apart from the theme itself. Each setting is a short list
 * of named options (one press to change), stored by position. Changing any of them bumps a
 * stamp, which tells open screens to rebuild themselves.
 */
object Look {
    private const val STAMP = "look_stamp"

    /** A setting with a fixed list of named options. */
    class Choice(val key: String, val default: Int, val names: IntArray) {
        fun get(context: Context): Int =
            Prefs.of(context).getInt(key, default).coerceIn(0, names.size - 1)

        fun set(context: Context, value: Int) {
            Prefs.of(context).edit().putInt(key, value).putInt(STAMP, stamp(context) + 1).apply()
        }

        fun name(context: Context): String = context.getString(names[get(context)])
    }

    /** A setting that is on or off. */
    class Switch(val key: String, val default: Boolean) {
        fun get(context: Context): Boolean = Prefs.of(context).getBoolean(key, default)

        fun set(context: Context, value: Boolean) {
            Prefs.of(context).edit().putBoolean(key, value).putInt(STAMP, stamp(context) + 1).apply()
        }
    }

    fun stamp(context: Context): Int = Prefs.of(context).getInt(STAMP, 0)

    /** For settings stored elsewhere (fonts, rows) that should also rebuild open screens. */
    fun touch(context: Context) {
        Prefs.of(context).edit().putInt(STAMP, stamp(context) + 1).apply()
    }

    private val SIZES5 = intArrayOf(
        R.string.size_smallest, R.string.size_small, R.string.size_medium,
        R.string.size_large, R.string.size_largest,
    )

    // ------------------------------------------------------------------ colours

    val ACCENT = Choice(
        "look_accent", 0, intArrayOf(
            R.string.accent_theme, R.string.colour_cyan, R.string.colour_blue,
            R.string.colour_purple, R.string.colour_pink, R.string.colour_red,
            R.string.colour_orange, R.string.colour_gold, R.string.colour_lime,
            R.string.colour_green, R.string.colour_teal, R.string.colour_white,
        )
    )
    val ACCENT_STYLES = intArrayOf(
        0, R.style.AccentCyan, R.style.AccentBlue, R.style.AccentPurple, R.style.AccentPink,
        R.style.AccentRed, R.style.AccentOrange, R.style.AccentGold, R.style.AccentLime,
        R.style.AccentGreen, R.style.AccentTeal, R.style.AccentWhite,
    )

    val TEXT_COLOUR = Choice(
        "look_text_colour", 0, intArrayOf(
            R.string.text_colour_theme, R.string.text_colour_white,
            R.string.text_colour_warm, R.string.text_colour_cool,
        )
    )
    val TEXT_COLOUR_STYLES = intArrayOf(0, R.style.TextWhite, R.style.TextWarm, R.style.TextCool)

    // ------------------------------------------------------------------ text

    private const val FONT = "look_font_id"
    private const val CLOCK_FONT = "look_clock_font_id"

    /** "" for the standard font. */
    fun font(context: Context): String {
        val id = Prefs.of(context).getString(FONT, Fonts.STANDARD) ?: Fonts.STANDARD
        return if (Fonts.exists(context, id)) id else Fonts.STANDARD
    }

    fun setFont(context: Context, id: String) {
        Prefs.of(context).edit().putString(FONT, id).apply()
        touch(context)
    }

    /** Null means the clock uses the same font as everything else. */
    fun clockFont(context: Context): String? {
        val id = Prefs.of(context).getString(CLOCK_FONT, null) ?: return null
        return if (Fonts.exists(context, id)) id else null
    }

    fun setClockFont(context: Context, id: String?) {
        Prefs.of(context).edit().putString(CLOCK_FONT, id).apply()
        touch(context)
    }

    val TEXT_SIZE = Choice(
        "look_text_size2", 2, intArrayOf(
            R.string.size_smallest, R.string.size_small, R.string.size_medium,
            R.string.size_large, R.string.size_larger, R.string.size_largest,
        )
    )
    private val TEXT_SCALES = floatArrayOf(0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f)

    val TEXT_SHADOW = Switch("look_text_shadow", true)

    // ------------------------------------------------------------------ app pictures

    const val TILE_BANNER = 0
    const val TILE_ICON = 1
    const val TILE_ICON_NAME = 2
    val TILE_STYLE = Choice(
        "look_tile_style", TILE_BANNER,
        intArrayOf(R.string.tile_style_banner, R.string.tile_style_icon, R.string.tile_style_icon_name)
    )

    /** The old five-step size, read only to carry it over to [CARD_SIZE]. */
    private val TILE_SIZE = Choice("look_tile_size2", 2, SIZES5)
    private val TILE_SCALES = floatArrayOf(0.65f, 0.8f, 1f, 1.25f, 1.5f)

    /** How big every card is, from half to double. */
    val CARD_SIZE = Choice(
        "look_card_size", 5, intArrayOf(
            R.string.pct_50, R.string.pct_60, R.string.pct_70, R.string.pct_80, R.string.pct_90,
            R.string.pct_100, R.string.pct_115, R.string.pct_130, R.string.pct_150,
            R.string.pct_175, R.string.pct_200,
        )
    )
    val CARD_SCALES = floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)

    const val SHAPE_AUTO = 0
    const val SHAPE_WIDE = 1
    const val SHAPE_STANDARD = 2
    const val SHAPE_SQUARE = 3
    const val SHAPE_TALL = 4
    val CARD_SHAPE = Choice(
        "look_card_shape", SHAPE_AUTO, intArrayOf(
            R.string.shape_auto, R.string.shape_wide, R.string.shape_standard,
            R.string.shape_square, R.string.shape_tall,
        )
    )

    /** How much of the card an icon fills. */
    val ICON_FILL = Choice(
        "look_icon_fill", 4, intArrayOf(
            R.string.fill_small, R.string.fill_medium, R.string.fill_large, R.string.fill_most,
            R.string.fill_edge,
        )
    )
    val ICON_FILLS = floatArrayOf(0.4f, 0.58f, 0.72f, 0.88f, 1f)
    const val FILL_EDGE = 4

    fun fullBleed(context: Context): Boolean =
        ICON_FILL.get(context) == FILL_EDGE && TILE_STYLE.get(context) != TILE_BANNER

    /** Space between the top bar and the first section. */
    val HEADER_GAP = Choice(
        "look_header_gap", 2, intArrayOf(
            R.string.gap_none, R.string.gap_tight, R.string.gap_normal, R.string.gap_wide, R.string.gap_widest,
        )
    )
    private val HEADER_GAP_DP = floatArrayOf(0f, 6f, 14f, 28f, 48f)
    fun headerGapDp(context: Context): Float = HEADER_GAP_DP[HEADER_GAP.get(context)]

    // ------------------------------------------------------------------ side menu

    const val MENU_OFF = 0
    const val MENU_HIDDEN = 1
    const val MENU_RAIL = 2
    val SIDE_MENU = Choice(
        "look_side_menu", MENU_RAIL,
        intArrayOf(R.string.menu_mode_off, R.string.menu_mode_hidden, R.string.menu_mode_rail)
    )
    val SIDE_MENU_SHORTCUTS = Switch("look_side_menu_shortcuts", true)

    val TILE_CORNERS = Choice(
        "look_tile_corners", 2, intArrayOf(
            R.string.corners_square, R.string.corners_slight, R.string.corners_rounded,
            R.string.corners_very, R.string.corners_pill,
        )
    )
    private val CORNER_DP = floatArrayOf(0f, 4f, 10f, 20f, 999f)

    const val CARD_THEME = 0
    const val CARD_CLEAR = 1
    const val CARD_GLASS = 2
    const val CARD_ACCENT = 3
    const val CARD_BLACK = 4
    const val CARD_WHITE = 5
    val TILE_CARD = Choice(
        "look_tile_card", CARD_THEME, intArrayOf(
            R.string.card_theme, R.string.card_clear, R.string.card_glass,
            R.string.card_accent, R.string.card_black, R.string.card_white,
        )
    )
    val TILE_EDGE = Switch("look_tile_edge", true)

    val TILE_GAP = Choice(
        "look_tile_gap", 1,
        intArrayOf(R.string.gap_tight, R.string.gap_normal, R.string.gap_wide, R.string.gap_widest)
    )
    private val GAP_DP = floatArrayOf(4f, 8f, 14f, 22f)

    const val NAMES_FOCUSED = 0
    const val NAMES_ALWAYS = 1
    const val NAMES_NEVER = 2
    val TILE_NAMES = Choice(
        "look_tile_names", NAMES_FOCUSED,
        intArrayOf(R.string.names_focused, R.string.names_always, R.string.names_never)
    )

    val FOCUS_ZOOM = Choice(
        "look_focus_zoom", 2,
        intArrayOf(R.string.zoom_none, R.string.zoom_small, R.string.zoom_medium, R.string.zoom_large)
    )
    private val ZOOMS = floatArrayOf(1f, 1.05f, 1.1f, 1.2f)

    const val OUTLINE_ACCENT = 0
    const val OUTLINE_WHITE = 1
    const val OUTLINE_NONE = 2
    val FOCUS_OUTLINE = Choice(
        "look_focus_outline", OUTLINE_ACCENT,
        intArrayOf(R.string.outline_accent, R.string.outline_white, R.string.outline_none)
    )

    val DIM_OTHERS = Switch("look_dim_others", false)

    // ------------------------------------------------------------------ rows

    const val TITLES_NORMAL = 0
    const val TITLES_BOLD = 1
    const val TITLES_CAPS = 2
    const val TITLES_HIDDEN = 3
    val ROW_TITLES = Choice(
        "look_row_titles", TITLES_NORMAL, intArrayOf(
            R.string.titles_normal, R.string.titles_bold, R.string.titles_caps, R.string.titles_hidden,
        )
    )

    const val SCROLL_START = 0
    const val SCROLL_CENTRE = 1
    const val SCROLL_FREE = 2
    val ROW_SCROLL = Choice(
        "look_row_scroll", SCROLL_START,
        intArrayOf(R.string.scroll_start, R.string.scroll_centre, R.string.scroll_free)
    )

    /** Select twice quickly picks an app up to move it. Opening then waits a moment for a second press. */
    val DOUBLE_PRESS = Switch("look_double_press", true)

    val ROW_GAP = Choice(
        "look_row_gap", 1,
        intArrayOf(R.string.gap_tight, R.string.gap_normal, R.string.gap_wide, R.string.gap_widest)
    )
    private val ROW_GAP_DP = floatArrayOf(4f, 14f, 26f, 40f)

    const val START_FIRST = 0
    const val START_LAST = 1
    val START_ON = Choice(
        "look_start_on", START_FIRST, intArrayOf(R.string.start_first, R.string.start_last)
    )

    // ------------------------------------------------------------------ top of the screen

    val SHOW_BRAND = Switch("look_show_brand", true)

    const val BRAND_BOTH = 0
    const val BRAND_LOGO = 1
    const val BRAND_NAME = 2
    val BRAND_PARTS = Choice(
        "look_brand_parts", BRAND_BOTH,
        intArrayOf(R.string.brand_both, R.string.brand_logo_only, R.string.brand_name_only)
    )
    /** Half the original size to begin with: the home screen is about the apps. */
    val BRAND_SIZE = Choice(
        "look_brand_size", 1, intArrayOf(
            R.string.brand_size_smallest, R.string.brand_size_small,
            R.string.brand_size_medium, R.string.brand_size_full,
        )
    )
    private val BRAND_SCALES = floatArrayOf(0.35f, 0.5f, 0.75f, 1f)
    fun brandScale(context: Context): Float = BRAND_SCALES[BRAND_SIZE.get(context)]
    val SHOW_VERSION = Switch("look_show_version", true)

    const val CLOCK_FOLLOW_TV = 0
    const val CLOCK_24 = 1
    const val CLOCK_12 = 2
    const val CLOCK_12_AMPM = 3
    const val CLOCK_HIDDEN = 4
    val CLOCK = Choice(
        "look_clock", CLOCK_FOLLOW_TV, intArrayOf(
            R.string.clock_follow, R.string.clock_24, R.string.clock_12,
            R.string.clock_12_ampm, R.string.clock_hidden,
        )
    )
    val CLOCK_SIZE = Choice("look_clock_size", 2, SIZES5)
    private val CLOCK_SCALES = floatArrayOf(0.7f, 0.85f, 1f, 1.3f, 1.7f)

    const val DATE_HIDDEN = 0
    const val DATE_SHORT = 1
    const val DATE_LONG = 2
    val DATE = Choice(
        "look_date", DATE_HIDDEN,
        intArrayOf(R.string.date_hidden, R.string.date_short, R.string.date_long)
    )

    val SHOW_NETWORK = Switch("look_show_network", true)
    val SHOW_UPDATE_BUTTON = Switch("look_show_update", true)

    // ------------------------------------------------------------------ idle

    val IDLE = Choice(
        "look_idle", 0, intArrayOf(
            R.string.idle_off, R.string.idle_1m, R.string.idle_2m, R.string.idle_5m,
            R.string.idle_10m, R.string.idle_15m,
        )
    )
    private val IDLE_MS = longArrayOf(0, 60_000, 120_000, 300_000, 600_000, 900_000)
    fun idleMs(context: Context): Long = IDLE_MS[IDLE.get(context)]
    val IDLE_CLOCK = Switch("look_idle_clock", true)
    val SHOW_TV_SETTINGS_BUTTON = Switch("look_show_tv_settings", true)

    // ------------------------------------------------------------------ working out the numbers

    fun textScale(context: Context): Float = TEXT_SCALES[TEXT_SIZE.get(context)]
    fun tileScale(context: Context): Float {
        if (!Prefs.of(context).contains(CARD_SIZE.key) && Prefs.of(context).contains(TILE_SIZE.key)) {
            // Carry the old size over to the closest new one
            val old = TILE_SCALES[TILE_SIZE.get(context)]
            return CARD_SCALES.minByOrNull { Math.abs(it - old) } ?: 1f
        }
        return CARD_SCALES[CARD_SIZE.get(context)]
    }
    fun cornerDp(context: Context): Float = CORNER_DP[TILE_CORNERS.get(context)]
    fun tileGapDp(context: Context): Float = GAP_DP[TILE_GAP.get(context)]
    fun focusZoom(context: Context): Float = ZOOMS[FOCUS_ZOOM.get(context)]
    fun rowGapDp(context: Context): Float = ROW_GAP_DP[ROW_GAP.get(context)]
    fun clockScale(context: Context): Float = CLOCK_SCALES[CLOCK_SIZE.get(context)]

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    // ------------------------------------------------------------------ text styling

    /**
     * Gives every piece of text under [root] the chosen font, size and shadow, keeping each
     * text's own weight. Call once per view, after it is built.
     */
    fun styleText(root: View) {
        val context = root.context
        val font = font(context)
        val scale = textScale(context)
        val shadow = TEXT_SHADOW.get(context)
        apply(root, context, font, scale, shadow)
    }

    private fun apply(view: View, context: Context, font: String, scale: Float, shadow: Boolean) {
        if (view is TextView) {
            styleOne(view, context, font, scale)
            if (!shadow) {
                view.setShadowLayer(0f, 0f, 0f, 0)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                apply(view.getChildAt(i), context, font, scale, shadow)
            }
        }
    }

    /** Sets one text's font (keeping its weight) and multiplies its size by [scale]. */
    fun styleOne(view: TextView, context: Context, font: String, scale: Float) {
        if (font != Fonts.STANDARD) {
            Fonts.typeface(context, font, weightOf(view.typeface))?.let { view.typeface = it }
        }
        if (scale != 1f) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * scale)
        }
    }

    /** The weight a text was designed with: light, regular, medium or bold. */
    fun weightOf(typeface: Typeface?): Int {
        if (typeface == null) {
            return 400
        }
        if (Build.VERSION.SDK_INT >= 28) {
            return typeface.weight
        }
        return if (typeface.isBold) 700 else 400
    }
}
