package com.tunastream.home

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cards of the viewer's own that open a web address or a link into an app (a YouTube channel, a
 * Plex library, a website), shown like apps in Favourites and their own sections. An address
 * can also be an Android "intent:" link, for things only an app's own link can reach.
 */
object Links {
    const val PREFIX = "link:"
    private const val KEY = "links"

    class Link(val id: String, val name: String, val address: String)

    fun all(context: Context): List<Link> {
        val found = ArrayList<Link>()
        try {
            val array = JSONArray(Prefs.of(context).getString(KEY, "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                found.add(Link(o.getString("id"), o.optString("name"), o.optString("address")))
            }
        } catch (e: Exception) {
            // None readable
        }
        return found
    }

    private fun save(context: Context, links: List<Link>) {
        val array = JSONArray()
        links.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("address", it.address)) }
        Prefs.of(context).edit().putString(KEY, array.toString()).apply()
        Prefs.appsChanged = true
    }

    /** Adds a link and returns the card's key (to put in a section). */
    fun add(context: Context, name: String, address: String): String {
        val id = java.lang.Long.toString(System.currentTimeMillis(), 36)
        save(context, all(context) + Link(id, name, tidy(address)))
        return PREFIX + id
    }

    fun change(context: Context, key: String, address: String) {
        save(context, all(context).map { if (PREFIX + it.id == key) Link(it.id, it.name, tidy(address)) else it })
    }

    fun remove(context: Context, key: String) {
        save(context, all(context).filter { PREFIX + it.id != key })
    }

    fun address(context: Context, key: String): String? = all(context).firstOrNull { PREFIX + it.id == key }?.address

    /** "example.com" becomes "https://example.com"; app links and intent: links are left as they are. */
    fun tidy(address: String): String {
        val text = address.trim()
        return if (text.contains("://") || text.startsWith("intent:")) text else "https://$text"
    }

    /** What opens a link. */
    fun intentFor(address: String): Intent = try {
        if (address.startsWith("intent:")) {
            Intent.parseUri(address, Intent.URI_INTENT_SCHEME).apply {
                // An intent: link can't be used to reach TunaHome's own or other private screens
                component = null
                selector = null
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(address))
        }
    } catch (e: Exception) {
        Intent(Intent.ACTION_VIEW, Uri.parse(address))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The cards for every link, pictured by the app that opens them. Slow: background thread. */
    fun entries(context: Context, names: Map<String, String>): List<AppEntry> {
        val pm = context.packageManager
        return all(context).map { link ->
            val key = PREFIX + link.id
            val launch = intentFor(link.address)
            // The app that opens it (not the "choose an app" screen)
            val handler = try {
                pm.resolveActivity(launch, 0)?.activityInfo?.takeIf { it.packageName != "android" }
            } catch (e: Exception) {
                null
            }
            val own = try {
                Pictures.loadTile(context, key)?.let { BitmapDrawable(context.resources, it) }
            } catch (e: Throwable) {
                null
            }
            val banner = try {
                handler?.loadBanner(pm) ?: handler?.applicationInfo?.loadBanner(pm)
            } catch (e: Exception) {
                null
            }
            val icon = try {
                handler?.loadIcon(pm)
            } catch (e: Exception) {
                null
            } ?: context.getDrawable(R.drawable.ic_link)
            AppEntry(
                pkg = key,
                label = names[key] ?: link.name,
                launch = launch,
                banner = banner,
                icon = icon,
                tv = true,
                own = own,
                appPkg = handler?.packageName ?: key,
                colour = IconShape.mainColour(own ?: banner ?: icon),
            )
        }
    }
}

/** Asks for a link's address, then its name, and adds it; [done] gets the new card's key. */
fun android.app.Activity.addLink(done: (String) -> Unit) {
    askForText(R.string.link_address, "https://", url = true) { address ->
        if (address.isBlank() || address.trim() == "https://") {
            return@askForText
        }
        val tidy = Links.tidy(address)
        // A first guess at the name: the site's name without "www."
        val guess = try {
            android.net.Uri.parse(tidy).host?.removePrefix("www.")?.substringBefore('.')
                ?.replaceFirstChar { it.uppercase() } ?: ""
        } catch (e: Exception) {
            ""
        }
        askForText(R.string.link_name, guess) { name ->
            done(Links.add(this, name.ifBlank { guess.ifBlank { tidy } }, tidy))
        }
    }
}
