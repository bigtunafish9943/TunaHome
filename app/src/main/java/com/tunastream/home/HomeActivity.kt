package com.tunastream.home

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/** The home screen: the name and clock, a row of favourites, and every app underneath. */
class HomeActivity : BaseActivity() {
    private lateinit var scroll: ScrollView
    private lateinit var favouritesRow: RecyclerView
    private lateinit var appsGrid: RecyclerView
    private lateinit var favouritesHint: TextView
    private lateinit var appsHint: TextView
    private lateinit var favouritesAdapter: AppAdapter
    private lateinit var appsAdapter: AppAdapter

    private var apps: List<AppEntry> = emptyList()
    private var loaded = false
    private var loading = false
    private var loadAgain = false
    private var placedFocus = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        scroll = findViewById(R.id.scroll)
        favouritesRow = findViewById(R.id.favourites)
        appsGrid = findViewById(R.id.apps)
        favouritesHint = findViewById(R.id.favouritesHint)
        appsHint = findViewById(R.id.appsHint)
        findViewById<TextView>(R.id.brandVersion).text =
            getString(R.string.version, BuildConfig.VERSION_NAME)

        favouritesAdapter = AppAdapter(false, ::open, ::showMenu)
        appsAdapter = AppAdapter(true, ::open, ::showMenu)
        favouritesRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        favouritesRow.adapter = favouritesAdapter
        favouritesRow.itemAnimator = null
        appsGrid.layoutManager = GridLayoutManager(this, COLUMNS)
        appsGrid.adapter = appsAdapter
        appsGrid.itemAnimator = null

        findViewById<View>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.openTvSettings).setOnClickListener { openTvSettings(this) }

        // Apps installed, removed or updated while TunaHome is open
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }

        reload()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.appsChanged) {
            Prefs.appsChanged = false
            reload()
        }
        Updater.resumeIfWaiting(this)
        Updater.check(this, false)
    }

    /** Home pressed while already here: back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (HomeButtonService.ACTION_HOME_PRESSED == intent.action && hasWindowFocus()) {
            goToTop()
        }
    }

    /** A home screen has nowhere to go back to, so Back returns to the top instead of closing. */
    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goToTop()
    }

    private fun goToTop() {
        scroll.smoothScrollTo(0, 0)
        focusFirst()
    }

    private fun focusFirst() {
        val list = if (favouritesAdapter.itemCount > 0) favouritesRow else appsGrid
        list.scrollToPosition(0)
        list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    // ------------------------------------------------------------------ the list of apps

    private fun reload() {
        if (loading) {
            loadAgain = true
            return
        }
        loading = true
        thread(name = "TunaHome apps") {
            val found = try {
                Apps.load(applicationContext)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                loading = false
                if (isDestroyed) {
                    return@runOnUiThread
                }
                apps = found
                loaded = true
                show()
                if (loadAgain) {
                    loadAgain = false
                    reload()
                }
            }
        }
    }

    /** Puts the loaded apps on screen, leaving out hidden ones and ordering the favourites. */
    private fun show() {
        val hidden = Prefs.hidden(this)
        val visible = apps.filter { it.pkg !in hidden }
        val byPackage = visible.associateBy { it.pkg }
        val favourites = Prefs.favourites(this).mapNotNull { byPackage[it] }

        favouritesAdapter.submit(favourites)
        appsAdapter.submit(visible)
        favouritesHint.visibility = if (favourites.isEmpty()) View.VISIBLE else View.GONE
        favouritesRow.visibility = if (favourites.isEmpty()) View.GONE else View.VISIBLE
        appsHint.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        appsHint.setText(if (loaded) R.string.no_apps else R.string.loading_apps)

        if (!placedFocus && visible.isNotEmpty()) {
            placedFocus = true
            focusFirst()
        }
    }

    private fun open(app: AppEntry) {
        try {
            startActivity(app.launch)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.open_failed, app.label), Toast.LENGTH_LONG).show()
            reload()
        }
    }

    // ------------------------------------------------------------------ an app's menu

    private fun showMenu(app: AppEntry) {
        val favourites = Prefs.favourites(this)
        val at = favourites.indexOf(app.pkg)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (at < 0) {
            labels.add(getString(R.string.menu_add_favourite))
            actions.add {
                favourites.add(app.pkg)
                saveFavourites(favourites, app.pkg)
            }
        } else {
            if (at > 0) {
                labels.add(getString(R.string.menu_move_left))
                actions.add {
                    favourites.removeAt(at)
                    favourites.add(at - 1, app.pkg)
                    saveFavourites(favourites, app.pkg)
                }
            }
            if (at < favourites.size - 1) {
                labels.add(getString(R.string.menu_move_right))
                actions.add {
                    favourites.removeAt(at)
                    favourites.add(at + 1, app.pkg)
                    saveFavourites(favourites, app.pkg)
                }
            }
            labels.add(getString(R.string.menu_remove_favourite))
            actions.add {
                favourites.remove(app.pkg)
                saveFavourites(favourites, null)
            }
        }

        labels.add(getString(R.string.menu_hide))
        actions.add {
            val hidden = Prefs.hidden(this)
            hidden.add(app.pkg)
            Prefs.setHidden(this, hidden)
            Prefs.appsChanged = false
            show()
            Toast.makeText(this, getString(R.string.hidden_toast, app.label), Toast.LENGTH_LONG).show()
        }

        labels.add(getString(R.string.menu_app_info))
        actions.add {
            startOrExplain(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
            )
        }

        labels.add(getString(R.string.menu_uninstall))
        actions.add {
            // Android shows its own "Do you want to uninstall this app?" before anything is removed
            startOrExplain(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Saves the favourites and, after a move, stays on the app that was moved. */
    private fun saveFavourites(favourites: List<String>, keepFocusOn: String?) {
        Prefs.setFavourites(this, favourites)
        show()
        if (keepFocusOn != null) {
            val position = favouritesAdapter.positionOf(keepFocusOn)
            if (position >= 0) {
                favouritesRow.scrollToPosition(position)
                favouritesRow.post {
                    favouritesRow.layoutManager?.findViewByPosition(position)?.requestFocus()
                }
            }
        }
    }

    private fun startOrExplain(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.action_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val COLUMNS = 5

        /** The TV's own settings, which a home screen has to offer a way into. */
        fun openTvSettings(context: Context) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(context, R.string.tv_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
