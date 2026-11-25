package com.tunastream.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * How app tiles look, worked out once from the viewer's settings when the home screen is built.
 */
class TileStyle(context: Context) {
    val style = Look.TILE_STYLE.get(context)
    val width: Int
    val height: Int
    val gap: Int
    val names = Look.TILE_NAMES.get(context)
    val zoom = Look.focusZoom(context)
    val dimOthers = Look.DIM_OTHERS.get(context)
    val scroll = Look.ROW_SCROLL.get(context)
    val iconSize: Int
    private val corner: Float
    private val cardColour: Int
    private val edgeColour: Int
    private val outlineColour: Int
    private val outlineWidth: Int

    init {
        val res = context.resources
        val scale = Look.tileScale(context)
        val baseWidth = res.getDimension(R.dimen.tile_width) * scale
        // Square tiles for icons; TV pictures are 16:9
        width = Math.round(if (style == Look.TILE_BANNER) baseWidth else baseWidth * 0.72f)
        height = Math.round(
            if (style == Look.TILE_BANNER) res.getDimension(R.dimen.tile_height) * scale
            else baseWidth * 0.72f
        )
        gap = Math.round(Look.dp(context, Look.tileGapDp(context)))
        iconSize = Math.round(height * if (style == Look.TILE_ICON_NAME) 0.5f else 0.6f)
        corner = minOf(Look.dp(context, Look.cornerDp(context)), height / 2f)

        val surface = context.themeColor(R.attr.tsSurface)
        val accent = context.themeColor(R.attr.tsAccent)
        cardColour = when (Look.TILE_CARD.get(context)) {
            Look.CARD_CLEAR -> Color.TRANSPARENT
            Look.CARD_GLASS -> withAlpha(surface, 0x88)
            Look.CARD_ACCENT -> withAlpha(accent, 0x66)
            Look.CARD_BLACK -> Color.argb(0xCC, 0, 0, 0)
            Look.CARD_WHITE -> Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
            else -> surface
        }
        edgeColour = if (Look.TILE_EDGE.get(context)) {
            if (Look.TILE_CARD.get(context) == Look.CARD_THEME) context.themeColor(R.attr.tsSurfaceBorder)
            else Color.argb(0x55, 0xFF, 0xFF, 0xFF)
        } else {
            Color.TRANSPARENT
        }
        outlineColour = when (Look.FOCUS_OUTLINE.get(context)) {
            Look.OUTLINE_WHITE -> Color.WHITE
            Look.OUTLINE_NONE -> Color.TRANSPARENT
            else -> accent
        }
        outlineWidth = Math.round(Look.dp(context, 3f))
    }

    /** The card behind an app's picture. */
    fun card(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = corner
        setColor(cardColour)
        if (edgeColour != Color.TRANSPARENT) {
            setStroke(Math.max(1, Math.round(Look.dp(context, 1f))), edgeColour)
        }
    }

    /** The outline drawn over the app you are on. */
    fun ring(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = corner
        setColor(Color.TRANSPARENT)
        if (outlineColour != Color.TRANSPARENT) {
            setStroke(outlineWidth, outlineColour)
        }
    }

    /** White cards need dark text inside them. */
    val darkCard: Boolean
        get() = Look.CARD_WHITE == cardKind

    private val cardKind = Look.TILE_CARD.get(context)

    private fun withAlpha(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
}
