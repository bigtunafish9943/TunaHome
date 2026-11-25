package com.tunastream.home

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shortcuts the viewer sets up: a button (or two together) pressed once, twice, three or four
 * times, or held, does something they chose. On the home screen any button can be used;
 * everywhere else only Home and Back can, through the Home button setting, because those are
 * the only presses TunaHome can hand back to the TV when they aren't a shortcut.
 */
object Hotkeys {
    private const val KEY = "hotkeys"

    /** How a button is pressed: 1 to 4 taps, or held. */
    const val HOLD = 0

    class Hotkey(
        /** One button, or two pressed together. */
        val keys: List<Int>,
        /** 1 to 4 taps, or [HOLD]. Two buttons together are always one press. */
        val presses: Int,
        val everywhere: Boolean,
        val action: String,
        /** The app or section the action is about, when it needs one. */
        val arg: String? = null,
    ) {
        val isCombo: Boolean
            get() = keys.size > 1
    }

    fun load(context: Context): MutableList<Hotkey> {
        val text = Prefs.of(context).getString(KEY, null) ?: return ArrayList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val keys = o.getJSONArray("keys").let { k -> (0 until k.length()).map { k.getInt(it) } }
                Hotkey(keys, o.getInt("presses"), o.optBoolean("everywhere"), o.getString("action"),
                    if (o.isNull("arg")) null else o.optString("arg", null))
            }.toMutableList()
        } catch (e: Exception) {
            ArrayList()
        }
    }

    fun save(context: Context, list: List<Hotkey>) {
        val array = JSONArray()
        for (h in list) {
            array.put(JSONObject().apply {
                put("keys", JSONArray(h.keys))
                put("presses", h.presses)
                put("everywhere", h.everywhere)
                put("action", h.action)
                put("arg", h.arg ?: JSONObject.NULL)
            })
        }
        Prefs.of(context).edit().putString(KEY, array.toString()).apply()
        cache = null
    }

    @Volatile
    private var cache: List<Hotkey>? = null

    fun all(context: Context): List<Hotkey> = cache ?: load(context).also { cache = it }

    /** Buttons that can be used everywhere: their ordinary press can be handed back. */
    fun canBeEverywhere(key: Int): Boolean = key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_BACK

    // ------------------------------------------------------------------ names

    fun keyName(context: Context, code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> context.getString(R.string.key_select)
        KeyEvent.KEYCODE_BACK -> context.getString(R.string.key_back)
        KeyEvent.KEYCODE_HOME -> context.getString(R.string.key_home)
        KeyEvent.KEYCODE_MENU -> context.getString(R.string.key_menu)
        KeyEvent.KEYCODE_DPAD_UP -> context.getString(R.string.key_up)
        KeyEvent.KEYCODE_DPAD_DOWN -> context.getString(R.string.key_down)
        KeyEvent.KEYCODE_DPAD_LEFT -> context.getString(R.string.key_left)
        KeyEvent.KEYCODE_DPAD_RIGHT -> context.getString(R.string.key_right)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> context.getString(R.string.key_play_pause)
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> context.getString(R.string.key_left_stick)
        KeyEvent.KEYCODE_BUTTON_THUMBR -> context.getString(R.string.key_right_stick)
        KeyEvent.KEYCODE_BUTTON_START -> context.getString(R.string.key_start)
        KeyEvent.KEYCODE_BUTTON_SELECT -> context.getString(R.string.key_view)
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    fun pressesName(context: Context, presses: Int): String = when (presses) {
        HOLD -> context.getString(R.string.press_hold)
        1 -> context.getString(R.string.press_once)
        2 -> context.getString(R.string.press_twice)
        3 -> context.getString(R.string.press_three)
        else -> context.getString(R.string.press_four)
    }

    /** "Y pressed twice", "L1 + R1 together", "Home held". */
    fun triggerName(context: Context, h: Hotkey): String =
        if (h.isCombo) context.getString(R.string.combo_name, h.keys.joinToString(" + ") { keyName(context, it) })
        else context.getString(R.string.trigger_name, keyName(context, h.keys[0]), pressesName(context, h.presses))
}
