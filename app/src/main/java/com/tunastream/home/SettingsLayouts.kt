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

// TunaHome settings: Layouts and backups.

internal fun SettingsActivity.layoutsPage() {
    row(R.string.layout_save, getString(R.string.layout_save_summary)) {
        askForText(R.string.layout_save, "") { name ->
            work(R.string.layout_failed, { Layouts.save(it, name) }) {
                Toast.makeText(this, getString(R.string.layout_saved, name), Toast.LENGTH_LONG).show()
            }
        }
    }
    for (name in Layouts.saved(this)) {
        row(name, getString(R.string.layout_entry_summary)) { layoutMenu(name) }
    }
    heading(R.string.heading_backup)
    switch(R.string.set_auto_backup, Layouts.AUTO_BACKUP, R.string.auto_backup_on, R.string.off)
    row(R.string.layout_export, getString(R.string.layout_export_summary)) {
        work(R.string.layout_failed, { exportedName = Layouts.export(it, "backup") }) {
            Toast.makeText(this, getString(R.string.layout_exported, exportedName), Toast.LENGTH_LONG).show()
        }
    }
    row(R.string.layout_import, getString(R.string.layout_import_summary)) { importLayout() }
    row(R.string.layout_auto, getString(R.string.layout_auto_summary)) {}
}

internal fun SettingsActivity.layoutMenu(name: String) {
    val options = listOf(getString(R.string.layout_use), getString(R.string.layout_update),
        getString(R.string.layout_export_one), getString(R.string.layout_delete))
    chooseOne(name, options, -1) { which ->
        when (which) {
            0 -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.layout_use_title, name))
                .setMessage(R.string.layout_use_body)
                .setPositiveButton(R.string.layout_use) { _, _ ->
                    work(R.string.layout_failed, { Layouts.load(it, name) }) { Layouts.restart(this) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            1 -> work(R.string.layout_failed, { Layouts.save(it, name) }) {
                Toast.makeText(this, getString(R.string.layout_saved, name), Toast.LENGTH_LONG).show()
            }
            2 -> work(R.string.layout_failed, { ctx -> exportedName = Layouts.exportSaved(ctx, name) }) { Toast.makeText(this, getString(R.string.layout_exported, exportedName), Toast.LENGTH_LONG).show() }
            else -> {
                Layouts.delete(this, name)
                build()
            }
        }
    }
}

/** From this TV's Downloads (files TunaHome put there), or any file through a file manager. */
internal fun SettingsActivity.importLayout() {
    val found = Layouts.exported(this)
    val names = found.map { it.first } + getString(R.string.layout_import_other)
    chooseOne(getString(R.string.layout_import), names, -1) { which ->
        if (which < found.size) {
            confirmImport { work(R.string.layout_import_failed, { ctx ->
                ctx.contentResolver.openInputStream(found[which].second)?.use { Layouts.read(ctx, it) }
                    ?: throw IllegalStateException("unreadable")
            }) { Layouts.restart(this) } }
        } else {
            pick(SettingsActivity.REQUEST_LAYOUT, "*/*")
        }
    }
}

internal fun SettingsActivity.confirmImport(then: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(R.string.layout_import)
        .setMessage(R.string.layout_use_body)
        .setPositiveButton(R.string.layout_use) { _, _ -> then() }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Runs [job] off the main thread, then [then]; says [failure] if it went wrong. */
internal fun SettingsActivity.work(failure: Int, job: (Context) -> Unit, then: () -> Unit) {
    val app = applicationContext
    thread(name = "TunaHome layouts") {
        val ok = try {
            job(app)
            true
        } catch (e: Throwable) {
            false
        }
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (ok) then() else Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
            if (!isDestroyed) build()
        }
    }
}
