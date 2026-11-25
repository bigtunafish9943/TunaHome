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
                canvas.drawBitmap(Bitmap.createScaledBitmap(source, size, size, true), 0f, 0f, null)
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
            copy.setBounds(0, 0, size, size)
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
