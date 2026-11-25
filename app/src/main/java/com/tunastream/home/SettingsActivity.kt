package com.tunastream.home

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** TunaHome's own settings: a short list of rows that each say what they are set to. */
class SettingsActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private var focusIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.list)
        findViewById<TextView>(R.id.brandVersion).text =
            getString(R.string.version, BuildConfig.VERSION_NAME)
        focusIndex = savedInstanceState?.getInt(STATE_FOCUS) ?: 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FOCUS, focusIndex)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt each time: the Home button row changes in the TV's settings, not here
        build()
        Updater.resumeIfWaiting(this)
    }

    private fun build() {
        list.removeAllViews()
        val rows = ArrayList<View>()

        fun row(title: String, summary: String, action: () -> Unit) {
            val view = layoutInflater.inflate(R.layout.item_setting, list, false)
            view.findViewById<TextView>(R.id.title).text = title
            view.findViewById<TextView>(R.id.summary).text = summary
            val index = rows.size
            view.setOnClickListener {
                focusIndex = index
                action()
            }
            view.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    focusIndex = index
                }
            }
            Look.styleText(view)
            list.addView(view)
            rows.add(view)
        }

        // Theme
        val chosenName = getString(TunaTheme.nameOf(TunaTheme.chosen(this)))
        val showing = TunaTheme.current(this)
        val themeSummary = if (showing != TunaTheme.chosen(this)) {
            getString(R.string.set_theme_season_showing, chosenName, getString(TunaTheme.nameOf(showing)))
        } else {
            chosenName
        }
        row(getString(R.string.set_theme), themeSummary) { chooseTheme() }

        val seasonal = TunaTheme.seasonal(this)
        row(
            getString(R.string.set_seasonal),
            getString(if (seasonal) R.string.set_seasonal_on else R.string.set_seasonal_off)
        ) {
            TunaTheme.setSeasonal(this, !seasonal)
            refresh()
        }

        // Wallpaper, text and picture size
        row(
            getString(R.string.set_wallpaper),
            getString(
                if (Pictures.hasWallpaper(this)) R.string.set_wallpaper_set
                else R.string.set_wallpaper_none
            )
        ) { chooseWallpaper() }

        row(getString(R.string.set_font), getString(Look.FONT_NAMES[Look.font(this)])) {
            chooseOne(R.string.set_font, Look.FONT_NAMES, Look.font(this)) { Look.setFont(this, it) }
        }
        row(getString(R.string.set_text_size), getString(Look.TEXT_NAMES[Look.textSize(this)])) {
            chooseOne(R.string.set_text_size, Look.TEXT_NAMES, Look.textSize(this)) {
                Look.setTextSize(this, it)
            }
        }
        row(getString(R.string.set_tile_size), getString(Look.TILE_NAMES[Look.tileSize(this)])) {
            chooseOne(R.string.set_tile_size, Look.TILE_NAMES, Look.tileSize(this)) {
                Look.setTileSize(this, it)
            }
        }

        // Apps
        val phoneApps = Prefs.showPhoneApps(this)
        row(
            getString(R.string.set_phone_apps),
            getString(if (phoneApps) R.string.set_phone_apps_on else R.string.set_phone_apps_off)
        ) {
            Prefs.setShowPhoneApps(this, !phoneApps)
            build()
        }

        val hiddenCount = Prefs.hidden(this).size
        row(
            getString(R.string.set_hidden),
            if (hiddenCount == 0) getString(R.string.set_hidden_none)
            else resources.getQuantityString(R.plurals.set_hidden_count, hiddenCount, hiddenCount)
        ) { chooseHidden() }

        // Home button
        val homeOn = HomeButtonService.isOn(this)
        row(
            getString(R.string.set_home_button),
            getString(if (homeOn) R.string.set_home_button_on else R.string.set_home_button_off)
        ) { explainHomeButton(homeOn) }

        val replace = Prefs.replaceStock(this)
        row(
            getString(R.string.set_replace_stock),
            getString(if (replace) R.string.set_replace_stock_on else R.string.set_replace_stock_off)
        ) {
            Prefs.setReplaceStock(this, !replace)
            build()
        }

        // Updates and the TV's own settings
        row(
            getString(R.string.set_update),
            getString(R.string.set_update_summary, BuildConfig.VERSION_NAME)
        ) { Updater.check(this, true) }

        row(getString(R.string.tv_settings), getString(R.string.set_tv_settings_summary)) {
            HomeActivity.openTvSettings(this)
        }

        val focus = rows.getOrNull(focusIndex) ?: rows.firstOrNull()
        focus?.requestFocus()
    }

    /** After a setting changed: rebuild the whole screen if its colours or text changed. */
    private fun refresh() {
        if (outOfDate()) {
            recreate()
        } else {
            build()
        }
    }

    private fun chooseTheme() {
        val ids = TunaTheme.IDS
        val names = Array(ids.size) { getString(TunaTheme.nameOf(ids[it])) }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_theme)
            .setSingleChoiceItems(names, ids.indexOf(TunaTheme.chosen(this))) { dialog, which ->
                TunaTheme.setChosen(this, ids[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A short list of named options with the current one marked; picking one applies it. */
    private fun chooseOne(title: Int, names: IntArray, current: Int, pick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(Array(names.size) { getString(names[it]) }, current) { dialog, which ->
                pick(which)
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseWallpaper() {
        val labels = ArrayList<String>()
        labels.add(getString(R.string.wallpaper_choose))
        if (Pictures.hasWallpaper(this)) {
            labels.add(getString(R.string.wallpaper_remove))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_wallpaper)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Pictures.pickerIntent(), REQUEST_WALLPAPER)
                    } catch (e: Exception) {
                        AlertDialog.Builder(this)
                            .setMessage(R.string.wallpaper_no_picker)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    Pictures.removeWallpaper(this)
                    build()
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK || uri == null) {
            return
        }
        thread(name = "TunaHome wallpaper") {
            val saved = try {
                Pictures.saveWallpaper(applicationContext, uri)
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) {
                    if (!saved) {
                        Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show()
                    }
                    build()
                }
            }
        }
    }

    private fun chooseHidden() {
        val hidden = Prefs.hidden(this).toList()
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.set_hidden_none, Toast.LENGTH_LONG).show()
            return
        }
        val pm = packageManager
        val named = hidden.map { pkg ->
            val label = try {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            pkg to label
        }.sortedBy { it.second.lowercase() }
        val keep = BooleanArray(named.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_hidden_title)
            .setMultiChoiceItems(named.map { it.second }.toTypedArray(), keep) { _, which, ticked ->
                keep[which] = ticked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                Prefs.setHidden(this, named.filterIndexed { i, _ -> keep[i] }.map { it.first }.toSet())
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** TunaHome never switches this on by itself: it explains, and opens the TV's own screen. */
    private fun explainHomeButton(on: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(R.string.set_home_button)
            .setMessage(if (on) R.string.home_button_how_off else R.string.home_button_how_on)
            .setPositiveButton(R.string.open_accessibility) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    HomeActivity.openTvSettings(this)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    companion object {
        private const val STATE_FOCUS = "focus"
        private const val REQUEST_WALLPAPER = 2
    }
}
