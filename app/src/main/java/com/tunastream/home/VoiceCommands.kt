package com.tunastream.home

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.widget.Toast
import java.util.Locale

/**
 * Spoken commands in TunaHome's voice search:
 *
 * - "Open Netflix", "launch Plex", "start YouTube": opens the app.
 * - "Watch Stranger Things", "play Halo", "continue The Bear": opens it from Watch Next or PC
 *   games; otherwise an app by that name; otherwise asks the TV's own search.
 * - "Watch The Office on Netflix": searches inside that app, where the app allows it.
 * - "Find Spotify", "search for cooking shows": shows TunaHome's results.
 * - "Go to Favourites", "show games": jumps to a section on the home screen.
 * - "Sleep in 30 minutes", "cancel the sleep timer", "next background", "settings", "TV settings".
 *
 * Anything else is searched for, as before.
 */
object VoiceCommands {
    sealed class Result {
        /** Something was done; the search screen can close. */
        object Done : Result()

        /** Show results for this. */
        class Query(val text: String) : Result()

        /** Something was started that will close the search screen itself when it opens. */
        object Waiting : Result()
    }

    private val OPEN = listOf("open up", "open", "launch", "start", "run", "load")
    private val WATCH = listOf("watch", "play", "continue watching", "continue playing", "continue", "resume", "stream")
    private val FIND = listOf("search for", "search", "find me", "find", "look for", "look up")
    private val GO = listOf("go to", "jump to", "take me to", "show me", "show")

    fun run(activity: Activity, heard: String, everything: List<AppEntry>): Result {
        val said = tidy(heard)
        if (said.isEmpty()) return Result.Query(heard)

        // Sleep timer
        Regex("""(?:sleep|turn off|switch off|go to sleep|turn the tv off)\D*?(\d+)\s*(hour|hr|minute|min)""")
            .find(said)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@let
                val minutes = if (m.groupValues[2].startsWith("h")) n * 60 else n
                if (HomeButtonService.running == null) {
                    toast(activity, activity.getString(R.string.act_needs_service))
                    return Result.Done
                }
                SleepTimer.set(activity, minutes.coerceIn(1, 600))
                toast(activity, activity.getString(R.string.sleep_set,
                    activity.resources.getQuantityString(R.plurals.sleep_minutes, minutes, minutes)))
                return Result.Done
            }
        if (Regex("""(cancel|stop|turn off|switch off)( the)? sleep( timer)?""").containsMatchIn(said)) {
            SleepTimer.set(activity, 0)
            toast(activity, activity.getString(R.string.voice_sleep_off))
            return Result.Done
        }

        when (said) {
            "next background", "change background", "change the background", "new background", "another background" -> {
                home(activity, HotkeyActions.NEXT_BACKGROUND, null)
                return Result.Done
            }
            "tv settings", "android settings", "system settings", "shield settings" -> {
                HomeActivity.openTvSettings(activity)
                return Result.Done
            }
            "settings", "tunahome settings", "home settings", "open settings" -> {
                home(activity, HotkeyActions.SETTINGS_PAGE, SettingsActivity.MAIN)
                return Result.Done
            }
            "quick changes", "customise", "customize" -> {
                home(activity, HotkeyActions.QUICK, null)
                return Result.Done
            }
            "home", "go home", "home screen" -> {
                home(activity, HotkeyActions.HOME_TOP, null)
                return Result.Done
            }
        }

        after(said, FIND)?.let { return Result.Query(it) }

        after(said, GO)?.let { name ->
            val sections = Rows.load(activity).filter { it.visible }
            val section = best(sections.map { it.name(activity) to it }, name)
            if (section != null) {
                home(activity, HotkeyActions.SECTION, section.id)
                return Result.Done
            }
            openApp(activity, everything.filter { !it.isInsideApp }, name)?.let { return it }
            return Result.Query(name)
        }

        after(said, OPEN)?.let { name ->
            openApp(activity, everything.filter { !it.isInsideApp }, name)?.let { return it }
            return Result.Query(name)
        }

        after(said, WATCH)?.let { what ->
            // "… on Netflix": search inside that app
            val on = what.lastIndexOf(" on ")
            if (on > 0) {
                val title = what.substring(0, on).trim()
                val appName = what.substring(on + 4).trim()
                val app = best(everything.filter { !it.isInsideApp && !it.isFolder }.map { it.label to it }, appName)
                if (app != null && title.isNotEmpty()) {
                    return searchInApp(activity, app, title)
                }
            }
            // Something to carry on with: Watch Next or a PC game
            best(everything.filter { it.isInsideApp }.map { it.label to it }, what)?.let { item ->
                return launch(activity, item)
            }
            openApp(activity, everything.filter { !it.isInsideApp }, what)?.let { return it }
            searchEverywhere(activity, what)
            return Result.Done
        }

        return Result.Query(heard)
    }

    private fun openApp(activity: Activity, apps: List<AppEntry>, name: String): Result? {
        val app = best(apps.filter { !it.isFolder }.map { it.label to it }, name) ?: return null
        return launch(activity, app)
    }

    private fun launch(activity: Activity, app: AppEntry): Result {
        toast(activity, activity.getString(R.string.voice_opening, app.label))
        Launch.open(activity, app, opened = { activity.finish() })
        return Result.Waiting
    }

    /** Asks an app to search for [title] itself; apps that can't are simply opened. */
    private fun searchInApp(activity: Activity, app: AppEntry, title: String): Result {
        val search = Intent(Intent.ACTION_SEARCH)
            .setPackage(app.appPkg)
            .putExtra(SearchManager.QUERY, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (search.resolveActivity(activity.packageManager) != null && !AppLock.isLocked(activity, app.appPkg)) {
            try {
                activity.startActivity(search)
                toast(activity, activity.getString(R.string.voice_searching_in, title, app.label))
                return Result.Done
            } catch (e: Exception) {
                // Opened instead
            }
        }
        return launch(activity, app)
    }

    /** The TV's own search, for films and shows that aren't in TunaHome. */
    fun searchEverywhere(activity: Activity, text: String) {
        val intents = listOf(
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, text).setPackage("com.google.android.katniss"),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text),
            Intent(Intent.ACTION_ASSIST).putExtra(SearchManager.QUERY, text),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next way
            }
        }
        toast(activity, activity.getString(R.string.search_everywhere_failed))
    }

    private fun home(activity: Activity, action: String, arg: String?) {
        activity.startActivity(
            Intent(activity, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(HotkeyActions.EXTRA_ACTION, action)
                .putExtra(HotkeyActions.EXTRA_ARG, arg)
        )
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    /** Lower case, no punctuation, without "please", "can you" and the like. */
    private fun tidy(heard: String): String {
        var s = heard.lowercase(Locale.getDefault()).replace(Regex("""[^\p{L}\p{N}' ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        for (lead in listOf("ok google ", "hey google ", "please ", "can you ", "could you ", "i want to ", "i'd like to ", "let's ", "lets ")) {
            if (s.startsWith(lead)) s = s.removePrefix(lead).trim()
        }
        s = s.removeSuffix(" please").trim()
        return s
    }

    /** What follows the first of [words] that [said] starts with, if any. */
    private fun after(said: String, words: List<String>): String? {
        for (w in words) {
            if (said.startsWith("$w ")) {
                val rest = said.removePrefix("$w ").removePrefix("the app ").removeSuffix(" app").trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    /**
     * The closest name to [wanted]: the same name, then one starting with it, then a word in it
     * starting with it, then one containing it. Null when nothing is close.
     */
    private fun <T> best(named: List<Pair<String, T>>, wanted: String): T? {
        val w = IconPacks.plain(wanted)
        if (w.isEmpty()) return null
        var found: T? = null
        var score = Int.MAX_VALUE
        var length = Int.MAX_VALUE
        for ((name, value) in named) {
            val plain = IconPacks.plain(name)
            val words = name.lowercase(Locale.getDefault()).split(' ', '-', ':', '.', '·').map { IconPacks.plain(it) }
            val s = when {
                plain == w -> 0
                plain.startsWith(w) || w.startsWith(plain) && plain.length >= 3 -> 1
                words.any { it.isNotEmpty() && it.startsWith(w) } -> 2
                w.length >= 3 && plain.contains(w) -> 3
                else -> continue
            }
            if (s < score || (s == score && plain.length < length)) {
                found = value
                score = s
                length = plain.length
            }
        }
        return found
    }
}
