package com.tunastream.home

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * A row of apps. The app you are on is kept at the start of the row. Select opens an app.
 * Holding select, or pressing Menu on a remote or X / Y on a controller, opens the app's menu.
 *
 * An app can be picked up with [startMove]: left and right then slide it along the row, and
 * select or Back puts it down, reporting the row's new order through [onMoved].
 */
class AppAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
    private val onMoved: (List<String>) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private val items = ArrayList<AppEntry>()
    private var recycler: RecyclerView? = null

    /** The app being moved, if any. */
    private var movingPkg: String? = null

    init {
        // Lets the list keep you on the same app when it is rebuilt
        setHasStableIds(true)
    }

    fun submit(list: List<AppEntry>) {
        movingPkg = null
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun positionOf(pkg: String): Int = items.indexOfFirst { it.pkg == pkg }

    /** Picks an app up so it can be slid along the row. */
    fun startMove(pkg: String): Boolean {
        val position = positionOf(pkg)
        val holder = recycler?.findViewHolderForAdapterPosition(position) as? Holder ?: return false
        movingPkg = pkg
        holder.itemView.requestFocus()
        holder.refresh()
        return true
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].pkg.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        // The viewer's chosen picture size and text style
        val scale = Look.tileScale(parent.context)
        if (scale != 1f) {
            val res = parent.resources
            view.layoutParams = view.layoutParams.apply {
                width = Math.round(res.getDimension(R.dimen.tile_width) * scale)
            }
            val card = view.findViewById<View>(R.id.card)
            card.layoutParams = card.layoutParams.apply {
                height = Math.round(res.getDimension(R.dimen.tile_height) * scale)
            }
        }
        Look.styleText(view)
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
        private var entry: AppEntry? = null

        private val moving: Boolean
            get() = entry != null && entry?.pkg == movingPkg

        init {
            // Round the banner's corners to match the card
            card.clipToOutline = true

            view.setOnFocusChangeListener { v, focused ->
                if (!focused && moving) {
                    // Something took over the screen mid-move: put the app down where it is
                    finishMove()
                }
                showFocus(focused, true)
                if (focused) {
                    // Slide the row so this app sits at its start, like the row's title
                    val row = v.parent as? RecyclerView
                    if (row != null && !moving) {
                        val gap = (v.layoutParams as ViewGroup.MarginLayoutParams).leftMargin
                        row.smoothScrollBy(v.left - gap - row.paddingLeft, 0)
                    }
                }
            }
            view.setOnClickListener { entry?.let(onOpen) }
            view.setOnLongClickListener {
                entry?.let(onMenu)
                true
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (moving) {
                    moveKey(keyCode, event)
                } else {
                    val menuKey = keyCode == KeyEvent.KEYCODE_MENU ||
                        keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
                        keyCode == KeyEvent.KEYCODE_BUTTON_X
                    if (menuKey && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        entry?.let(onMenu)
                    }
                    menuKey
                }
            }
        }

        fun bind(app: AppEntry) {
            entry = app
            itemView.contentDescription = app.label
            banner.setImageDrawable(app.banner)
            icon.setImageDrawable(app.icon)
            icon.visibility = if (app.banner == null) View.VISIBLE else View.GONE
            showFocus(itemView.isFocused, false)
        }

        /** Redraws the tile after it was picked up or put down. */
        fun refresh() {
            showFocus(itemView.isFocused, true)
        }

        private fun showFocus(focused: Boolean, animate: Boolean) {
            val name = entry?.label ?: ""
            // Arrows either side of the name show that left and right now move the app
            label.text = if (moving) "◀  $name  ▶" else name
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            label.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            val scale = if (!focused) 1f else if (moving) 1.2f else 1.1f
            if (animate) {
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            } else {
                itemView.animate().cancel()
                itemView.scaleX = scale
                itemView.scaleY = scale
            }
        }

        /** While an app is picked up, the remote moves it instead of moving around the screen. */
        private fun moveKey(keyCode: Int, event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) shift(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) shift(1)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE ->
                    if (!down) finishMove()
                else -> return false
            }
            return true
        }

        private fun shift(by: Int) {
            val from = bindingAdapterPosition
            val to = from + by
            if (from < 0 || to < 0 || to >= items.size) {
                return
            }
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
            recycler?.scrollToPosition(to)
        }

        private fun finishMove() {
            movingPkg = null
            refresh()
            onMoved(items.map { it.pkg })
        }
    }
}
