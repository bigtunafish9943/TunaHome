package com.tunastream.home

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.concurrent.thread

// The home screen: moving between sections, where the section you're on sits, and the background drifting.

/**
 * As focus moves to another section, that section glides to the chosen height (near the
 * top, or the middle) instead of the screen scrolling only as far as it must. Sections too
 * tall for that scroll the usual way.
 */
internal fun HomeActivity.setUpAlignment() {
    if (Look.SECTION_POSITION.get(this) == Look.POSITION_FREE) {
        return
    }
    (scroll as? HomeScroll)?.aligned = { rect ->
        val y = rect.centerY()
        sections.any { section ->
            section.view.visibility == View.VISIBLE && alignTarget(section) != null &&
                sectionTop(section).let { top -> y >= top && y < top + section.view.height }
        }
    }
    scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
        val section = now?.let { view -> sections.firstOrNull { it.list === view.parent } }
        val target = section?.let { alignTarget(it) }
        if (target != null && target != scroll.scrollY) {
            scroll.smoothScrollTo(0, target)
        }
    }
}

/** The soft panel behind a section, if one is chosen. */
internal fun HomeActivity.dressSection(view: View) {
    val panel = Look.SECTION_PANEL.get(this)
    if (panel == Look.PANEL_NONE) return
    val strength = Look.FINE_PANEL.get(this) / 100f
    val drawable = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Look.dp(this@dressSection, Look.FINE_PANEL_CORNERS.get(this@dressSection).toFloat())
        when (panel) {
            Look.PANEL_GLASS -> setColor(android.graphics.Color.argb(Math.round(255 * strength), 0, 0, 0))
            Look.PANEL_TINT -> setColor(themeColor(R.attr.tsAccent) and 0x00FFFFFF or (Math.round(255 * strength * 0.6f) shl 24))
            else -> setStroke(Math.round(Look.dp(this@dressSection, 1.5f)),
                android.graphics.Color.argb(Math.round(255 * strength * 1.5f).coerceAtMost(255), 255, 255, 255))
        }
    }
    view.background = drawable
    val pad = Math.round(Look.dp(this, 10f))
    view.setPadding(pad, pad / 2, pad, pad / 2)
    (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.bottomMargin = pad }
}

/** The app the viewer is on, if they're on one. */
internal fun HomeActivity.focusedApp(): AppEntry? {
    val view = currentFocus ?: return null
    val section = sections.firstOrNull { it.list === view.parent } ?: return null
    return section.adapter.appAtPosition(section.list.getChildAdapterPosition(view))
}

/** The background moves up a little as you scroll down, slower than the sections. */
internal fun HomeActivity.setUpDrift() {
    if (!Backdrop.DRIFT.get(this)) {
        return
    }
    val layers = listOf<View>(findViewById(R.id.backdropImage), findViewById(R.id.backdropVideo), findViewById(R.id.followBackdrop))
    // A little bigger than the screen, so there is picture to move into
    layers.forEach {
        it.scaleX = 1.1f
        it.scaleY = 1.1f
    }
    val room = resources.displayMetrics.heightPixels * 0.045f
    scroll.setOnScrollChangeListener { _, _, y, _, _ ->
        val shift = -minOf(y * 0.12f, room)
        layers.forEach { it.translationY = shift }
    }
}

/** Where a section starts, measured down the whole scrolling page. */
internal fun HomeActivity.sectionTop(section: HomeActivity.Section): Int {
    val rect = android.graphics.Rect(0, 0, section.view.width, section.view.height)
    scroll.offsetDescendantRectToMyCoords(section.view, rect)
    return rect.top
}

/** How far down to scroll so [section] sits where the viewer chose; null to scroll as usual. */
internal fun HomeActivity.alignTarget(section: HomeActivity.Section): Int? {
    val mode = Look.SECTION_POSITION.get(this)
    val screen = scroll.height
    if (mode == Look.POSITION_FREE || screen == 0 || section.view.height > screen * 0.8f) {
        return null
    }
    val top = sectionTop(section)
    val first = sections.firstOrNull { it.view.visibility == View.VISIBLE } === section
    val target = when {
        // The first section keeps the top of the screen in view
        first -> 0
        mode == Look.POSITION_MIDDLE -> top + section.view.height / 2 - screen / 2
        else -> top - Math.round(Look.dp(this, 28f))
    }
    val most = maxOf(0, (scroll.getChildAt(0)?.height ?: 0) - screen)
    return target.coerceIn(0, most)
}

internal fun HomeActivity.goToSection(section: HomeActivity.Section) {
    scroll.post {
        scroll.smoothScrollTo(0, alignTarget(section) ?: maxOf(0, section.view.top - Math.round(Look.dp(this, 24f))))
        focusIn(section, 0)
    }
}

internal fun HomeActivity.goToTop() {
    scroll.smoothScrollTo(0, 0)
    focusFirst()
}

internal fun HomeActivity.focusFirst() {
    val first = sections.firstOrNull { it.adapter.appCount > 0 } ?: return
    focusIn(first, 0)
}

internal fun HomeActivity.focusIn(section: HomeActivity.Section, position: Int) {
    section.list.scrollToPosition(position)
    section.list.post { section.list.layoutManager?.findViewByPosition(position)?.requestFocus() }
}

/**
 * Controller shoulder buttons (and Page up, Page down, Home and End on a keyboard): a section
 * up or down, or the first or last app of this one.
 */
internal fun HomeActivity.jump(code: Int): Boolean {
    val shown = sections.filter { it.view.visibility == View.VISIBLE && it.adapter.appCount > 0 }
    if (shown.isEmpty()) {
        return false
    }
    val focused = currentFocus
    val here = shown.indexOfFirst { it.list === focused?.parent }
    when (code) {
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP ->
            goToSection(shown[maxOf(0, here - 1)])
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN ->
            goToSection(shown[if (here < 0) 0 else minOf(here + 1, shown.size - 1)])
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_MOVE_END -> {
            val section = shown.getOrNull(here) ?: return false
            val first = code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_MOVE_HOME
            val position = section.adapter.positionOfApp(if (first) 0 else section.adapter.appCount - 1)
            section.list.scrollToPosition(position)
            section.list.post { section.list.layoutManager?.findViewByPosition(position)?.requestFocus() }
        }
        else -> return false
    }
    return true
}
