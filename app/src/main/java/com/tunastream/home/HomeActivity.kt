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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/**
 * The home screen: the name, a few small buttons and the clock, then rows of apps that run the
 * width of the screen: favourites, apps made for TV, and mobile apps.
 */
class HomeActivity : BaseActivity() {
    /** One titled row of apps. Hidden altogether while it has nothing in it. */
    private class Row(val title: View, val list: RecyclerView, val adapter: AppAdapter) {
        fun show(apps: List<AppEntry>) {
            adapter.submit(apps)
            val visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
            title.visibility = visibility
            list.visibility = visibility
        }
    }

    private lateinit var scroll: ScrollView
    private lateinit var wallpaper: ImageView
    private lateinit var wallpaperShade: View
    private lateinit var appsHint: TextView
    private lateinit var favourites: Row
    private lateinit var tvApps: Row
    private lateinit var mobileApps: Row

    private var apps: List<AppEntry> = emptyList()
    private var loaded = false
    private var loading = false
    private var loadAgain = false
    private var placedFocus = false
    private var wallpaperShown = -1L

    /** The app whose picture is being chosen, while the TV's picture chooser is open. */
    private var choosingPictureFor: String? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        choosingPictureFor = savedInstanceState?.getString(STATE_PICTURE_FOR)

        scroll = findViewById(R.id.scroll)
        wallpaper = findViewById(R.id.wallpaper)
        wallpaperShade = findViewById(R.id.wallpaperShade)
        appsHint = findViewById(R.id.appsHint)
        findViewById<TextView>(R.id.brandVersion).text =
            getString(R.string.version, BuildConfig.VERSION_NAME)

        favourites = row(R.id.favouritesTitle, R.id.favourites)
        tvApps = row(R.id.tvAppsTitle, R.id.tvApps)
        mobileApps = row(R.id.mobileAppsTitle, R.id.mobileApps)

        findViewById<View>(R.id.checkUpdate).setOnClickListener { Updater.check(this, true) }
        findViewById<View>(R.id.openTvSettings).setOnClickListener { openTvSettings(this) }
        findViewById<View>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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

        show()
        reload()
    }

    private fun row(titleId: Int, listId: Int): Row {
        val list = findViewById<RecyclerView>(listId)
        val adapter = AppAdapter(::open, ::showMenu)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = adapter
        list.itemAnimator = null
        return Row(findViewById(titleId), list, adapter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PICTURE_FOR, choosingPictureFor)
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
        showWallpaper()
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
        val first = listOf(favourites, tvApps, mobileApps).firstOrNull { it.adapter.itemCount > 0 }
            ?: return
        focusIn(first, 0)
    }

    private fun focusIn(row: Row, position: Int) {
        row.list.scrollToPosition(position)
        row.list.post { row.list.layoutManager?.findViewByPosition(position)?.requestFocus() }
    }

    // ------------------------------------------------------------------ wallpaper

    /** Shows the viewer's picture behind everything, or the theme's colours when there is none. */
    private fun showWallpaper() {
        val stamp = Prefs.wallpaperStamp(this)
        if (stamp == wallpaperShown) {
            return
        }
        wallpaperShown = stamp
        if (stamp == 0L) {
            wallpaper.setImageDrawable(null)
            wallpaperShade.visibility = View.GONE
            return
        }
        thread(name = "TunaHome wallpaper") {
            val picture = try {
                Pictures.loadWallpaper(applicationContext)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (!isDestroyed && wallpaperShown == stamp) {
                    wallpaper.setImageBitmap(picture)
                    wallpaperShade.visibility = if (picture != null) View.VISIBLE else View.GONE
                }
            }
        }
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

        favourites.show(Prefs.favourites(this).mapNotNull { byPackage[it] })
        tvApps.show(visible.filter { it.tv })
        mobileApps.show(visible.filter { !it.tv })

        // One line of help: while loading, when there is nothing to show, and until the first
        // favourite is added
        val hint = when {
            !loaded -> R.string.loading_apps
            visible.isEmpty() -> R.string.no_apps
            favourites.adapter.itemCount == 0 -> R.string.favourites_hint
            else -> 0
        }
        appsHint.visibility = if (hint == 0) View.GONE else View.VISIBLE
        if (hint != 0) {
            appsHint.setText(hint)
        }

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
        val list = Prefs.favourites(this)
        val at = list.indexOf(app.pkg)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (at < 0) {
            labels.add(getString(R.string.menu_add_favourite))
            actions.add {
                list.add(app.pkg)
                saveFavourites(list, app.pkg)
            }
        } else {
            if (at > 0) {
                labels.add(getString(R.string.menu_move_left))
                actions.add {
                    list.removeAt(at)
                    list.add(at - 1, app.pkg)
                    saveFavourites(list, app.pkg)
                }
            }
            if (at < list.size - 1) {
                labels.add(getString(R.string.menu_move_right))
                actions.add {
                    list.removeAt(at)
                    list.add(at + 1, app.pkg)
                    saveFavourites(list, app.pkg)
                }
            }
            labels.add(getString(R.string.menu_remove_favourite))
            actions.add {
                list.remove(app.pkg)
                saveFavourites(list, null)
            }
        }

        labels.add(getString(R.string.menu_change_picture))
        actions.add { choosePicture(app) }
        if (Pictures.hasTile(this, app.pkg)) {
            labels.add(getString(R.string.menu_own_picture))
            actions.add {
                Pictures.removeTile(this, app.pkg)
                reload()
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
    private fun saveFavourites(list: List<String>, keepFocusOn: String?) {
        Prefs.setFavourites(this, list)
        show()
        if (keepFocusOn != null) {
            val position = favourites.adapter.positionOf(keepFocusOn)
            if (position >= 0) {
                focusIn(favourites, position)
            }
        }
    }

    // ------------------------------------------------------------------ an app's own picture

    private fun choosePicture(app: AppEntry) {
        choosingPictureFor = app.pkg
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Pictures.pickerIntent(), REQUEST_TILE_PICTURE)
        } catch (e: Exception) {
            choosingPictureFor = null
            AlertDialog.Builder(this)
                .setMessage(R.string.wallpaper_no_picker)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TILE_PICTURE) {
            return
        }
        val pkg = choosingPictureFor
        choosingPictureFor = null
        val uri = data?.data
        if (resultCode != RESULT_OK || pkg == null || uri == null) {
            return
        }
        thread(name = "TunaHome picture") {
            val saved = try {
                Pictures.saveTile(applicationContext, pkg, uri)
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) {
                    if (saved) {
                        reload()
                    } else {
                        Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show()
                    }
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
        private const val STATE_PICTURE_FOR = "picture_for"
        private const val REQUEST_TILE_PICTURE = 1

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
