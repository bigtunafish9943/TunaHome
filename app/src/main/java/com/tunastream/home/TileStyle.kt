package com.tunastream.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * How app tiles look, worked out once from the viewer's settings when the home screen is built.
 */
class TileStyle(context: Context, sizeOverride: Int = -1, styleOverride: Int = -1) {
    /** TV pictures, icons, or icons with names: a section can have its own. */
    val style = if (styleOverride >= 0) styleOverride else Look.TILE_STYLE.get(context)
    val width: Int
    val height: Int
    val gap: Int
    val names = Look.TILE_NAMES.get(context)
    val zoom = Look.focusZoom(context)
    val dimOthers = Look.DIM_OTHERS.get(context)
    val scroll = Look.ROW_SCROLL.get(context)
    val doublePress = Look.DOUBLE_PRESS.get(context)
    val motion = Look.FOCUS_MOTION.get(context)
    val shadow = Look.FOCUS_SHADOW.get(context)
    val glowColour = if (shadow == Look.SHADOW_GLOW_WHITE) Color.WHITE else context.themeColor(R.attr.tsAccent)
    /** The glow and outline take each app's own colour. */
    val glowApp = shadow == Look.SHADOW_GLOW_APP
    val outlineApp = Look.FOCUS_OUTLINE.get(context) == Look.OUTLINE_APP
    val newBadge = Look.NEW_BADGE.get(context)
    val shine = Look.SHINE.get(context) && Look.FOCUS_MOTION.get(context) != Look.MOTION_INSTANT
    val scrollNames = Look.SCROLL_NAMES.get(context)
    val lift = Look.dp(context, 14f)
    /** Icons fill the whole card, edge to edge, cut to its rounded corners. */
    val fullBleed = Look.ICON_FILL.get(context) == Look.FILL_EDGE && style != Look.TILE_BANNER &&
        Look.ICON_TINT.get(context) == Look.TINT_NONE
    val iconSize: Int
    private val corner: Float
    private val cardColour: Int
    private val edgeColour: Int
    val outlineColour: Int
    val outlineWidth: Int

    init {
        val res = context.resources
        // A section can have its own card size; otherwise the size set for every card
        val scale = if (sizeOverride in Look.CARD_SCALES.indices) Look.CARD_SCALES[sizeOverride]
        else Look.tileScale(context)
        val baseWidth = res.getDimension(R.dimen.tile_width) * scale
        // Width, then height as a share of it. "Auto" is 16:9 for TV pictures, square for icons.
        val shape = Look.CARD_SHAPE.get(context).let {
            if (it == Look.SHAPE_AUTO) (if (style == Look.TILE_BANNER) Look.SHAPE_WIDE else Look.SHAPE_SQUARE) else it
        }
        val (widthShare, heightRatio) = when (shape) {
            Look.SHAPE_STANDARD -> 0.85f to 0.75f
            Look.SHAPE_SQUARE -> 0.72f to 1f
            Look.SHAPE_TALL -> 0.6f to 1.5f
            else -> 1f to 9f / 16f
        }
        width = Math.round(baseWidth * widthShare)
        height = Math.round(baseWidth * widthShare * heightRatio)
        gap = Math.round(Look.dp(context, Look.tileGapDp(context)))
        // Icons all in one colour are line drawings: edge to edge they would look far too big
        val fill = Look.ICON_FILLS[Look.ICON_FILL.get(context)].let {
            if (Look.ICON_TINT.get(context) != Look.TINT_NONE) minOf(it, 0.72f) else it
        }
        // With the name inside the card, the icon leaves room for it
        iconSize = Math.round(
            minOf(width, height) * when {
                // An app with no TV picture, in the TV picture style: a modest icon in the middle
                style == Look.TILE_BANNER -> 0.6f
                style == Look.TILE_ICON_NAME && !fullBleed -> fill * 0.8f
                else -> fill
            }
        )
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
