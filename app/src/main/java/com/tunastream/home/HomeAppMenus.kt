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

// The home screen: an app's menu, folders, and an app's own picture.

/** A folder's apps in a small grid over the home screen. */
internal fun HomeActivity.openFolder(id: String) {
    val folder = Folders.load(this).firstOrNull { it.id == id } ?: return
    val byPackage = apps.associateBy { it.pkg }
    val inside = folder.apps.mapNotNull { byPackage[it] }
    val list = RecyclerView(this)
    val across = minOf(5, maxOf(2, inside.size))
    lateinit var dialog: AlertDialog
    val adapter = AppAdapter(tiles, Rows.WRAP, { app ->
        dialog.dismiss()
        Launch.open(this, app, failed = { reload() })
    }, { app ->
        QuietDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf(getString(R.string.folder_take_out), getString(R.string.menu_app_info))) { _, which ->
                if (which == 0) {
                    Folders.update(this, id) { it.apps.remove(app.pkg) }
                    dialog.dismiss()
                    show()
                } else {
                    startOrExplain(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)))
                }
            }
            .show()
    }, { order -> Folders.update(this, id) { it.apps.clear(); it.apps.addAll(order) }; show() })
    adapter.wrapColumns = across
    list.layoutManager = GridLayoutManager(this, across)
    list.adapter = adapter
    list.itemAnimator = null
    list.clipToPadding = false
    val pad = Math.round(Look.dp(this, 16f))
    list.setPadding(pad, pad, pad, pad)
    adapter.submit(inside)
    dialog = QuietDialog.Builder(this)
        .setTitle(folder.name)
        .setView(list)
        .setNegativeButton(R.string.close, null)
        .show()
    list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
}

/** Puts an app into a folder, or a new one in the same place in its section. */
internal fun HomeActivity.putInFolder(app: AppEntry, row: Rows.Row) {
    val folders = Folders.load(this)
    val names = folders.map { it.name } + getString(R.string.folder_new)
    QuietDialog.Builder(this)
        .setTitle(getString(R.string.folder_put_title, app.label))
        .setItems(names.toTypedArray()) { _, which ->
            if (which < folders.size) {
                val list = Folders.load(this)
                list.forEach { it.apps.remove(app.pkg) }
                list.firstOrNull { it.id == folders[which].id }?.apps?.add(app.pkg)
                Folders.save(this, list.filter { it.apps.isNotEmpty() })
                show()
            } else {
                askForText(R.string.folder_new, "") { name ->
                    val folder = Folders.create(this, name, app.pkg)
                    // The folder takes the app's place in its section
                    val section = sections.firstOrNull { it.row.id == row.id }
                    val order = section?.adapter?.order()?.map { if (it == app.pkg) Folders.key(folder.id) else it }
                    if (order != null) saveOrder(row, order)
                    show()
                }
            }
        }
        .show()
}

internal fun HomeActivity.folderMenu(card: AppEntry, row: Rows.Row) {
    val id = card.pkg.removePrefix(Folders.PREFIX)
    val section = sections.firstOrNull { it.row.id == row.id }
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()
    if (section != null && section.adapter.appCount > 1) {
        labels.add(getString(R.string.menu_move))
        actions.add { section.adapter.startMove(card.pkg) }
    }
    labels.add(getString(R.string.folder_open))
    actions.add { openFolder(id) }
    labels.add(getString(R.string.section_rename))
    actions.add {
        askForText(R.string.section_rename, card.label) { name ->
            Folders.update(this, id) { it.name = name }
            show()
        }
    }
    labels.add(getString(R.string.folder_delete))
    actions.add {
        val list = Folders.load(this).filter { it.id != id }
        Folders.save(this, list)
        show()
    }
    QuietDialog.Builder(this)
        .setTitle(card.label)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

internal fun HomeActivity.showMenu(app: AppEntry, row: Rows.Row) {
    // Kids mode: changing things is for grown-ups
    if (Guard.kidsModeOn(this) && !Guard.grownUpRecently()) {
        Guard.askGrownUp(this) { showMenu(app, row) }
        return
    }
    if (app.isInsideApp) {
        watchNextMenu(app)
        return
    }
    if (app.isFolder) {
        folderMenu(app, row)
        return
    }
    val section = sections.firstOrNull { it.row.id == row.id }
    val favourites = Prefs.favourites(this)
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()

    // First, so that hold select then select picks the app up. Recently opened keeps its
    // own order, newest first.
    if (section != null && section.adapter.appCount > 1 && row.kind != Rows.RECENT) {
        labels.add(getString(R.string.menu_move))
        actions.add { section.adapter.startMove(app.pkg) }
    }

    if (app.pkg in favourites) {
        labels.add(getString(R.string.menu_remove_favourite))
        actions.add {
            favourites.remove(app.pkg)
            Prefs.setFavourites(this, favourites)
            show()
        }
    } else {
        labels.add(getString(R.string.menu_add_favourite))
        actions.add {
            favourites.add(app.pkg)
            Prefs.setFavourites(this, favourites)
            show()
        }
    }

    labels.add(getString(R.string.menu_add_to_row))
    actions.add { addToRow(app) }
    if (row.kind != Rows.RECENT && row.kind != Rows.WATCH_NEXT) {
        labels.add(getString(R.string.folder_put))
        actions.add { putInFolder(app, row) }
    }
    if (row.custom) {
        labels.add(getString(R.string.menu_remove_from_row, row.name(this)))
        actions.add {
            Rows.update(this, row.id, restyle = false) { it.apps.remove(app.pkg) }
            show()
        }
    }

    labels.add(getString(R.string.menu_change_picture))
    actions.add { pictureMenu(app) }

    labels.add(getString(R.string.menu_rename))
    actions.add {
        askForText(R.string.menu_rename, app.label) { name ->
            Prefs.setAppName(this, app.pkg, name)
            Prefs.appsChanged = false
            reload()
        }
    }
    if (Prefs.appNames(this).containsKey(app.pkg)) {
        labels.add(getString(R.string.menu_own_name))
        actions.add {
            Prefs.setAppName(this, app.pkg, null)
            Prefs.appsChanged = false
            reload()
        }
    }

    if (AppLock.isLocked(this, app.pkg)) {
        labels.add(getString(R.string.menu_unlock))
        actions.add {
            AppLock.ask(this, app.label) {
                AppLock.setLocked(this, app.pkg, false)
                show()
            }
        }
    } else {
        labels.add(getString(R.string.menu_lock))
        actions.add {
            val lock = {
                AppLock.setLocked(this, app.pkg, true)
                show()
                Toast.makeText(this, getString(R.string.lock_locked, app.label), Toast.LENGTH_LONG).show()
            }
            if (AppLock.hasPin(this)) lock() else AppLock.choosePin(this, lock)
        }
    }

    labels.add(getString(R.string.quick_title))
    actions.add { quickCustomise() }

    labels.add(getString(R.string.menu_edit_section, row.name(this)))
    actions.add {
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.SECTIONS)
            .putExtra(SettingsActivity.EXTRA_SECTION, row.id))
    }

    if (row.kind == Rows.FAVOURITES || row.custom) {
        labels.add(getString(R.string.link_add_here))
        actions.add { addLink { key -> putInSection(key, row) } }
    }

    if (app.isLink) {
        labels.add(getString(R.string.link_change))
        actions.add {
            askForText(R.string.link_address, Links.address(this, app.pkg) ?: "", url = true) { address ->
                if (address.isNotBlank()) {
                    Links.change(this, app.pkg, address)
                    Prefs.appsChanged = false
                    reload()
                }
            }
        }
        labels.add(getString(R.string.link_delete))
        actions.add {
            QuietDialog.Builder(this)
                .setTitle(getString(R.string.link_delete_title, app.label))
                .setPositiveButton(R.string.link_delete) { _, _ ->
                    Links.remove(this, app.pkg)
                    Prefs.appsChanged = false
                    reload()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        QuietDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
        return
    }

    if (!app.isLink) {
        val limit = Guard.appLimit(this, app.pkg)
        labels.add(if (limit > 0) getString(R.string.limit_menu_set, minutesName(limit)) else getString(R.string.limit_menu))
        actions.add {
            val change = {
                chooseOne(getString(R.string.limit_menu_title, app.label), Guard.CHOICES.map { minutesName(it) },
                    Guard.CHOICES.indexOf(limit)) { which ->
                    Guard.setAppLimit(this, app.pkg, Guard.CHOICES[which])
                }
            }
            // A limit someone set can't be lifted by whoever it limits
            if (limit > 0 && AppLock.hasPin(this)) AppLock.ask(this, app.label) { change() } else change()
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

    QuietDialog.Builder(this)
        .setTitle(app.label)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

/** Puts a new card at the end of Favourites or a section of the viewer's own. */
internal fun HomeActivity.putInSection(key: String, row: Rows.Row) {
    if (row.kind == Rows.FAVOURITES) {
        val favourites = Prefs.favourites(this)
        favourites.add(key)
        Prefs.setFavourites(this, favourites)
    } else {
        Rows.update(this, row.id, restyle = false) { it.apps.add(key) }
    }
    Prefs.appsChanged = false
    reload()
}

/** A Watch Next card belongs to its app: offer to open it, or that app's info. */
internal fun HomeActivity.watchNextMenu(item: AppEntry) {
    val appName = apps.firstOrNull { it.pkg == item.appPkg }?.label ?: item.appPkg
    val labels = arrayListOf(getString(if (item.isPcGame) R.string.pc_game_play else R.string.watch_next_play),
        getString(R.string.watch_next_open_app, appName),
        getString(R.string.menu_app_info))
    val actions = arrayListOf<() -> Unit>(
        { open(item) },
        {
            apps.firstOrNull { it.pkg == item.appPkg }?.let { open(it) }
                ?: Toast.makeText(this, getString(R.string.open_failed, appName), Toast.LENGTH_LONG).show()
        },
        {
            startOrExplain(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + item.appPkg)))
        },
    )
    // A PC app can play in a corner, over a show on the TV
    if (item.isPcGame) {
        labels.add(1, getString(R.string.corner_play))
        actions.add(1) { Corner.start(this, item) }
    }
    // Watch Next without an app's shows (one you don't want there, like a news app)
    if (!item.isPcGame && item.appPkg.isNotEmpty()) {
        labels.add(getString(R.string.watch_next_leave_out, appName))
        actions.add { setWatchNextLeftOut(item.appPkg, true) }
    }
    if (Rows.load(this).any { it.kind == Rows.WATCH_NEXT && it.apps.isNotEmpty() }) {
        labels.add(getString(R.string.watch_next_bring_back))
        actions.add { setWatchNextLeftOut(null, false) }
    }
    QuietDialog.Builder(this)
        .setTitle(item.label)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

/** Leaves an app's shows out of Watch Next, or (with null) brings every app's back. */
internal fun HomeActivity.setWatchNextLeftOut(appPkg: String?, out: Boolean) {
    val rows = Rows.load(this)
    rows.filter { it.kind == Rows.WATCH_NEXT }.forEach { row ->
        if (appPkg == null) {
            row.apps.clear()
        } else if (out && appPkg !in row.apps) {
            row.apps.add(appPkg)
        }
    }
    Rows.save(this, rows, false)
    // The sections read their rows again, then show the list without those apps
    show()
}

/** Adds an app to one of the viewer's own rows, or to a new one. */
internal fun HomeActivity.addToRow(app: AppEntry) {
    val own = Rows.load(this).filter { it.custom && app.pkg !in it.apps }
    val names = own.map { it.name(this) } + getString(R.string.row_new)
    QuietDialog.Builder(this)
        .setTitle(getString(R.string.menu_add_to_row_title, app.label))
        .setItems(names.toTypedArray()) { _, which ->
            if (which < own.size) {
                Rows.update(this, own[which].id, restyle = false) { it.apps.add(app.pkg) }
                show()
            } else {
                askForText(R.string.row_new, "") { title ->
                    val made = Rows.add(this, title)
                    Rows.update(this, made.id) { it.apps.add(app.pkg) }
                    // A new section: build the screen again
                    recreate()
                }
            }
        }
        .show()
}

/** Where an app's own picture comes from: the TV, a web address, or back to its own. */
internal fun HomeActivity.pictureMenu(app: AppEntry) {
    val labels = arrayListOf(getString(R.string.picture_from_tv), getString(R.string.picture_address))
    if (Pictures.hasTile(this, app.pkg)) {
        labels.add(getString(R.string.menu_own_picture))
    }
    // With an icon pack on, any of its drawings can be chosen for this app
    val pack = IconPacks.chosen(this)
    if (pack != null && Look.TILE_STYLE.get(this) != Look.TILE_BANNER) {
        labels.add(0, getString(R.string.pick_from_pack))
    }
    val offset = if (labels[0] == getString(R.string.pick_from_pack)) 1 else 0
    QuietDialog.Builder(this)
        .setTitle(getString(R.string.picture_title, app.label))
        .setItems(labels.toTypedArray()) { _, chosen ->
            if (offset == 1 && chosen == 0) {
                PackPicker.show(this, app) { reload() }
                return@setItems
            }
            when (chosen - offset) {
                0 -> choosePicture(app)
                1 -> askForText(R.string.picture_address, "https://", url = true) { address ->
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, R.string.bg_address_bad, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, R.string.picture_fetching, Toast.LENGTH_SHORT).show()
                        saveTile(app.pkg, Uri.parse(address), R.string.picture_address_failed)
                    }
                }
                else -> {
                    Pictures.removeTile(this, app.pkg)
                    reload()
                }
            }
        }
        .show()
}

internal fun HomeActivity.saveTile(pkg: String, uri: Uri, failure: Int) {
    thread(name = "TunaHome picture") {
        val saved = try {
            Pictures.saveTile(applicationContext, pkg, uri)
        } catch (e: Throwable) {
            false
        }
        runOnUiThread {
            if (!isDestroyed) {
                if (saved) reload() else Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun HomeActivity.choosePicture(app: AppEntry) {
    choosingPictureFor = app.pkg
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(Pictures.pickerIntent(), HomeActivity.REQUEST_TILE_PICTURE)
    } catch (e: Exception) {
        choosingPictureFor = null
        QuietDialog.Builder(this)
            .setMessage(R.string.wallpaper_no_picker)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

internal fun HomeActivity.startOrExplain(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(this, R.string.action_failed, Toast.LENGTH_LONG).show()
    }
}

/** "Off", "45 minutes", "1 h 30 min". */
internal fun android.content.Context.minutesName(minutes: Int): String = when {
    minutes <= 0 -> getString(R.string.limit_off)
    minutes < 60 -> resources.getQuantityString(R.plurals.sleep_minutes, minutes, minutes)
    minutes % 60 == 0 -> resources.getQuantityString(R.plurals.limit_hours, minutes / 60, minutes / 60)
    else -> getString(R.string.usage_hours, minutes / 60, minutes % 60)
}
