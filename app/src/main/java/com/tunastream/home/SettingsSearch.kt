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

// TunaHome settings: Search settings, which finds any setting by name.

/** Every setting whose name or current choice has [query] in it, on every page. */
internal fun SettingsActivity.findSettings(query: String): List<SettingsActivity.Found> {
    val found = ArrayList<SettingsActivity.Found>()
    collecting = found
    try {
        for (p in SettingsActivity.SEARCHABLE) {
            collectingPage = p
            collectedOnPage = 0
            try {
                buildPage(p)
            } catch (e: Exception) {
                // A page that can't be read is left out
            }
        }
    } finally {
        collecting = null
    }
    val wanted = query.trim().lowercase()
    return found.filter { it.title.lowercase().contains(wanted) || it.summary.lowercase().contains(wanted) }
        .sortedBy { if (it.title.lowercase().contains(wanted)) 0 else 1 }
}

internal fun SettingsActivity.searchPage() {
    val query = intent.getStringExtra(SettingsActivity.EXTRA_QUERY) ?: ""
    findViewById<TextView>(R.id.pageTitle).text = getString(R.string.settings_search_title, query)
    val found = findSettings(query)
    if (found.isEmpty()) {
        row(getString(R.string.settings_search_none, query), getString(R.string.settings_search_again)) { askSearch() }
        return
    }
    for (f in found) {
        row(f.title, getString(SettingsActivity.pageTitle(f.page)) + (if (f.summary.isNotEmpty()) " · " + f.summary else "")) {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PAGE, f.page).putExtra(SettingsActivity.EXTRA_FOCUS, f.index))
        }
    }
}

internal fun SettingsActivity.askSearch() {
    askForText(R.string.settings_search, "") { text ->
        if (text.isNotBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.SEARCH).putExtra(SettingsActivity.EXTRA_QUERY, text.trim()))
        }
    }
}
