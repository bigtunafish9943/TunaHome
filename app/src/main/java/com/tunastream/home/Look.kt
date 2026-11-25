package com.tunastream.home

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The viewer's choices for how TunaHome looks beyond its colours: text style, text size and how
 * big the app pictures are. Each is a short list of named options so it is one press to change.
 */
object Look {
    private const val FONT = "look_font"
    private const val TEXT_SIZE = "look_text_size"
    private const val TILE_SIZE = "look_tile_size"
    private const val STAMP = "look_stamp"

    /** Android's own font families; "" leaves each piece of text as the design has it. */
    val FONT_FAMILIES = arrayOf("", "sans-serif-light", "sans-serif-condensed", "serif", "monospace", "casual")
    val FONT_NAMES = intArrayOf(
        R.string.font_standard, R.string.font_light, R.string.font_condensed,
        R.string.font_serif, R.string.font_mono, R.string.font_casual,
    )

    val TEXT_SCALES = floatArrayOf(0.88f, 1f, 1.15f, 1.3f)
    val TEXT_NAMES = intArrayOf(
        R.string.size_small, R.string.size_medium, R.string.size_large, R.string.size_largest,
    )

    val TILE_SCALES = floatArrayOf(0.8f, 1f, 1.25f)
    val TILE_NAMES = intArrayOf(R.string.size_small, R.string.size_medium, R.string.size_large)

    private fun index(context: Context, key: String, default: Int, count: Int): Int =
        Prefs.of(context).getInt(key, default).coerceIn(0, count - 1)

    private fun set(context: Context, key: String, value: Int) {
        // The stamp tells open screens they were built with the old look
        Prefs.of(context).edit().putInt(key, value).putInt(STAMP, stamp(context) + 1).apply()
    }

    fun stamp(context: Context): Int = Prefs.of(context).getInt(STAMP, 0)

    fun font(context: Context): Int = index(context, FONT, 0, FONT_FAMILIES.size)
    fun setFont(context: Context, value: Int) = set(context, FONT, value)

    fun textSize(context: Context): Int = index(context, TEXT_SIZE, 1, TEXT_SCALES.size)
    fun setTextSize(context: Context, value: Int) = set(context, TEXT_SIZE, value)

    fun tileSize(context: Context): Int = index(context, TILE_SIZE, 1, TILE_SCALES.size)
    fun setTileSize(context: Context, value: Int) = set(context, TILE_SIZE, value)

    fun tileScale(context: Context): Float = TILE_SCALES[tileSize(context)]

    /** Gives every piece of text under [root] the chosen style and size. Call once per view. */
    fun styleText(root: View) {
        val context = root.context
        val family = FONT_FAMILIES[font(context)]
        val scale = TEXT_SCALES[textSize(context)]
        if (family.isEmpty() && scale == 1f) {
            return
        }
        apply(root, family, scale)
    }

    private fun apply(view: View, family: String, scale: Float) {
        if (view is TextView) {
            if (family.isNotEmpty()) {
                val style = view.typeface?.style ?: Typeface.NORMAL
                view.typeface = Typeface.create(family, style)
            }
            if (scale != 1f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * scale)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                apply(view.getChildAt(i), family, scale)
            }
        }
    }
}
