package com.tunastream.home

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A menu down the left of the home screen. Pressing Left on the first app of a line opens it:
 * it lists the sections (choosing one jumps there) and a few shortcuts. Right or Back closes it
 * and puts you back where you were. When closed it can leave just its icons showing, or nothing.
 */
class SideMenu(private val activity: Activity) {
    class Entry(val icon: Int, val label: String, val sectionId: String?, val action: () -> Unit)

    private val panel: View = activity.findViewById(R.id.sideMenu)
    private val shade: View = activity.findViewById(R.id.sideMenuShade)
    private val items: LinearLayout = activity.findViewById(R.id.sideMenuItems)
    private val mode = Look.SIDE_MENU.get(activity)
    private val views = ArrayList<View>()
    private val entries = ArrayList<Entry>()
    private var returnTo: View? = null

    var isOpen = false
        private set

    val enabled: Boolean
        get() = mode != Look.MENU_OFF

    /** With its icons showing when closed, the home screen leaves room for them. */
    val showsRail: Boolean
        get() = mode == Look.MENU_RAIL

    fun setEntries(list: List<Entry>) {
        items.removeAllViews()
        views.clear()
        entries.clear()
        if (!enabled) {
            panel.visibility = View.GONE
            return
        }
        for (entry in list) {
            if (entry.icon == 0) {
                // A gap between the sections and the shortcuts
                val gap = View(activity)
                gap.layoutParams = LinearLayout.LayoutParams(1, Math.round(Look.dp(activity, 18f)))
                items.addView(gap)
                continue
            }
            val view = activity.layoutInflater.inflate(R.layout.item_side_menu, items, false)
            view.findViewById<ImageView>(R.id.menuIcon).setImageResource(entry.icon)
            view.findViewById<TextView>(R.id.menuLabel).text = entry.label
            view.contentDescription = entry.label
            view.setOnClickListener {
                close(false)
                entry.action()
            }
            Look.styleText(view)
            items.addView(view)
            views.add(view)
            entries.add(entry)
        }
        showClosed()
    }

    private fun showClosed() {
        panel.visibility = if (showsRail) View.VISIBLE else View.GONE
        items.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        views.forEach {
            it.findViewById<View>(R.id.menuLabel).visibility = View.GONE
            it.alpha = 0.5f
        }
        shade.alpha = 0f
    }

    /** Opens the menu with the focus on [sectionId]'s entry, or the first one. */
    fun open(sectionId: String?) {
        if (!enabled || views.isEmpty()) {
            return
        }
        isOpen = true
        returnTo = activity.currentFocus
        panel.visibility = View.VISIBLE
        items.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        views.forEach {
            it.findViewById<View>(R.id.menuLabel).visibility = View.VISIBLE
            it.animate().alpha(1f).setDuration(150).start()
        }
        shade.animate().alpha(1f).setDuration(200).start()
        val at = entries.indexOfFirst { it.sectionId != null && it.sectionId == sectionId }
        (views.getOrNull(at) ?: views.first()).requestFocus()
    }

    /** Closes the menu, putting the focus back where it was if [restore]. */
    fun close(restore: Boolean) {
        if (!isOpen) {
            return
        }
        isOpen = false
        showClosed()
        if (restore) {
            returnTo?.requestFocus()
        }
        returnTo = null
    }

    /**
     * Keys while the menu is open. Up and down move through its entries (stopping at the ends)
     * and never leave it; Left does nothing; Right and Back close it.
     */
    fun key(event: KeyEvent): Boolean {
        if (!isOpen) {
            return false
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) {
                    val at = views.indexOf(activity.currentFocus).let { if (it < 0) 0 else it }
                    val next = (if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) at - 1 else at + 1)
                        .coerceIn(0, views.size - 1)
                    views[next].requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                close(true)
            }
            return true
        }
        return false
    }
}
