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
     * A drawing on nothing (an icon pack's line art, a logo without a background), which would
     * be blown up and cut off if made to fill a card: the card shows it whole, with a margin.
     */
    class LineArt(res: Resources, bitmap: Bitmap) : BitmapDrawable(res, bitmap)

    /** [source] drawn as large as fits in a square, keeping its shape, [margin] of it left empty. */
    private fun fitted(source: Bitmap, size: Int, margin: Float = 0f): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val room = size * (1f - 2 * margin)
        val scale = room / Math.max(source.width, source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (size - w) / 2
        val top = (size - h) / 2
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        Canvas(out).drawBitmap(source, null, android.graphics.RectF(left, top, left + w, top + h), paint)
        return out
    }

    /** Where a drawable goes in a [size] square to keep its shape: as large as fits, in the middle. */
    fun fitBounds(drawable: Drawable, size: Int, inset: Int = 0) = fitSquare(drawable, inset, inset, size - 2 * inset)

    /** [drawable] as large as fits in the square at [x], [y] with sides [side], keeping its shape. */
    fun fitSquare(drawable: Drawable, x: Int, y: Int, side: Int) {
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw <= 0 || ih <= 0 || iw == ih) {
            drawable.setBounds(x, y, x + side, y + side)
            return
        }
        val scale = side.toFloat() / Math.max(iw, ih)
        val w = Math.round(iw * scale)
        val h = Math.round(ih * scale)
        val left = x + (side - w) / 2
        val top = y + (side - h) / 2
        drawable.setBounds(left, top, left + w, top + h)
    }

    /** How much of a picture is drawn on, 0 to 1. */
    private fun coverage(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) {
            return 0f
        }
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var drawn = 0
        for (p in px) {
            if ((p ushr 24) > 160) drawn++
        }
        return drawn.toFloat() / px.size
    }

    /**
     * An icon with its empty border taken away, ready to fill a card. Modern icons come in two
     * layers bigger than what shows, so the middle of them is used; older ones are trimmed to
     * where their picture starts. Slow: call from a background thread.
     */
    fun fullBleed(res: Resources, icon: Drawable?): Drawable? {
        icon ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
                return BitmapDrawable(res, layers(icon))
            }
            val bitmap = trimmed(icon) ?: return icon
            // Line art or a logo on nothing: shown whole, not stretched or cut to fill the card
            if (coverage(bitmap) < 0.6f) {
                return LineArt(res, fitted(bitmap, SIZE, 0.06f))
            }
            BitmapDrawable(res, bitmap)
        } catch (e: Exception) {
            icon
        }
    }

    /**
     * An icon as single-colour line art, to sit with line-style packs such as Arcticons. A modern
     * icon's foreground layer is its logo on nothing, so that shape is used as it is; an older
     * icon is a picture on a background, so its edges are traced instead.
     * Slow: call from a background thread.
     */
    fun outline(res: Resources, icon: Drawable?, colour: Int): Drawable? {
        icon ?: return null
        return try {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable && icon.foreground != null) {
                val extra = size / 4
                val fg = icon.foreground.constantState?.newDrawable()?.mutate() ?: icon.foreground
                fg.setBounds(-extra, -extra, size + extra, size + extra)
                fg.draw(canvas)
                tint(bitmap, colour)
            } else {
                val source = trimmed(icon) ?: return icon
                // Kept in shape: a wide logo stays wide instead of being squashed into a square
                canvas.drawBitmap(fitted(source, size), 0f, 0f, null)
                edges(bitmap, colour)
            }
            BitmapDrawable(res, bitmap)
        } catch (e: Exception) {
            icon
        }
    }

    /**
     * An icon pack's drawing in one colour, keeping its shape (line-art packs such as Arcticons
     * come out as coloured lines). Slow: call from a background thread.
     */
    fun tinted(res: Resources, icon: Drawable?, colour: Int): Drawable? {
        icon ?: return null
        return try {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
            fitBounds(copy, size)
            copy.draw(Canvas(bitmap))
            tint(bitmap, colour)
            BitmapDrawable(res, bitmap)
        } catch (e: Exception) {
            icon
        }
    }

    /**
     * The colour a picture is mostly made of, brightened to glow well: the commonest vivid hue,
     * averaged. 0 when it's mostly grey, black or white. Slow: call from a background thread.
     */
    fun mainColour(picture: Drawable?): Int {
        picture ?: return 0
        return try {
            val size = 24
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val copy = picture.constantState?.newDrawable()?.mutate() ?: picture
            copy.setBounds(0, 0, size, size)
            copy.draw(Canvas(bitmap))
            val px = IntArray(size * size)
            bitmap.getPixels(px, 0, size, 0, 0, size, size)
            // Twelve slices of the colour wheel; each vivid pixel counts towards its slice
            val weight = FloatArray(12)
            val red = FloatArray(12)
            val green = FloatArray(12)
            val blue = FloatArray(12)
            val hsv = FloatArray(3)
            var total = 0f
            for (p in px) {
                if ((p ushr 24) < 128) continue
                android.graphics.Color.colorToHSV(p, hsv)
                val w = hsv[1] * hsv[1] * hsv[2]
                if (w < 0.05f) continue
                val slice = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                weight[slice] += w
                red[slice] += (p shr 16 and 0xFF) * w
                green[slice] += (p shr 8 and 0xFF) * w
                blue[slice] += (p and 0xFF) * w
                total += w
            }
            if (total < 6f) {
                return 0
            }
            val best = weight.indices.maxByOrNull { weight[it] } ?: return 0
            val w = weight[best]
            val colour = android.graphics.Color.rgb(
                (red[best] / w).toInt(), (green[best] / w).toInt(), (blue[best] / w).toInt()
            )
            android.graphics.Color.colorToHSV(colour, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.55f)
            hsv[2] = hsv[2].coerceAtLeast(0.9f)
            android.graphics.Color.HSVToColor(hsv)
        } catch (e: Exception) {
            0
        }
    }

    /** Every pixel the colour given, keeping how see-through it was. */
    private fun tint(bitmap: Bitmap, colour: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = colour and 0xFFFFFF
        for (i in px.indices) {
            px[i] = (px[i] and 0xFF000000.toInt()) or rgb
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Lines where brightness changes sharply (a Sobel filter), in the colour given. */
    private fun edges(bitmap: Bitmap, colour: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val grey = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            val lum = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
            // Transparent counts as black, so a shape's own outline shows too
            grey[i] = lum * a / 255
        }
        val out = IntArray(w * h)
        val rgb = colour and 0xFFFFFF
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -grey[i - w - 1] - 2 * grey[i - 1] - grey[i + w - 1] + grey[i - w + 1] + 2 * grey[i + 1] + grey[i + w + 1]
                val gy = -grey[i - w - 1] - 2 * grey[i - w] - grey[i - w + 1] + grey[i + w - 1] + 2 * grey[i + w] + grey[i + w + 1]
                val strength = Math.min(255, (Math.abs(gx) + Math.abs(gy)) / 2)
                out[i] = if (strength < 40) 0 else ((Math.min(255, strength * 2) shl 24) or rgb)
            }
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
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
        // Its own shape, at a workable size: a big or small picture is scaled as a whole, never
        // one side more than the other
        val iw = icon.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val ih = icon.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        val big = Math.max(iw, ih)
        val scale = when {
            big > 512 -> 512f / big
            big < 128 -> 128f / big
            else -> 1f
        }
        val w = Math.max(1, Math.round(iw * scale))
        val h = Math.max(1, Math.round(ih * scale))
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
