package com.tunastream.home

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/** Makes icons fill their whole card, edge to edge, instead of sitting small in the middle. */
object IconShape {
    private const val SIZE = 256

    /**
     * An icon with its empty border taken away, ready to fill a card. Modern icons come in two
     * layers bigger than what shows, so the middle of them is used; older ones are trimmed to
     * where their picture starts. Slow: call from a background thread.
     */
    fun fullBleed(res: Resources, icon: Drawable?): Drawable? {
        icon ?: return null
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
                layers(icon)
            } else {
                trimmed(icon)
            }
            bitmap?.let { BitmapDrawable(res, it) } ?: icon
        } catch (e: Exception) {
            icon
        }
    }

    /** The visible middle of an adaptive icon's two layers, without the TV's mask shape. */
    private fun layers(icon: AdaptiveIconDrawable): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Layers are 108 units across and the icon shows the middle 72: a quarter more each side
        val extra = SIZE / 4
        for (layer in listOf(icon.background, icon.foreground)) {
            layer ?: continue
            val copy = layer.constantState?.newDrawable()?.mutate() ?: layer
            copy.setBounds(-extra, -extra, SIZE + extra, SIZE + extra)
            copy.draw(canvas)
        }
        return bitmap
    }

    /** An older icon, cut down to where its picture is (most have see-through borders). */
    private fun trimmed(icon: Drawable): Bitmap? {
        val w = icon.intrinsicWidth.takeIf { it > 0 }?.coerceIn(16, 512) ?: SIZE
        val h = icon.intrinsicHeight.takeIf { it > 0 }?.coerceIn(16, 512) ?: SIZE
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
        copy.setBounds(0, 0, w, h)
        copy.draw(Canvas(full))

        val pixels = IntArray(w * h)
        full.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var right = -1
        var top = h
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((pixels[y * w + x] ushr 24) > 24) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) {
            return full
        }
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) {
            return full
        }
        return Bitmap.createBitmap(full, left, top, right - left + 1, bottom - top + 1)
    }
}
