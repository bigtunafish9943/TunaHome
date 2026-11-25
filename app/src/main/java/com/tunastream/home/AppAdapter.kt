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
 * An app is picked up by pressing select twice quickly, or with Move in its menu. While it is
 * picked up the home screen hands every D-pad press to [moveKey]: each press swaps it with the
 * app beside, above or below it, and select or Back puts it down, reporting the section's new
 * order through [onMoved].
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

    val isMoving: Boolean
        get() = movingPkg != null

    /** False for sections in an order of their own (Watch Next, Recently opened). */
    var canMove = true

    /** The first app shown twice the size. Single-line sections only. */
    var spotlight = false

    private val spotlightOn: Boolean
        get() = spotlight && lines == 1

    /** Called when the viewer moves onto an app. */
    var onFocused: ((AppEntry) -> Unit)? = null

    /** The app shown at [position], if any. */
    fun appAtPosition(position: Int): AppEntry? = items.getOrNull(appAt(position))

    /** Called when an app is picked up, so the home screen can say how to move it. */
    var onMoveStart: (() -> Unit)? = null

    // Double press: the first press waits a moment in case a second one follows
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPkg: String? = null
    private var pendingAt = 0L
    private var pendingOpen: Runnable? = null

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

    /** The section's apps (and folders) in order, by name. */
    fun order(): List<String> = items.map { it.pkg }

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

    /** Whether the tile at [position] is the first on its line, where Left opens the side menu. */
    fun isLineStart(position: Int): Boolean = when {
        position < 0 -> false
        lines == Rows.WRAP -> position % wrapColumns == 0
        sideways -> position < lines
        else -> position == 0
    }

    override fun getItemCount(): Int = if (sideways) columns * lines else items.size

    override fun getItemId(position: Int): Long {
        val index = appAt(position)
        return if (index < items.size) items[index].pkg.hashCode().toLong() else -1L - position
    }

    // ------------------------------------------------------------------ moving an app

    /** Picks an app up so the D-pad moves it. */
    fun startMove(pkg: String): Boolean {
        val position = positionOf(pkg)
        if (position < 0 || items.size < 2) {
            return false
        }
        movingPkg = pkg
        notifyItemChanged(position)
        focusMoving()
        onMoveStart?.invoke()
        return true
    }

    /** Puts the app down where it is and saves the order. */
    fun finishMove() {
        val was = movingPkg ?: return
        movingPkg = null
        val position = positionOf(was)
        if (position >= 0) {
            notifyItemChanged(position)
        }
        onMoved(items.map { it.pkg })
        // The tile is redrawn: put the focus back on it
        recycler?.let { list -> list.post { list.layoutManager?.findViewByPosition(position)?.requestFocus() } }
    }

    /**
     * A key while an app is picked up. Returns true when the key was used for moving, which is
     * every D-pad, select and Back press, so nothing else happens until the app is put down.
     */
    fun moveKey(event: KeyEvent): Boolean {
        if (movingPkg == null) {
            return false
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && isArrow(event.keyCode)) {
            // One move at a time: a held or fast-pressed D-pad sends presses quicker than the
            // section can be redrawn. Presses that arrive before the last move is on screen are
            // dropped rather than piled up.
            val list = recycler
            val now = android.os.SystemClock.uptimeMillis()
            if (list == null || list.isComputingLayout || list.hasPendingAdapterUpdates() ||
                now - lastMoveAt < MOVE_GAP_MS
            ) {
                return true
            }
            lastMoveAt = now
        }
        val across = if (lines == Rows.WRAP) wrapColumns else columns
        val grid = sideways || lines == Rows.WRAP
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveBy(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveBy(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (down && grid) moveBy(-across)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && grid) moveBy(across)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_ESCAPE -> if (!down) finishMove()
            else -> return false
        }
        return true
    }

    private var lastMoveAt = 0L

    private fun isArrow(code: Int) = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN

    /** Swaps the moving app with the one [by] places away, if there is one. */
    private fun moveBy(by: Int) {
        val from = items.indexOfFirst { it.pkg == movingPkg }
        val to = from + by
        if (from < 0 || to < 0 || to >= items.size) {
            return
        }
        java.util.Collections.swap(items, from, to)
        val fromPosition = positionOfApp(from)
        val toPosition = positionOfApp(to)
        if (Math.abs(by) == 1 && !sideways && !spotlightOn) {
            // Neighbours in a single line: slide them past each other
            notifyItemMoved(fromPosition, toPosition)
        } else {
            // Apps that swap places across lines. Telling the list "these two changed" makes it
            // see two tiles with the same identity mid-animation, and it stops the app (the
            // crash this replaces). Redrawing the section is safe; each tile keeps its identity.
            notifyDataSetChanged()
        }
        focusMoving()
    }

    /** Puts the focus on the moving app's tile, wherever it now is, once it has been drawn. */
    private fun focusMoving() {
        val list = recycler ?: return
        val position = positionOf(movingPkg ?: return)
        list.scrollToPosition(position)
        list.post {
            val now = positionOf(movingPkg ?: return@post)
            val view = list.layoutManager?.findViewByPosition(now)
            if (view != null) {
                view.requestFocus()
                keepInView(view)
            } else {
                // Not drawn yet (it scrolled into view): try once more after the next layout
                list.post { list.layoutManager?.findViewByPosition(now)?.requestFocus() }
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

    /** Select on an app: open it, unless a second press quickly follows, which picks it up. */
    private fun pressed(app: AppEntry) {
        if (!tiles.doublePress || !canMove) {
            onOpen(app)
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (pendingPkg == app.pkg && now - pendingAt < DOUBLE_PRESS_MS) {
            pendingOpen?.let { handler.removeCallbacks(it) }
            pendingOpen = null
            pendingPkg = null
            startMove(app.pkg)
            return
        }
        pendingOpen?.let { handler.removeCallbacks(it) }
        pendingPkg = app.pkg
        pendingAt = now
        val open = Runnable {
            pendingPkg = null
            pendingOpen = null
            onOpen(app)
        }
        pendingOpen = open
        handler.postDelayed(open, DOUBLE_PRESS_MS)
    }

    companion object {
        /** How quickly the second press has to follow to count as a double press. */
        const val DOUBLE_PRESS_MS = 320L

        private const val BIG = 1

        /** How long a newly installed app is marked "New" (until it's opened). */
        private const val NEW_FOR_MS = 3L * 24 * 60 * 60 * 1000

        /** The shortest time between two moves of a picked-up app. */
        const val MOVE_GAP_MS = 90L
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    // ------------------------------------------------------------------ tiles

    override fun getItemViewType(position: Int): Int = if (spotlightOn && position == 0) BIG else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val context = parent.context
        val big = viewType == BIG
        // The spotlight card covers two cards across and two down, gaps included
        val cardWidth = if (big) tiles.width * 2 + tiles.gap * 2 else tiles.width
        val cardHeight = if (big) tiles.height * 2 + tiles.gap * 2 else tiles.height

        val card = view.findViewById<View>(R.id.card)
        card.layoutParams = card.layoutParams.apply {
            width = cardWidth
            height = cardHeight
        }
        card.background = tiles.card(context)
        view.findViewById<View>(R.id.ring).background = tiles.ring()
        view.layoutParams = RecyclerView.LayoutParams(
            if (lines == Rows.WRAP) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Beside a spotlight card the others line up with its bottom edge
            val top = if (spotlightOn && !big) tiles.gap + cardHeight + tiles.gap * 2 else tiles.gap
            setMargins(tiles.gap, top, tiles.gap, tiles.gap)
        }
        if (lines == Rows.WRAP) {
            // In a wrapping grid the tile sits in the middle of its column
            (view as android.widget.LinearLayout).setGravity(Gravity.CENTER_HORIZONTAL)
        }

        val icon = view.findViewById<ImageView>(R.id.icon)
        if (tiles.fullBleed) {
            // Edge to edge: the icon is the card, cut to its rounded corners
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            view.findViewById<TextView>(R.id.cardLabel).apply {
                setBackgroundResource(R.drawable.card_label_bg)
                setTextColor(Color.WHITE)
                setPadding(paddingLeft, Math.round(cardHeight * 0.12f), paddingRight, paddingBottom)
            }
        } else icon.layoutParams = (icon.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (big) tiles.iconSize * 2 else tiles.iconSize
            height = if (big) tiles.iconSize * 2 else tiles.iconSize
            gravity = if (tiles.style == Look.TILE_ICON_NAME) Gravity.CENTER_HORIZONTAL or Gravity.TOP
            else Gravity.CENTER
            if (tiles.style == Look.TILE_ICON_NAME) {
                topMargin = Math.round(cardHeight * 0.14f)
            }
        }
        val label = view.findViewById<TextView>(R.id.label)
        label.layoutParams = label.layoutParams.apply { width = cardWidth }
        if (tiles.darkCard && !tiles.fullBleed) {
            view.findViewById<TextView>(R.id.cardLabel).setTextColor(Color.rgb(0x15, 0x1A, 0x22))
        }
        Look.styleText(view)
        return Holder(view, big)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val index = appAt(position)
        holder.bind(if (index < items.size) items[index] else null)
    }

    inner class Holder(view: View, private val big: Boolean) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val ring: View = view.findViewById(R.id.ring)
        private val label: TextView = view.findViewById(R.id.label)
        private val cardLabel: TextView = view.findViewById(R.id.cardLabel)
        private val lockBadge: View = view.findViewById(R.id.lockBadge)
        private val newBadge: View = view.findViewById(R.id.newBadge)
        private val progress: View = view.findViewById(R.id.progress)
        private var entry: AppEntry? = null

        private val moving: Boolean
            get() = entry != null && entry?.pkg == movingPkg

        init {
            // Round the picture's corners to match the card
            card.clipToOutline = true

            view.setOnFocusChangeListener { v, focused ->
                showFocus(focused, true)
                if (focused) {
                    keepInView(v)
                    entry?.let { onFocused?.invoke(it) }
                }
            }
            view.setOnClickListener {
                if (movingPkg == null) {
                    entry?.let { pressed(it) }
                }
            }
            view.setOnLongClickListener {
                entry?.let(onMenu)
                true
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (movingPkg != null) {
                    // The home screen sends moving keys to the section; ignore anything else
                    false
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

            // The viewer's own picture for an app wins in every style: it fills the card in the
            // TV picture style and takes the icon's place in the icon styles
            val wide = tiles.style == Look.TILE_BANNER
            val wideImage = if (wide) app.own ?: app.banner else null
            val showBanner = wideImage != null
            banner.setImageDrawable(wideImage)
            banner.visibility = if (showBanner) View.VISIBLE else View.GONE
            icon.setImageDrawable(if (showBanner) null else app.own ?: app.icon)
            icon.visibility = if (showBanner) View.GONE else View.VISIBLE
            cardLabel.visibility = if (tiles.style == Look.TILE_ICON_NAME) View.VISIBLE else View.GONE
            lockBadge.visibility = if (AppLock.isLocked(itemView.context, app.appPkg)) View.VISIBLE else View.GONE
            val fresh = tiles.newBadge && app.installed > 0 &&
                System.currentTimeMillis() - app.installed < NEW_FOR_MS && !Prefs.everOpened(itemView.context, app.pkg)
            newBadge.visibility = if (fresh) View.VISIBLE else View.GONE
            if (tiles.outlineApp) {
                (ring.background as? android.graphics.drawable.GradientDrawable)
                    ?.setStroke(tiles.outlineWidth, if (app.colour != 0) app.colour else tiles.outlineColour)
            }
            if (app.progress >= 0f) {
                progress.visibility = View.VISIBLE
                progress.layoutParams = progress.layoutParams.apply {
                    width = Math.max(1, Math.round((if (big) tiles.width * 2 + tiles.gap * 2 else tiles.width) * app.progress))
                }
            } else {
                progress.visibility = View.GONE
            }
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
            ring.visibility = if (focused || moving) View.VISIBLE else View.INVISIBLE
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
            val lit = focused || moving
            val alpha = if (tiles.dimOthers && !lit) 0.55f else 1f
            var scale = if (moving) maxOf(tiles.zoom, 1.1f) + 0.08f else if (focused) tiles.zoom else 1f
            if (big) {
                // A big card grows half as much, or it would cover its neighbours
                scale = 1f + (scale - 1f) / 2f
            }
            // The glow or shadow behind the card you're on (Android 9 and later colour it)
            val depth = if (lit && tiles.shadow != Look.SHADOW_NONE) tiles.lift else 0f
            val rise = if (lit && tiles.shadow == Look.SHADOW_LIFT) -tiles.lift / 3 else 0f
            if (android.os.Build.VERSION.SDK_INT >= 28 && tiles.shadow != Look.SHADOW_NONE) {
                val own = entry?.colour ?: 0
                val colour = when {
                    tiles.shadow == Look.SHADOW_LIFT -> Color.BLACK
                    tiles.glowApp && own != 0 -> own
                    else -> tiles.glowColour
                }
                card.outlineSpotShadowColor = colour
                card.outlineAmbientShadowColor = colour
            }
            if (animate && tiles.motion != Look.MOTION_INSTANT) {
                val (time, ease) = when (tiles.motion) {
                    Look.MOTION_BOUNCY -> (if (lit) 260L else 160L) to
                        (if (lit) android.view.animation.OvershootInterpolator(2.2f) else android.view.animation.DecelerateInterpolator())
                    Look.MOTION_SNAPPY -> 90L to android.view.animation.DecelerateInterpolator()
                    else -> 200L to android.view.animation.DecelerateInterpolator(1.5f)
                }
                // Drawn by the graphics chip as one layer while it moves
                itemView.animate().scaleX(scale).scaleY(scale).alpha(alpha).translationY(rise)
                    .setDuration(time).setInterpolator(ease).withLayer().start()
                card.animate().translationZ(depth).setDuration(time).start()
                if (focused && !moving) {
                    Sounds.play(itemView.context, Sounds.MOVE)
                }
            } else {
                itemView.animate().cancel()
                card.animate().cancel()
                itemView.scaleX = scale
                itemView.scaleY = scale
                itemView.alpha = alpha
                itemView.translationY = rise
                card.translationZ = depth
            }
        }
    }
}
