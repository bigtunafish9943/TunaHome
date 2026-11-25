package com.tunastream.home

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * A row or grid of apps. Select opens an app. Holding select, or pressing Menu on a remote or
 * X / Y on a controller, opens the app's menu.
 */
class AppAdapter(
    private val grid: Boolean,
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    init {
        // Lets the list keep you on the same app when it is rebuilt
        setHasStableIds(true)
    }

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    fun positionOf(pkg: String): Int = items.indexOfFirst { it.pkg == pkg }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].pkg.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        if (grid) {
            // In the grid each tile fills its column
            view.layoutParams = view.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val ring: View = view.findViewById(R.id.ring)
        private val label: TextView = view.findViewById(R.id.label)
        private val textFocused = view.context.themeColor(R.attr.tsText)
        private val textResting = view.context.themeColor(R.attr.tsTextSecondary)
        private var entry: AppEntry? = null

        init {
            // Round the banner's corners to match the card
            card.clipToOutline = true

            view.setOnFocusChangeListener { v, focused -> showFocus(v, focused, true) }
            view.setOnClickListener { entry?.let(onOpen) }
            view.setOnLongClickListener {
                entry?.let(onMenu)
                true
            }
            view.setOnKeyListener { _, keyCode, event ->
                val menuKey = keyCode == KeyEvent.KEYCODE_MENU ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_X
                if (menuKey) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        entry?.let(onMenu)
                    }
                    true
                } else {
                    false
                }
            }
        }

        fun bind(app: AppEntry) {
            entry = app
            label.text = app.label
            itemView.contentDescription = app.label
            banner.setImageDrawable(app.banner)
            icon.setImageDrawable(app.icon)
            icon.visibility = if (app.banner == null) View.VISIBLE else View.GONE
            showFocus(itemView, itemView.isFocused, false)
        }

        private fun showFocus(view: View, focused: Boolean, animate: Boolean) {
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            label.setTextColor(if (focused) textFocused else textResting)
            val scale = if (focused) 1.08f else 1f
            if (animate) {
                view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            } else {
                view.animate().cancel()
                view.scaleX = scale
                view.scaleY = scale
            }
        }
    }
}
