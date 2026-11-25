package com.tunastream.home

import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * One section of apps. It shows its apps on [lines] lines (or as many as needed), filling each
 * line across before starting the next, like reading. Select opens an app. Holding select, or
 * pressing Menu on a remote or X / Y on a controller, opens the app's menu.
 *
 * An app can be picked up with [startMove]: the D-pad then moves it, and select or Back puts it
 * down, reporting the section's new order through [onMoved].
 */
class AppAdapter(
    private val tiles: TileStyle,
    /** 1 to 5, or [Rows.WRAP] for as many lines as needed with no sideways scrolling. */
    private val lines: Int,
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
    private val onMoved: (List<String>) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private val items = ArrayList<AppEntry>()
    private var recycler: RecyclerView? = null

    /** For [Rows.WRAP]: how many apps fit across, set by the home screen once it is measured. */
    var wrapColumns = 1
        set(value) {
            field = maxOf(1, value)
        }

    /** The app being moved, if any. */
    private var movingPkg: String? = null

    /** True while a moved app's tile is being found again after the section was redrawn. */
    private var shifting = false

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

    val appCount: Int
        get() = items.size

    // ------------------------------------------------------------------ lines
    //
    // A sideways-scrolling grid lays out down each column first. To read across each line
    // instead, grid position and app order are mapped onto each other here; the last line can
    // be shorter, so some grid cells are left empty.

    private val sideways: Boolean
        get() = lines > 1

    /** Apps on the longest line. */
    private val columns: Int
        get() = if (!sideways) items.size else (items.size + lines - 1) / lines

    private fun appAt(position: Int): Int {
        if (!sideways) {
            return position
        }
        val line = position % lines
        val column = position / lines
        return line * columns + column
    }

    fun positionOfApp(index: Int): Int {
        if (!sideways || index < 0) {
            return index
        }
        return (index % columns) * lines + index / columns
    }

    fun positionOf(pkg: String): Int = positionOfApp(items.indexOfFirst { it.pkg == pkg })

    override fun getItemCount(): Int = if (sideways) columns * lines else items.size

    override fun getItemId(position: Int): Long {
        val index = appAt(position)
        return if (index < items.size) items[index].pkg.hashCode().toLong() else -1L - position
    }

    // ------------------------------------------------------------------ moving an app

    /** Picks an app up so the D-pad moves it. */
    fun startMove(pkg: String): Boolean {
        val position = positionOf(pkg)
        val holder = recycler?.findViewHolderForAdapterPosition(position) as? Holder ?: return false
        movingPkg = pkg
        holder.itemView.requestFocus()
        holder.refresh()
        return true
    }

    private fun shift(pkg: String, by: Int) {
        val from = items.indexOfFirst { it.pkg == pkg }
        val to = from + by
        if (from < 0 || to < 0 || to >= items.size) {
            return
        }
        items.add(to, items.removeAt(from))
        if (!sideways) {
            notifyItemMoved(from, to)
        } else {
            notifyDataSetChanged()
        }
        val position = positionOfApp(to)
        val list = recycler ?: return
        shifting = true
        list.scrollToPosition(position)
        // Twice: once for the redraw to happen, once to find the tile in its new place
        list.post {
            list.post {
                list.layoutManager?.findViewByPosition(position)?.requestFocus()
                shifting = false
            }
        }
    }

    private fun finishMove() {
        if (movingPkg == null) {
            return
        }
        val was = movingPkg
        movingPkg = null
        recycler?.let { list ->
            val position = positionOf(was ?: "")
            (list.findViewHolderForAdapterPosition(position) as? Holder)?.refresh()
        }
        onMoved(items.map { it.pkg })
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    // ------------------------------------------------------------------ tiles

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val context = parent.context

        val card = view.findViewById<View>(R.id.card)
        card.layoutParams = card.layoutParams.apply {
            width = tiles.width
            height = tiles.height
        }
        card.background = tiles.card(context)
        view.findViewById<View>(R.id.ring).background = tiles.ring()
        view.layoutParams = RecyclerView.LayoutParams(
            if (lines == Rows.WRAP) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(tiles.gap, tiles.gap, tiles.gap, tiles.gap) }
        if (lines == Rows.WRAP) {
            // In a wrapping grid the tile sits in the middle of its column
            (view as android.widget.LinearLayout).setGravity(Gravity.CENTER_HORIZONTAL)
        }

        val icon = view.findViewById<View>(R.id.icon)
        icon.layoutParams = (icon.layoutParams as FrameLayout.LayoutParams).apply {
            width = tiles.iconSize
            height = tiles.iconSize
            gravity = if (tiles.style == Look.TILE_ICON_NAME) Gravity.CENTER_HORIZONTAL or Gravity.TOP
            else Gravity.CENTER
            if (tiles.style == Look.TILE_ICON_NAME) {
                topMargin = Math.round(tiles.height * 0.14f)
            }
        }
        val label = view.findViewById<TextView>(R.id.label)
        label.layoutParams = label.layoutParams.apply { width = tiles.width }
        if (tiles.darkCard) {
            view.findViewById<TextView>(R.id.cardLabel).setTextColor(Color.rgb(0x15, 0x1A, 0x22))
        }
        Look.styleText(view)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val index = appAt(position)
        holder.bind(if (index < items.size) items[index] else null)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val ring: View = view.findViewById(R.id.ring)
        private val label: TextView = view.findViewById(R.id.label)
        private val cardLabel: TextView = view.findViewById(R.id.cardLabel)
        private var entry: AppEntry? = null

        private val moving: Boolean
            get() = entry != null && entry?.pkg == movingPkg

        init {
            // Round the picture's corners to match the card
            card.clipToOutline = true

            view.setOnFocusChangeListener { v, focused ->
                if (!focused && moving && !shifting) {
                    // Something else took the focus mid-move: put the app down where it is
                    finishMove()
                }
                showFocus(focused, true)
                if (focused && !moving) {
                    keepInView(v)
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

        /** Slides a sideways list so the app you're on sits where the viewer chose. */
        private fun keepInView(v: View) {
            if (lines == Rows.WRAP || tiles.scroll == Look.SCROLL_FREE) {
                return
            }
            val list = v.parent as? RecyclerView ?: return
            val target = if (tiles.scroll == Look.SCROLL_CENTRE) {
                (list.width - v.width) / 2
            } else {
                list.paddingLeft + tiles.gap
            }
            list.smoothScrollBy(v.left - target, 0)
        }

        fun bind(app: AppEntry?) {
            entry = app
            if (app == null) {
                // An empty cell at the end of a shorter last line
                itemView.visibility = View.INVISIBLE
                itemView.isFocusable = false
                return
            }
            itemView.visibility = View.VISIBLE
            itemView.isFocusable = true
            itemView.contentDescription = app.label
            cardLabel.text = app.label

            val showBanner = tiles.style == Look.TILE_BANNER && app.banner != null
            banner.setImageDrawable(if (showBanner) app.banner else null)
            banner.visibility = if (showBanner) View.VISIBLE else View.GONE
            icon.setImageDrawable(if (showBanner) null else app.icon)
            icon.visibility = if (showBanner) View.GONE else View.VISIBLE
            cardLabel.visibility = if (tiles.style == Look.TILE_ICON_NAME) View.VISIBLE else View.GONE
            showFocus(itemView.isFocused, false)
        }

        /** Redraws the tile after it was picked up or put down. */
        fun refresh() {
            showFocus(itemView.isFocused, true)
        }

        private fun showFocus(focused: Boolean, animate: Boolean) {
            val name = entry?.label ?: ""
            // Arrows either side of the name show that the D-pad now moves the app
            label.text = if (moving) "◀  $name  ▶" else name
            ring.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            val showName = moving || when (tiles.names) {
                Look.NAMES_ALWAYS -> true
                Look.NAMES_NEVER -> false
                else -> focused
            }
            // "Never" and the name-in-card style take the space under the tile away altogether
            label.visibility = when {
                showName -> View.VISIBLE
                tiles.names == Look.NAMES_NEVER || tiles.style == Look.TILE_ICON_NAME -> View.GONE
                else -> View.INVISIBLE
            }
            if (tiles.style == Look.TILE_ICON_NAME && !moving) {
                label.visibility = View.GONE
            }
            itemView.alpha = if (tiles.dimOthers && !focused) 0.55f else 1f
            val scale = if (!focused) 1f else if (moving) maxOf(tiles.zoom, 1.1f) + 0.08f else tiles.zoom
            if (animate) {
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            } else {
                itemView.animate().cancel()
                itemView.scaleX = scale
                itemView.scaleY = scale
            }
        }

        /** While an app is picked up, the D-pad moves it instead of moving around the screen. */
        private fun moveKey(keyCode: Int, event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            val pkg = entry?.pkg ?: return false
            val across = if (lines == Rows.WRAP) wrapColumns else columns
            val multiLine = sideways || lines == Rows.WRAP
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) shift(pkg, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) shift(pkg, 1)
                KeyEvent.KEYCODE_DPAD_UP -> if (down && multiLine) shift(pkg, -across)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down && multiLine) shift(pkg, across)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE ->
                    if (!down) finishMove()
                else -> return false
            }
            return true
        }
    }
}
