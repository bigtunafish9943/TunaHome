package com.tunastream.home

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Turns button presses into shortcuts: taps counted within a short window, holds, and two
 * buttons pressed together. Presses of buttons with no shortcut pass straight through; a press
 * that turns out not to be a shortcut is handed back through [replay] so the button still does
 * its ordinary job (just a moment later, while the engine waited for a second tap).
 */
class HotkeyEngine(
    private val shortcuts: () -> List<Hotkeys.Hotkey>,
    private val fire: (Hotkeys.Hotkey) -> Unit,
    private val replay: (keyCode: Int, taps: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val held = LinkedHashSet<Int>()
    private val consumed = HashSet<Int>()
    private var tapKey = -1
    private var taps = 0
    private var holdFired = false
    private var holdRunnable: Runnable? = null
    private var windowRunnable: Runnable? = null

    /** Returns true when the event was taken (part of a shortcut, or waiting to see). */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val list = shortcuts()
        if (list.isEmpty()) {
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    return code in consumed
                }
                // Two buttons together
                for (other in held) {
                    val combo = list.firstOrNull { it.isCombo && it.keys.toSet() == setOf(other, code) }
                    if (combo != null) {
                        cancelTimers()
                        tapKey = -1
                        taps = 0
                        consumed.add(code)
                        consumed.add(other)
                        held.add(code)
                        fire(combo)
                        return true
                    }
                }
                held.add(code)
                val forKey = list.filter { !it.isCombo && it.keys[0] == code }
                if (forKey.isEmpty()) {
                    // No shortcut of its own (a combo's first button still does its own job
                    // unless the second follows)
                    return false
                }
                consumed.add(code)
                if (tapKey != code) {
                    flushPending()
                    tapKey = code
                    taps = 0
                }
                windowRunnable?.let { handler.removeCallbacks(it) }
                holdFired = false
                forKey.firstOrNull { it.presses == Hotkeys.HOLD }?.let { hold ->
                    val r = Runnable {
                        holdFired = true
                        taps = 0
                        fire(hold)
                    }
                    holdRunnable = r
                    handler.postDelayed(r, HOLD_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                held.remove(code)
                if (code !in consumed) {
                    return false
                }
                consumed.remove(code)
                holdRunnable?.let { handler.removeCallbacks(it) }
                holdRunnable = null
                if (holdFired || tapKey != code) {
                    holdFired = false
                    return true
                }
                taps++
                val taps = taps
                val forKey = list.filter { !it.isCombo && it.keys[0] == code && it.presses != Hotkeys.HOLD }
                val most = forKey.maxOfOrNull { it.presses } ?: 0
                if (taps >= most) {
                    // No more taps would change anything: act now
                    settle(code, taps, forKey)
                } else {
                    val r = Runnable { settle(code, taps, forKey) }
                    windowRunnable = r
                    handler.postDelayed(r, TAP_WINDOW_MS)
                }
                return true
            }
        }
        return false
    }

    private fun settle(code: Int, count: Int, forKey: List<Hotkeys.Hotkey>) {
        windowRunnable = null
        tapKey = -1
        taps = 0
        val match = forKey.firstOrNull { it.presses == count }
        if (match != null) fire(match) else replay(code, count)
    }

    /** A different button interrupts a tap count: finish the old one first. */
    private fun flushPending() {
        val r = windowRunnable ?: return
        handler.removeCallbacks(r)
        r.run()
    }

    private fun cancelTimers() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        windowRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        windowRunnable = null
    }

    fun reset() {
        cancelTimers()
        held.clear()
        consumed.clear()
        tapKey = -1
        taps = 0
    }

    companion object {
        const val TAP_WINDOW_MS = 320L
        const val HOLD_MS = 600L
    }
}
