package com.tunastream.home

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

// TunaHome settings: the Fine tuning page, with its live preview and step-by-step rows.

internal fun SettingsActivity.previewApps(): List<AppEntry> {
    val hidden = Prefs.hidden(this)
    val visible = Apps.cached.filter { it.pkg !in hidden }
    val favourites = Prefs.favourites(this).mapNotNull { pkg -> visible.firstOrNull { it.pkg == pkg } }
    return (favourites + visible).distinctBy { it.pkg }.take(8)
}

internal fun SettingsActivity.previewRow() {
    if (collecting != null) return
    val apps = previewApps()
    if (apps.isEmpty()) return
    val view = androidx.recyclerview.widget.RecyclerView(this)
    view.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
        this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
    view.clipChildren = false
    view.clipToPadding = false
    view.itemAnimator = null
    val pad = Math.round(Look.dp(this, 20f))
    view.setPadding(pad / 2, pad, pad, pad)
    view.layoutParams = LinearLayout.LayoutParams(
        Math.round(Look.dp(this, 900f)), ViewGroup.LayoutParams.WRAP_CONTENT)
    list.clipChildren = false
    list.addView(view)
    preview = view
    refreshPreview()
}

internal fun SettingsActivity.refreshPreview() {
    val view = preview ?: return
    val adapter = AppAdapter(TileStyle(this), 1, {}, {}, {})
    adapter.canMove = false
    adapter.submit(previewApps())
    view.adapter = adapter
}

/**
 * A number tuned with Left and Right, shown as a bar. Select puts it back to the usual.
 * The preview at the top of the page follows straight away.
 */
internal fun SettingsActivity.slider(title: Int, level: Look.Level) {
    val view = row(getString(title), sliderText(level)) {} ?: return
    val summary = view.findViewById<TextView>(R.id.summary)
    val track = android.widget.FrameLayout(this)
    val fill = View(this)
    val height = Math.round(Look.dp(this, 4f))
    track.layoutParams = LinearLayout.LayoutParams(Math.round(Look.dp(this, 300f)), height).apply {
        topMargin = Math.round(Look.dp(this@slider, 8f))
    }
    track.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = height / 2f
        setColor(themeColor(R.attr.tsTextSecondary) and 0x00FFFFFF or 0x40000000)
    }
    fill.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = height / 2f
        setColor(themeColor(R.attr.tsAccent))
    }
    track.addView(fill, android.widget.FrameLayout.LayoutParams(0, height))
    (view as LinearLayout).addView(track)
    fun show() {
        summary.text = sliderText(level)
        val share = (level.get(this) - level.min).toFloat() / (level.max - level.min)
        fill.layoutParams = fill.layoutParams.apply { width = Math.round(track.layoutParams.width * share) }
    }
    show()
    view.setOnKeyListener { _, code, event ->
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return@setOnKeyListener false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Holding the button speeds up
            val steps = when {
                event.repeatCount > 12 -> 5
                event.repeatCount > 4 -> 2
                else -> 1
            }
            val before = level.get(this)
            level.set(this, before + direction * level.step * steps)
            if (level.get(this) != before) {
                takeLook()
                Sounds.play(this, Sounds.MOVE)
                show()
                refreshPreview()
            }
        }
        true
    }
    view.setOnClickListener {
        level.clear(this)
        takeLook()
        show()
        refreshPreview()
    }
}

internal fun SettingsActivity.sliderText(level: Look.Level): String {
    val now = level.name(this)
    return if (level.isSet(this)) getString(R.string.fine_value_tuned, now, level.name(this, level.usual(this)))
    else getString(R.string.fine_value_usual, now)
}

internal fun SettingsActivity.finePage() {
    previewRow()
    row(R.string.preset_title, getString(R.string.preset_summary)) {
        chooseOne(getString(R.string.preset_title), Look.PRESETS.map { getString(it) }, -1) {
            Look.applyPreset(this, it)
            changed()
        }
    }
    heading(R.string.fine_cards)
    slider(R.string.set_card_size, Look.FINE_CARD_SIZE)
    slider(R.string.fine_corners, Look.FINE_CORNERS)
    slider(R.string.fine_gap, Look.FINE_GAP)
    heading(R.string.fine_selected)
    slider(R.string.fine_zoom, Look.FINE_ZOOM)
    slider(R.string.fine_glow, Look.FINE_GLOW)
    slider(R.string.fine_shine, Look.FINE_SHINE)
    slider(R.string.fine_speed, Look.FINE_SPEED)
    slider(R.string.fine_dim_others, Look.FINE_DIM_OTHERS)
    heading(R.string.fine_screen)
    slider(R.string.fine_row_gap, Look.FINE_ROW_GAP)
    slider(R.string.fine_text, Look.FINE_TEXT)
    slider(R.string.fine_clock, Look.FINE_CLOCK)
    slider(R.string.fine_bg_dim, Backdrop.FINE_DIM)
    slider(R.string.fine_follow_dark, Ambient.FINE_DARK)
    slider(R.string.fine_panel, Look.FINE_PANEL)
    slider(R.string.fine_panel_corners, Look.FINE_PANEL_CORNERS)
    row(R.string.fine_reset, getString(R.string.fine_reset_summary)) {
        Look.allFine().forEach { it.clear(this) }
        changed()
    }
}
