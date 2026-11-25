package com.tunastream.home

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/**
 * Finding an app by typing or speaking. Matches the start of an app's name first, then the start
 * of any word in it, then anywhere in it, then its letters in order ("ytm" finds YouTube Music).
 * Hidden apps are found too, and Watch Next shows by title. "Search everywhere" hands the words
 * to the TV's own search.
 */
class SearchActivity : BaseActivity() {
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var everywhere: TextView
    private lateinit var adapter: AppAdapter
    private var apps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.query)
        status = findViewById(R.id.searchStatus)
        everywhere = findViewById(R.id.searchEverywhere)

        val tiles = TileStyle(this)
        adapter = AppAdapter(tiles, Rows.WRAP, { Launch.open(this, it) }, {}, {})
        adapter.canMove = false
        val list = findViewById<RecyclerView>(R.id.results)
        val across = maxOf(1, (resources.displayMetrics.widthPixels - Math.round(Look.dp(this, 96f))) /
            (tiles.width + 2 * tiles.gap))
        adapter.wrapColumns = across
        list.layoutManager = GridLayoutManager(this, across)
        list.adapter = adapter
        list.itemAnimator = null

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                update()
            }
        })
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                // Done typing: down to the results
                hideKeyboard()
                list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() ?: everywhere.requestFocus() }
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.voice).setOnClickListener { listen() }
        everywhere.setOnClickListener { searchEverywhere() }

        apps = Apps.cached
        update()
        thread(name = "TunaHome search") {
            val all = try {
                Apps.load(applicationContext) + WatchNext.load(applicationContext)
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    apps = all
                    update()
                }
            }
        }

        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            listen()
        } else {
            query.requestFocus()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun update() {
        val text = query.text.toString().trim()
        val results = if (text.isEmpty()) emptyList() else match(text)
        adapter.submit(results)
        status.text = when {
            text.isEmpty() -> getString(R.string.search_start)
            results.isEmpty() -> getString(R.string.search_none, text)
            else -> resources.getQuantityString(R.plurals.search_found, results.size, results.size)
        }
        everywhere.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        everywhere.text = getString(R.string.search_everywhere, text)
    }

    private fun match(text: String): List<AppEntry> {
        val wanted = IconPacks.plain(text)
        if (wanted.isEmpty()) {
            return emptyList()
        }
        val scored = ArrayList<Pair<Int, AppEntry>>()
        for (app in apps) {
            val name = IconPacks.plain(app.label)
            val words = app.label.lowercase().split(' ', '-', '·', ':', '.').map { IconPacks.plain(it) }
            val score = when {
                name.startsWith(wanted) -> 0
                words.any { it.startsWith(wanted) } -> 1
                name.contains(wanted) -> 2
                inOrder(wanted, name) -> 3
                else -> continue
            }
            scored.add(score to app)
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.label.lowercase() })).map { it.second }.take(60)
    }

    /** Whether every letter of [wanted] appears in [name], in order. */
    private fun inOrder(wanted: String, name: String): Boolean {
        var at = 0
        for (c in name) {
            if (at < wanted.length && c == wanted[at]) at++
        }
        return at == wanted.length
    }

    // ------------------------------------------------------------------ voice

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_say))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.search_no_voice, Toast.LENGTH_LONG).show()
            query.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VOICE || resultCode != RESULT_OK) {
            return
        }
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        query.setText(heard)
        query.setSelection(heard.length)
        // Spoken: go straight to the best match, ready to open with one press
        val list = findViewById<RecyclerView>(R.id.results)
        list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() ?: everywhere.requestFocus() }
    }

    /** The TV's own search (Google), for films, shows and anything that isn't an app. */
    private fun searchEverywhere() {
        val text = query.text.toString().trim()
        val intents = listOf(
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, text).setPackage("com.google.android.katniss"),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text),
            Intent(Intent.ACTION_ASSIST).putExtra(SearchManager.QUERY, text),
        )
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Try the next way
            }
        }
        Toast.makeText(this, R.string.search_everywhere_failed, Toast.LENGTH_LONG).show()
    }

    /** Back from the results returns to the box first; from the box it closes. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !query.hasFocus()) {
            query.requestFocus()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !query.hasFocus()) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_VOICE = "voice"
        private const val REQUEST_VOICE = 7
    }
}
