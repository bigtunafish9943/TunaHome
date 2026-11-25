package com.tunastream.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/** A clock with hands, in the theme's colours, beside (or instead of) the digital one. */
class ClockFace @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Again at the start of the next minute
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }

    init {
        val text = context.themeColor(R.attr.tsText)
        ring.color = text and 0x00FFFFFF or 0x66000000
        hand.color = text
        centre.color = context.themeColor(R.attr.tsAccent)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        hand.setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - size * 0.06f
        ring.strokeWidth = size * 0.04f
        canvas.drawCircle(cx, cy, r, ring)
        // Marks at 12, 3, 6 and 9
        hand.strokeWidth = size * 0.04f
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0)
            canvas.drawLine(
                cx + (r * 0.78f * Math.sin(a)).toFloat(), cy - (r * 0.78f * Math.cos(a)).toFloat(),
                cx + (r * 0.92f * Math.sin(a)).toFloat(), cy - (r * 0.92f * Math.cos(a)).toFloat(), hand,
            )
        }
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.MINUTE)
        val hours = now.get(Calendar.HOUR) + minutes / 60f
        drawHand(canvas, cx, cy, hours * 30f, r * 0.5f, size * 0.075f)
        drawHand(canvas, cx, cy, minutes * 6f, r * 0.78f, size * 0.05f)
        canvas.drawCircle(cx, cy, size * 0.06f, centre)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, width: Float) {
        val a = Math.toRadians(degrees.toDouble())
        hand.strokeWidth = width
        canvas.drawLine(cx, cy, cx + (length * Math.sin(a)).toFloat(), cy - (length * Math.cos(a)).toFloat(), hand)
    }
}
