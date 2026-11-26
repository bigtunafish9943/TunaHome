package com.tunastream.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picture test: test patterns for setting up the TV's picture (black and white level, colour,
 * banding, sharpness and overscan, smooth motion), with the screen mode the TV box is sending.
 * Left and Right change pattern, select shows or hides the notes, Back leaves.
 */
class PictureTestActivity : BaseActivity() {
    private lateinit var view: PatternView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PatternView(this)
        setContentView(view)
        view.isFocusable = true
        view.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        super.onPause()
        view.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> view.next(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_MEDIA_REWIND -> view.next(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> view.toggleNotes()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Moves are handled above, quietly: no focus to move here. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            return onKeyDown(event.keyCode, event)
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return true
    }

    private class PatternView(context: Context) : View(context), Choreographer.FrameCallback {
        private val names = intArrayOf(
            R.string.pt_black, R.string.pt_white, R.string.pt_colour, R.string.pt_gradient,
            R.string.pt_edges, R.string.pt_motion,
        )
        private val tips = intArrayOf(
            R.string.pt_black_tip, R.string.pt_white_tip, R.string.pt_colour_tip, R.string.pt_gradient_tip,
            R.string.pt_edges_tip, R.string.pt_motion_tip,
        )
        private var pattern = 0
        private var notes = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val dp = resources.displayMetrics.density

        // Motion: where the bar is, and frames that came late
        private var running = false
        private var lastFrameNs = 0L
        private var startNs = 0L
        private var frames = 0
        private var skipped = 0
        private var frameNs = 16_666_667L

        fun start() {
            if (!running) {
                running = true
                resetMotion()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
        }

        fun next(step: Int) {
            pattern = (pattern + step + names.size) % names.size
            resetMotion()
            Sounds.play(context, Sounds.MOVE)
            invalidate()
        }

        fun toggleNotes() {
            notes = !notes
            invalidate()
        }

        private fun resetMotion() {
            lastFrameNs = 0
            startNs = 0
            frames = 0
            skipped = 0
            val hz = display()?.refreshRate ?: 60f
            frameNs = (1_000_000_000.0 / hz).toLong()
        }

        @Suppress("DEPRECATION")
        private fun display(): Display? = if (Build.VERSION.SDK_INT >= 30) context.display
            else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) {
                return
            }
            if (pattern == MOTION) {
                if (startNs == 0L) {
                    startNs = frameTimeNanos
                } else if (lastFrameNs != 0L) {
                    frames++
                    val gap = frameTimeNanos - lastFrameNs
                    if (gap > frameNs * 3 / 2) {
                        skipped += ((gap + frameNs / 2) / frameNs - 1).toInt()
                    }
                }
                lastFrameNs = frameTimeNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(Color.BLACK)
            when (pattern) {
                0 -> steps(canvas, w, h, 0, 2, Color.rgb(90, 90, 90))
                1 -> steps(canvas, w, h, 225, 2, Color.rgb(140, 140, 140))
                2 -> colourBars(canvas, w, h)
                3 -> gradients(canvas, w, h)
                4 -> edges(canvas, w, h)
                MOTION -> motion(canvas, w, h)
            }
            if (notes) {
                notes(canvas, w, h)
            }
        }

        /** Sixteen bars, [from] upwards by [step], numbered underneath. */
        private fun steps(canvas: Canvas, w: Float, h: Float, from: Int, step: Int, labelColour: Int) {
            if (from > 0) {
                canvas.drawColor(Color.rgb(255, 255, 255))
            }
            val count = 16
            val left = w * 0.06f
            val barW = (w - 2 * left) / count
            val top = h * 0.22f
            val bottom = h * 0.62f
            text.textSize = 15 * dp
            text.textAlign = Paint.Align.CENTER
            text.color = labelColour
            for (i in 0 until count) {
                val v = (from + i * step).coerceAtMost(255)
                paint.color = Color.rgb(v, v, v)
                canvas.drawRect(left + i * barW, top, left + (i + 1) * barW, bottom, paint)
                canvas.drawText(v.toString(), left + (i + 0.5f) * barW, bottom + 26 * dp, text)
            }
        }

        private fun colourBars(canvas: Canvas, w: Float, h: Float) {
            // 75% bars over full-strength ones
            val top = intArrayOf(
                Color.rgb(191, 191, 191), Color.rgb(191, 191, 0), Color.rgb(0, 191, 191), Color.rgb(0, 191, 0),
                Color.rgb(191, 0, 191), Color.rgb(191, 0, 0), Color.rgb(0, 0, 191),
            )
            val full = intArrayOf(
                Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE,
            )
            val barW = w / top.size
            for (i in top.indices) {
                paint.color = top[i]
                canvas.drawRect(i * barW, 0f, (i + 1) * barW, h * 0.6f, paint)
                paint.color = full[i]
                canvas.drawRect(i * barW, h * 0.6f, (i + 1) * barW, h * 0.8f, paint)
            }
            // Skin tones and greys along the bottom, for judging colour by eye
            val bottom = intArrayOf(
                Color.rgb(255, 224, 196), Color.rgb(234, 192, 134), Color.rgb(198, 134, 66),
                Color.rgb(141, 85, 36), Color.rgb(64, 64, 64), Color.rgb(128, 128, 128), Color.rgb(32, 32, 32),
            )
            for (i in bottom.indices) {
                paint.color = bottom[i]
                canvas.drawRect(i * barW, h * 0.8f, (i + 1) * barW, h, paint)
            }
        }

        private fun gradients(canvas: Canvas, w: Float, h: Float) {
            val rows = intArrayOf(Color.WHITE, Color.RED, Color.GREEN, Color.BLUE)
            val rowH = h / rows.size
            for (i in rows.indices) {
                paint.shader = LinearGradient(0f, 0f, w, 0f, Color.BLACK, rows[i], Shader.TileMode.CLAMP)
                canvas.drawRect(0f, i * rowH, w, (i + 1) * rowH, paint)
            }
            paint.shader = null
        }

        private fun edges(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.rgb(40, 40, 40))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            // Outlines at the very edge, then 2.5% and 5% in
            val insets = floatArrayOf(0f, 0.025f, 0.05f)
            val colours = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN)
            for (i in insets.indices) {
                paint.color = colours[i]
                val x = w * insets[i]
                val y = h * insets[i]
                canvas.drawRect(x + 0.5f, y + 0.5f, w - x - 0.5f, h - y - 0.5f, paint)
            }
            paint.style = Paint.Style.FILL
            // One-pixel lines in the middle, for sharpness: halos round them mean too much
            val box = (h * 0.34f).roundToInt()
            val cx = (w / 2).roundToInt() - box
            val cy = (h / 2).roundToInt() - box / 2
            paint.color = Color.BLACK
            canvas.drawRect(cx.toFloat(), cy.toFloat(), (cx + 2 * box).toFloat(), (cy + box).toFloat(), paint)
            paint.color = Color.WHITE
            var x = cx
            while (x < cx + box) {
                canvas.drawRect(x.toFloat(), cy.toFloat(), x + 1f, (cy + box).toFloat(), paint)
                x += 2
            }
            var y = cy
            while (y < cy + box) {
                canvas.drawRect((cx + box).toFloat(), y.toFloat(), (cx + 2 * box).toFloat(), y + 1f, paint)
                y += 2
            }
        }

        private fun motion(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.rgb(24, 24, 24))
            // Four seconds across the screen and back, a fixed step per frame
            val periodFrames = (4_000_000_000L / frameNs).coerceAtLeast(1)
            val t = ((frames + skipped) % (2 * periodFrames)).toFloat() / periodFrames
            val pos = if (t <= 1f) t else 2f - t
            val barW = w * 0.06f
            val x = pos * (w - barW)
            paint.color = Color.WHITE
            canvas.drawRect(x, h * 0.3f, x + barW, h * 0.7f, paint)
            // Ticks to judge the steps against
            paint.color = Color.rgb(90, 90, 90)
            var tx = 0f
            while (tx <= w) {
                canvas.drawRect(tx, h * 0.72f, tx + 2, h * 0.75f, paint)
                tx += w / 24
            }
        }

        private fun notes(canvas: Canvas, w: Float, h: Float) {
            val res = context.resources
            val lines = mutableListOf(
                "${res.getString(names[pattern])}  (${pattern + 1}/${names.size})",
                res.getString(tips[pattern]),
                mode(),
            )
            if (pattern == MOTION && frames > 0) {
                lines.add(res.getString(R.string.pt_skipped, skipped, frames + skipped))
            }
            lines.add(res.getString(R.string.pt_keys))
            text.textAlign = Paint.Align.LEFT
            val size = 15 * dp
            val pad = 14 * dp
            val boxH = pad * 2 + lines.size * size * 1.45f
            val boxW = (w * 0.72f).coerceAtMost(900 * dp)
            val left = (w - boxW) / 2
            val top = h - boxH - 24 * dp
            paint.color = Color.argb(205, 0, 0, 0)
            canvas.drawRoundRect(left, top, left + boxW, top + boxH, 12 * dp, 12 * dp, paint)
            for ((i, line) in lines.withIndex()) {
                text.textSize = if (i == 0) size * 1.1f else size
                text.color = if (i == 0) Color.WHITE else Color.rgb(205, 205, 205)
                text.isFakeBoldText = i == 0
                canvas.drawText(fit(line, boxW - 2 * pad), left + pad, top + pad + size + i * size * 1.45f, text)
            }
            text.isFakeBoldText = false
        }

        private fun fit(line: String, max: Float): String {
            if (text.measureText(line) <= max) return line
            var s = line
            while (s.length > 4 && text.measureText("$s…") > max) s = s.dropLast(1)
            return "$s…"
        }

        /** "3840 × 2160 at 59.94 Hz. Also: 23.976, 24, 50, 60 Hz. HDR: HDR10, HLG". */
        private fun mode(): String {
            val d = display() ?: return ""
            val res = context.resources
            val m = d.mode
            val rates = d.supportedModes
                .filter { it.physicalWidth == m.physicalWidth && it.physicalHeight == m.physicalHeight }
                .map { it.refreshRate }
                .filter { abs(it - m.refreshRate) > 0.01f }
                .distinct()
                .sorted()
                .joinToString(", ") { hz(it) }
            var s = res.getString(R.string.pt_mode, m.physicalWidth, m.physicalHeight, hz(m.refreshRate))
            if (rates.isNotEmpty()) {
                s += " " + res.getString(R.string.pt_mode_also, rates)
            }
            if (Build.VERSION.SDK_INT >= 24) {
                val hdr = d.hdrCapabilities?.supportedHdrTypes?.mapNotNull {
                    when (it) {
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                        4 -> "HDR10+"
                        else -> null
                    }
                }.orEmpty()
                if (hdr.isNotEmpty()) {
                    s += " " + res.getString(R.string.pt_mode_hdr, hdr.joinToString(", "))
                }
            }
            return s
        }

        private fun hz(rate: Float): String {
            val r = (rate * 1000).roundToInt() / 1000f
            return if (abs(r - r.roundToInt()) < 0.005f) r.roundToInt().toString()
            else String.format(Locale.US, "%.3f", r).trimEnd('0').trimEnd('.')
        }

        companion object {
            const val MOTION = 5
        }
    }
}
