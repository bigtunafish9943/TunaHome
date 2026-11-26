package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * A pointer for apps made for phones, that the D-pad can't reach everything in: the D-pad moves
 * an arrow over the screen, select taps where it points (held, a long press), pushing past an
 * edge scrolls, and Back turns it off. Switched on with a shortcut (TunaHome settings, Shortcut
 * keys). Taps are made through the Home button service, which Android allows for accessibility
 * services; nothing is read from the screen.
 */
class RemotePointer(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = service.resources.displayMetrics.density
    private var arrow: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var x = 0f
    private var y = 0f

    private var held = 0
    private var heldSince = 0L
    private var edgeSince = 0L
    private var lastScroll = 0L
    private var selectDown = 0L
    private var longPressed = false

    val isOn: Boolean get() = arrow != null

    fun toggle() = if (isOn) off(true) else on()

    fun on() {
        if (isOn) return
        if (Build.VERSION.SDK_INT < 24) {
            Toast.makeText(service, R.string.pointer_needs_android_7, Toast.LENGTH_LONG).show()
            return
        }
        val size = service.resources.displayMetrics
        x = size.widthPixels / 2f
        y = size.heightPixels / 2f
        val view = ArrowView(service)
        val lp = WindowManager.LayoutParams(
            (28 * dp).toInt(), (28 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Toast.makeText(service, R.string.act_failed, Toast.LENGTH_LONG).show()
            return
        }
        arrow = view
        params = lp
        place()
        Toast.makeText(service, R.string.pointer_on, Toast.LENGTH_LONG).show()
    }

    fun off(say: Boolean) {
        val view = arrow ?: return
        main.removeCallbacks(mover)
        main.removeCallbacks(longPress)
        held = 0
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            // Already gone
        }
        arrow = null
        params = null
        if (say) {
            Toast.makeText(service, R.string.pointer_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun place() {
        val lp = params ?: return
        val view = arrow ?: return
        lp.x = x.toInt()
        lp.y = y.toInt()
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            // The overlay went away (the service stopped)
        }
    }

    /** True when the press was the pointer's. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isOn) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            else -> 0
        }
        if (dir != 0) {
            if (down) {
                if (held and dir == 0) {
                    if (held == 0) heldSince = SystemClock.uptimeMillis()
                    held = held or dir
                    step()
                    main.removeCallbacks(mover)
                    main.postDelayed(mover, FRAME_MS)
                }
            } else {
                held = held and dir.inv()
                if (held == 0) {
                    main.removeCallbacks(mover)
                    edgeSince = 0
                }
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        selectDown = SystemClock.uptimeMillis()
                        longPressed = false
                        main.postDelayed(longPress, LONG_PRESS_MS)
                    }
                } else {
                    main.removeCallbacks(longPress)
                    if (!longPressed) gesture(x, y, x, y, 40)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                if (!down) off(true)
                return true
            }
            KeyEvent.KEYCODE_HOME -> {
                off(false)
                return false
            }
        }
        return false
    }

    private val longPress = Runnable {
        longPressed = true
        gesture(x, y, x, y, 900)
    }

    private val mover = object : Runnable {
        override fun run() {
            if (held == 0 || !isOn) return
            step()
            main.postDelayed(this, FRAME_MS)
        }
    }

    /** One step in the held directions, quicker the longer they're held. */
    private fun step() {
        val heldFor = SystemClock.uptimeMillis() - heldSince
        val speed = dp * when {
            heldFor < 250 -> 4f
            heldFor < 700 -> 9f
            heldFor < 1400 -> 16f
            else -> 26f
        }
        var dx = 0f
        var dy = 0f
        if (held and LEFT != 0) dx -= speed
        if (held and RIGHT != 0) dx += speed
        if (held and UP != 0) dy -= speed
        if (held and DOWN != 0) dy += speed
        val m = service.resources.displayMetrics
        val maxX = m.widthPixels - 1f
        val maxY = m.heightPixels - 1f
        val nx = (x + dx).coerceIn(0f, maxX)
        val ny = (y + dy).coerceIn(0f, maxY)
        val pushing = (dx < 0 && x <= 0f) || (dx > 0 && x >= maxX) || (dy < 0 && y <= 0f) || (dy > 0 && y >= maxY)
        x = nx
        y = ny
        place()
        if (pushing) {
            val now = SystemClock.uptimeMillis()
            if (edgeSince == 0L) edgeSince = now
            if (now - edgeSince > EDGE_WAIT_MS && now - lastScroll > SCROLL_EVERY_MS) {
                lastScroll = now
                scroll(dx, dy, maxX, maxY)
            }
        } else {
            edgeSince = 0
        }
    }

    /** Pushing down past the bottom scrolls down: a swipe up from the pointer, as a finger would. */
    private fun scroll(dx: Float, dy: Float, maxX: Float, maxY: Float) {
        val dist = 0.35f
        when {
            dy > 0 -> gesture(x, maxY * 0.7f, x, maxY * (0.7f - dist), 250)
            dy < 0 -> gesture(x, maxY * 0.3f, x, maxY * (0.3f + dist), 250)
            dx > 0 -> gesture(maxX * 0.7f, y, maxX * (0.7f - dist), y, 250)
            dx < 0 -> gesture(maxX * 0.3f, y, maxX * (0.3f + dist), y, 250)
        }
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 24) return
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val sent = try {
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(),
                null, null,
            )
        } catch (e: Exception) {
            false
        }
        if (!sent) {
            // Android hasn't given the service taps (switched on before it asked for them)
            Toast.makeText(service, R.string.pointer_no_taps, Toast.LENGTH_LONG).show()
        }
    }

    /** An arrow with a dark outline, readable on any picture. */
    private class ArrowView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            line.strokeWidth = s / 14
            path.reset()
            path.moveTo(s * 0.06f, s * 0.04f)
            path.lineTo(s * 0.06f, s * 0.8f)
            path.lineTo(s * 0.26f, s * 0.62f)
            path.lineTo(s * 0.42f, s * 0.95f)
            path.lineTo(s * 0.56f, s * 0.88f)
            path.lineTo(s * 0.41f, s * 0.57f)
            path.lineTo(s * 0.68f, s * 0.57f)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
    }

    companion object {
        private const val UP = 1
        private const val DOWN = 2
        private const val LEFT = 4
        private const val RIGHT = 8
        private const val FRAME_MS = 16L
        private const val LONG_PRESS_MS = 600L
        private const val EDGE_WAIT_MS = 250L
        private const val SCROLL_EVERY_MS = 450L
    }
}
