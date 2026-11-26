package com.tunastream.home

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.concurrent.thread

// TunaHome settings: Who's watching, everyone's own TunaHome.

internal fun SettingsActivity.profilesSummary(): String {
    val people = Profiles.all(this)
    if (people.size < 2) return getString(R.string.who_off)
    val now = Profiles.current(this)?.name ?: ""
    return resources.getQuantityString(R.plurals.who_count, people.size, people.size) + " · " + getString(R.string.who_in_now, now)
}

internal fun SettingsActivity.profilesPage() {
    val people = Profiles.all(this)
    if (people.size < 2) {
        row(R.string.who_set_up, getString(R.string.who_set_up_summary)) {
            askForText(R.string.who_your_name, "") { mine ->
                if (mine.isBlank()) return@askForText
                askForText(R.string.who_next_name, "") { next ->
                    if (next.isBlank()) return@askForText
                    addPerson(next) {
                        Profiles.begin(this, mine.trim())
                    }
                }
            }
        }
        return
    }
    val currentId = Profiles.currentId(this)
    for (person in people) {
        val notes = ArrayList<String>()
        if (person.id == currentId) notes.add(getString(R.string.who_now))
        if (person.locked) notes.add(getString(R.string.who_has_pin))
        row(person.name, notes.joinToString(" · ").ifEmpty { getString(R.string.who_tap_for_options) }) { personMenu(person) }
    }
    row(R.string.who_add, getString(R.string.who_add_summary)) {
        askForText(R.string.who_next_name, "") { name ->
            if (name.isNotBlank()) addPerson(name) {}
        }
    }
    row(R.string.who_ask, getString(Profiles.ASK_NAMES[Profiles.ask(this)])) {
        chooseOne(getString(R.string.who_ask), Profiles.ASK_NAMES.map { getString(it) }, Profiles.ask(this)) {
            Profiles.setAsk(this, it)
            build()
        }
    }
    row(R.string.who_switch, getString(R.string.who_switch_summary)) { WhoActivity.start(this) }
}

/** Asks how the new person starts (a copy of this setup, or fresh), then adds them. */
private fun SettingsActivity.addPerson(name: String, first: () -> Unit) {
    val ways = listOf(getString(R.string.who_start_copy), getString(R.string.who_start_fresh))
    chooseOne(getString(R.string.who_start_title, name.trim()), ways, 0) { which ->
        first()
        Profiles.add(this, name.trim(), copy = which == 0)
        Toast.makeText(this, getString(R.string.who_added, name.trim()), Toast.LENGTH_LONG).show()
        build()
    }
}

/** One person's options; changing a locked person asks their PIN first. */
private fun SettingsActivity.personMenu(person: Profiles.Profile) {
    val isCurrent = person.id == Profiles.currentId(this)
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()
    fun guarded(action: () -> Unit) = { if (person.locked) WhoActivity.askPin(this, person, action) else action() }
    if (!isCurrent) {
        labels.add(getString(R.string.who_switch_to, person.name))
        actions.add {
            val go = {
                thread(name = "TunaHome profile") {
                    val worked = try {
                        Profiles.switchTo(applicationContext, person.id)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        if (worked) Layouts.restart(this)
                        else Toast.makeText(this, R.string.layout_failed, Toast.LENGTH_LONG).show()
                    }
                }
                Unit
            }
            if (person.locked) WhoActivity.askPin(this, person, go) else go()
        }
    }
    labels.add(getString(R.string.who_rename))
    actions.add(guarded {
        askForText(R.string.who_rename, person.name) { name ->
            if (name.isNotBlank()) {
                Profiles.rename(this, person.id, name.trim())
                build()
            }
        }
    })
    labels.add(getString(R.string.who_colour))
    actions.add(guarded {
        val names = Profiles.COLOURS.indices.map { getString(R.string.who_colour_n, it + 1) }
        chooseOne(getString(R.string.who_colour), names, Profiles.COLOURS.indexOf(person.colour)) {
            Profiles.setColour(this, person.id, Profiles.COLOURS[it])
            build()
        }
    })
    if (person.locked) {
        labels.add(getString(R.string.who_remove_pin))
        actions.add(guarded {
            Profiles.setPin(this, person.id, null)
            build()
        })
    } else {
        labels.add(getString(R.string.who_set_pin))
        actions.add {
            choosePersonPin { pin ->
                Profiles.setPin(this, person.id, pin)
                build()
            }
        }
    }
    if (!isCurrent) {
        labels.add(getString(R.string.who_remove, person.name))
        actions.add(guarded {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.who_remove_title, person.name))
                .setMessage(R.string.who_remove_body)
                .setPositiveButton(R.string.who_remove_yes) { _, _ ->
                    Profiles.remove(this, person.id)
                    build()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
    }
    AlertDialog.Builder(this)
        .setTitle(person.name)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

/** A new PIN for a person: at least four digits. */
private fun SettingsActivity.choosePersonPin(then: (String) -> Unit) {
    val box = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine()
        setTextColor(themeColor(R.attr.tsText))
    }
    val pad = Math.round(Look.dp(this, 20f))
    val frame = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0) }
    frame.addView(box)
    AlertDialog.Builder(this)
        .setTitle(R.string.who_set_pin)
        .setMessage(R.string.who_pin_body)
        .setView(frame)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val pin = box.text.toString()
            if (pin.length < 4) Toast.makeText(this, R.string.who_pin_short, Toast.LENGTH_LONG).show()
            else then(pin)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    box.requestFocus()
}
