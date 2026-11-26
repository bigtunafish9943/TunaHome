package com.tunastream.home

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The first time TunaHome opens: five quick choices so it looks like the viewer's own straight
 * away (theme, cards, background, the Home button, a backup to bring back). Every step can be
 * skipped, and everything can be changed later in TunaHome settings, where this can be run again.
 */
class WelcomeActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.list)
        step = savedInstanceState?.getInt(STATE_STEP) ?: intent.getIntExtra(STATE_STEP, 0)
        findViewById<TextView>(R.id.brandVersion).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        showStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        // Back from the TV's accessibility settings: the Home button step shows whether it worked
        if (step == STEP_HOME && !isFinishing) {
            showStep()
        }
    }

    private fun showStep() {
        list.removeAllViews()
        val title = findViewById<TextView>(R.id.pageTitle)
        title.text = getString(R.string.welcome_step, step + 1, STEPS, getString(TITLES[step]))
        text(BODIES[step])
        when (step) {
            STEP_THEME -> {
                // The theme shows at once: the screen is rebuilt in it
                TunaTheme.IDS.forEach { id ->
                    option(getString(TunaTheme.nameOf(id)), if (id == TunaTheme.chosen(this)) getString(R.string.welcome_current) else "") {
                        TunaTheme.setChosen(this, id)
                        next()
                    }
                }
            }
            STEP_CARDS -> {
                Look.TILE_STYLE.names.forEachIndexed { i, name ->
                    option(getString(name), getString(CARD_NOTES[i])) {
                        Look.TILE_STYLE.set(this, i)
                        next()
                    }
                }
            }
            STEP_BACKGROUND -> {
                option(getString(R.string.bg_theme), getString(R.string.welcome_bg_theme)) {
                    Backdrop.SOURCE.set(this, Backdrop.SOURCE_THEME)
                    next()
                }
                option(getString(R.string.bg_daily), getString(R.string.welcome_bg_daily)) {
                    Backdrop.SOURCE.set(this, Backdrop.SOURCE_DAILY)
                    next()
                }
                WallpaperApps.installed(this).forEach { app ->
                    option(app.name, getString(R.string.welcome_bg_app)) {
                        Backdrop.setApp(this, app.component)
                        Backdrop.SOURCE.set(this, Backdrop.SOURCE_APP)
                        next()
                    }
                }
                option(getString(R.string.welcome_later), getString(R.string.welcome_bg_later)) { next() }
            }
            STEP_HOME -> {
                if (HomeButtonService.isOn(this)) {
                    option(getString(R.string.welcome_home_done), getString(R.string.welcome_home_done_summary)) { next() }
                } else {
                    option(getString(R.string.welcome_home_set), getString(R.string.welcome_home_set_summary)) {
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Exception) {
                            HomeActivity.openTvSettings(this)
                        }
                    }
                    option(getString(R.string.welcome_later), getString(R.string.welcome_home_later)) { next() }
                }
            }
            STEP_BACKUP -> {
                option(getString(R.string.welcome_fresh), getString(R.string.welcome_fresh_summary)) { finishWelcome() }
                option(getString(R.string.welcome_restore), getString(R.string.welcome_restore_summary)) {
                    finishWelcome()
                    startActivity(Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.LAYOUTS))
                }
            }
        }
        if (step > 0) {
            option(getString(R.string.welcome_back), "") {
                step--
                showStep()
            }
        }
        (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
    }

    private fun next() {
        step++
        if (step >= STEPS) {
            finishWelcome()
            return
        }
        // A new theme or card style shows on the next step
        if (outOfDate()) {
            intent.putExtra(STATE_STEP, step)
            recreate()
        } else {
            showStep()
        }
    }

    private fun finishWelcome() {
        done(this)
        Look.touch(this)
        Prefs.appsChanged = true
        finish()
    }

    private fun text(body: Int) {
        val view = TextView(this)
        view.setText(body)
        view.setTextColor(themeColor(R.attr.tsTextSecondary))
        view.textSize = 16f
        view.setLineSpacing(0f, 1.2f)
        val pad = Math.round(Look.dp(this, 6f))
        view.setPadding(pad, pad, pad, pad * 3)
        Look.styleText(view)
        list.addView(view)
    }

    private fun option(title: String, summary: String, action: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.item_setting, list, false)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.summary).apply {
            text = summary
            visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
        }
        view.setOnClickListener {
            Sounds.play(this, Sounds.SELECT)
            action()
        }
        Look.styleText(view)
        list.addView(view)
    }

    companion object {
        private const val STATE_STEP = "welcome_step"
        private const val DONE = "welcomed"

        private const val STEP_THEME = 0
        private const val STEP_CARDS = 1
        private const val STEP_BACKGROUND = 2
        private const val STEP_HOME = 3
        private const val STEP_BACKUP = 4
        private const val STEPS = 5

        private val TITLES = intArrayOf(
            R.string.welcome_theme, R.string.welcome_cards, R.string.welcome_background,
            R.string.welcome_home, R.string.welcome_backup,
        )
        private val BODIES = intArrayOf(
            R.string.welcome_theme_body, R.string.welcome_cards_body, R.string.welcome_background_body,
            R.string.welcome_home_body, R.string.welcome_backup_body,
        )
        private val CARD_NOTES = intArrayOf(R.string.welcome_cards_banner, R.string.welcome_cards_icon, R.string.welcome_cards_icon_name)

        private fun done(context: android.content.Context) {
            Prefs.of(context).edit().putBoolean(DONE, true).apply()
        }

        /**
         * Shows the welcome the first time TunaHome opens. Someone updating from an older
         * TunaHome (who has changed something already) never sees it unasked.
         */
        fun showIfNew(home: android.app.Activity) {
            val prefs = Prefs.of(home)
            if (prefs.getBoolean(DONE, false)) return
            if (Look.stamp(home) > 0) {
                done(home)
                return
            }
            // Shown once: leaving it with Back skips it for good (it's in settings to run again)
            done(home)
            home.startActivity(Intent(home, WelcomeActivity::class.java))
        }

        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, WelcomeActivity::class.java))
        }
    }
}
