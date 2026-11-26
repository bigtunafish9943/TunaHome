package com.tunastream.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The small web server behind "Set up from your phone". It runs only while that screen is open
 * on the TV, answers only on the home network, and only to a phone that typed the code shown on
 * the TV. Through it the phone can reorder sections, rename and hide apps, add web links, send
 * pictures and videos for the background, and change the most used looks.
 */
class PhoneServer(private val context: Context, private val said: (String) -> Unit) {
    var code = newCode()
        private set
    var port = 0
        private set

    /** Something was changed: the home screen loads again when the TV screen closes. */
    @Volatile
    var changed = false

    private val random = SecureRandom()
    private val tokens = java.util.Collections.synchronizedSet(HashSet<String>())
    private var server: ServerSocket? = null
    private var wrong = 0
    /** Wrong PINs for someone's profile: after five, no more tries until the screen opens again. */
    private var wrongPins = 0
    private val icons = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun newCode() = (100000 + SecureRandom().nextInt(900000)).toString()

    fun start(): Boolean {
        for (p in PORTS) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: Exception) {
                // Taken: try the next
            }
        }
        val socket = server ?: return false
        thread(name = "TunaHome phone setup") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(name = "TunaHome phone request") { handle(client) }
            }
        }
        return true
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
            // Already closed
        }
        server = null
        tokens.clear()
    }

    // ------------------------------------------------------------------ requests

    private class Request(val method: String, val path: String, val query: Map<String, String>,
        val headers: Map<String, String>, val input: InputStream) {
        val length: Long get() = headers["content-length"]?.toLongOrNull() ?: 0L
        fun body(): String {
            val n = length.coerceAtMost(MAX_JSON.toLong()).toInt()
            val bytes = ByteArray(n)
            var read = 0
            while (read < n) {
                val got = input.read(bytes, read, n - read)
                if (got < 0) break
                read += got
            }
            return String(bytes, 0, read, Charsets.UTF_8)
        }
        fun json(): JSONObject = try { JSONObject(body()) } catch (e: Exception) { JSONObject() }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 30_000
                // The home network only: never answer anyone from the internet
                val from = socket.inetAddress
                if (!(from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress)) return
                val input = socket.getInputStream().buffered()
                val out = socket.getOutputStream()
                val request = read(input) ?: return
                route(request, out)
                out.flush()
            } catch (e: Exception) {
                // The phone went away mid-request
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (head.length < 16 * 1024) {
            val c = input.read()
            if (c < 0) return null
            head.append(c.toChar())
            last4 = (last4 shl 8) or c
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString().split("\r\n")
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val target = first[1]
        val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
        val query = HashMap<String, String>()
        target.substringAfter('?', "").split('&').filter { it.contains('=') }.forEach {
            query[URLDecoder.decode(it.substringBefore('='), "UTF-8")] = URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(first[0], path, query, headers, input)
    }

    private fun route(r: Request, out: OutputStream) {
        when {
            r.method == "GET" && (r.path == "/" || r.path == "/index.html") ->
                send(out, 200, "text/html; charset=utf-8", context.assets.open("phone/index.html").use { it.readBytes() })
            r.method == "POST" && r.path == "/api/login" -> login(r, out)
            r.path.startsWith("/photo/") -> {
                if ((r.query["t"] ?: "") !in tokens) return send(out, 403, "text/plain", "no".toByteArray())
                val file = java.io.File(java.io.File(context.filesDir, "profiles"), r.path.removePrefix("/photo/").filter { it.isLetterOrDigit() } + "-photo.png")
                if (!file.exists()) send(out, 404, "text/plain", "none".toByteArray())
                else send(out, 200, "image/png", file.readBytes())
            }
            r.path.startsWith("/icon/") -> {
                if ((r.query["t"] ?: "") !in tokens) return send(out, 403, "text/plain", "no".toByteArray())
                val png = icon(r.path.removePrefix("/icon/"))
                if (png == null) send(out, 404, "text/plain", "none".toByteArray())
                else send(out, 200, "image/png", png, cache = true)
            }
            r.path.startsWith("/api/") -> {
                if ((r.headers["x-token"] ?: "") !in tokens) return send(out, 403, "application/json", """{"error":"code"}""".toByteArray())
                val answer = try {
                    api(r)
                } catch (e: Exception) {
                    JSONObject().put("error", e.message ?: e.toString())
                }
                send(out, if (answer.has("error")) 400 else 200, "application/json", answer.toString().toByteArray())
            }
            else -> send(out, 404, "text/plain", "Not here".toByteArray())
        }
    }

    private fun login(r: Request, out: OutputStream) {
        val tried = r.json().optString("code").trim()
        synchronized(this) {
            if (tried == code) {
                wrong = 0
                val token = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { String.format("%02x", it) }
                tokens.add(token)
                said(context.getString(R.string.phone_connected))
                send(out, 200, "application/json", JSONObject().put("token", token).toString().toByteArray())
                return
            }
            wrong++
            if (wrong >= 5) {
                // Too many guesses: a new code, shown on the TV
                wrong = 0
                code = newCode()
                said(context.getString(R.string.phone_new_code))
            }
        }
        send(out, 403, "application/json", """{"error":"code"}""".toByteArray())
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, cache: Boolean = false) {
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Bad Request" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            (if (cache) "Cache-Control: max-age=600\r\n" else "Cache-Control: no-store\r\n") +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
    }

    // ------------------------------------------------------------------ what the phone can do

    private fun apps(): List<AppEntry> = Apps.cached.ifEmpty { Apps.load(context) }

    private fun api(r: Request): JSONObject = when (r.path) {
        "/api/state" -> state()
        "/api/order" -> r.json().let { order(it.getString("section"), it.getJSONArray("keys").let { a -> (0 until a.length()).map { i -> a.getString(i) } }) }
        "/api/section" -> section(r.json())
        "/api/app" -> app(r.json())
        "/api/link" -> link(r.json())
        "/api/look" -> look(r.json())
        "/api/background" -> background(r)
        "/api/move" -> move(r.json())
        "/api/icon" -> ownIcon(r)
        "/api/icon/remove" -> r.json().getString("key").let { key ->
            Pictures.removeTile(context, key)
            icons.remove(key)
            Apps.forget()
            done(context.getString(R.string.phone_did_icon, label(key)))
            JSONObject().put("ok", true)
        }
        "/api/people" -> people()
        "/api/person" -> person(r.json())
        "/api/person/photo" -> personPhoto(r)
        else -> JSONObject().put("error", "unknown")
    }

    /** Everything the page shows: sections with their apps, every app, and the looks. */
    private fun state(): JSONObject {
        val all = apps()
        val hidden = Prefs.hidden(context)
        val names = Prefs.appNames(context)
        val favourites = Prefs.favourites(context)
        val byKey = all.associateBy { it.pkg }
        val folders = Folders.load(context)
        val inFolder = folders.flatMap { it.apps }.toSet()
        val folderCards = folders.map { Folders.entry(context, it, byKey) }.associateBy { it.pkg }
        val visible = all.filter { it.pkg !in hidden }
        val loose = visible.filter { it.pkg !in inFolder && !it.isLink }

        fun entry(key: String, label: String) = JSONObject().put("key", key).put("label", label)
        fun ordered(list: List<AppEntry>, order: List<String>): List<AppEntry> {
            val place = HashMap<String, Int>()
            order.forEachIndexed { i, k -> place[k] = i }
            return if (order.isEmpty()) list else list.sortedBy { place[it.pkg] ?: Int.MAX_VALUE }
        }

        val sections = JSONArray()
        for (row in Rows.load(context)) {
            val o = JSONObject().put("id", row.id).put("name", row.name(context)).put("visible", row.visible)
            val canOrder = !row.ownOrder
            o.put("canOrder", canOrder)
            o.put("sorted", row.sort != Rows.SORT_OWN)
            val items: List<AppEntry>? = when (row.kind) {
                Rows.FAVOURITES -> favourites.mapNotNull { byKey[it] ?: folderCards[it] }.filter { it.pkg !in hidden }
                Rows.CUSTOM -> row.apps.mapNotNull { byKey[it] ?: folderCards[it] }.filter { it.pkg !in hidden }
                Rows.TV -> ordered(loose.filter { it.tv } + folderCards.values.filter { card ->
                    val named = favourites.contains(card.pkg) || Rows.load(context).any { it.custom && card.pkg in it.apps } ||
                        Prefs.order(context, Rows.MOBILE).contains(card.pkg)
                    !named || Prefs.order(context, row.id).contains(card.pkg)
                }, Prefs.order(context, row.id))
                Rows.MOBILE -> ordered(loose.filter { !it.tv } + folderCards.values.filter { Prefs.order(context, row.id).contains(it.pkg) },
                    Prefs.order(context, row.id))
                Rows.SIDELOADED -> ordered(loose.filter { it.sideloaded }, Prefs.order(context, row.id))
                Rows.GAMES -> ordered(loose.filter { it.group == Apps.GAME }, Prefs.order(context, row.id))
                Rows.VIDEO -> ordered(loose.filter { it.group == Apps.VIDEO }, Prefs.order(context, row.id))
                Rows.MUSIC -> ordered(loose.filter { it.group == Apps.MUSIC }, Prefs.order(context, row.id))
                else -> null
            }
            val list = JSONArray()
            items?.forEach { list.put(entry(it.pkg, it.label)) }
            o.put("apps", list)
            o.put("custom", row.custom || row.kind == Rows.FAVOURITES)
            sections.put(o)
        }

        val everyApp = JSONArray()
        all.sortedBy { it.label.lowercase() }.forEach {
            everyApp.put(entry(it.pkg, it.label).put("hidden", it.pkg in hidden).put("renamed", names.containsKey(it.pkg))
                .put("favourite", it.pkg in favourites).put("link", it.isLink).put("ownIcon", Pictures.hasTile(context, it.pkg)))
        }

        val looks = JSONArray()
        fun choice(id: String, title: Int, names: List<String>, current: Int) {
            looks.put(JSONObject().put("id", id).put("title", context.getString(title))
                .put("options", JSONArray(names)).put("current", current))
        }
        choice("theme", R.string.set_theme, TunaTheme.IDS.map { context.getString(TunaTheme.nameOf(it)) }, TunaTheme.IDS.indexOf(TunaTheme.chosen(context)))
        for ((id, setting) in LOOKS) {
            choice(id, setting.first, setting.second.names.map { context.getString(it) }, setting.second.get(context))
        }
        choice("preset", R.string.preset_title, Look.PRESETS.map { context.getString(it) }, -1)

        return JSONObject().put("sections", sections).put("apps", everyApp).put("looks", looks)
    }

    /** New order for a section, keeping anything the phone wasn't shown where it was. */
    private fun order(id: String, keys: List<String>): JSONObject {
        val row = Rows.load(context).firstOrNull { it.id == id } ?: return JSONObject().put("error", "section")
        fun merge(old: List<String>) = keys + old.filter { it !in keys }
        when {
            row.kind == Rows.FAVOURITES -> Prefs.setFavourites(context, merge(Prefs.favourites(context)))
            row.custom -> Rows.update(context, id, restyle = false) { r ->
                val merged = merge(r.apps)
                r.apps.clear()
                r.apps.addAll(merged)
            }
            else -> Prefs.setOrder(context, id, merge(Prefs.order(context, id)))
        }
        if (row.sort != Rows.SORT_OWN) {
            Rows.update(context, id, restyle = false) { it.sort = Rows.SORT_OWN }
        }
        done(context.getString(R.string.phone_did_order, row.name(context)))
        return JSONObject().put("ok", true)
    }

    private fun section(j: JSONObject): JSONObject {
        val id = j.getString("id")
        if (j.has("visible")) Rows.update(context, id) { it.visible = j.getBoolean("visible") }
        if (j.has("name")) {
            val name = j.getString("name").trim()
            if (name.isNotEmpty()) Rows.update(context, id) { it.title = name }
        }
        done(context.getString(R.string.phone_did_section))
        return JSONObject().put("ok", true)
    }

    private fun app(j: JSONObject): JSONObject {
        val key = j.getString("key")
        if (j.has("name")) {
            Prefs.setAppName(context, key, if (j.isNull("name")) null else j.getString("name").trim().ifEmpty { null })
        }
        if (j.has("hidden")) {
            val hidden = Prefs.hidden(context)
            if (j.getBoolean("hidden")) hidden.add(key) else hidden.remove(key)
            Prefs.setHidden(context, hidden)
        }
        if (j.has("favourite")) {
            val favourites = Prefs.favourites(context)
            if (j.getBoolean("favourite")) { if (key !in favourites) favourites.add(key) } else favourites.remove(key)
            Prefs.setFavourites(context, favourites)
        }
        if (j.has("addTo")) {
            val id = j.getString("addTo")
            Rows.update(context, id, restyle = false) { if (key !in it.apps) it.apps.add(key) }
        }
        if (j.has("removeFrom")) {
            val id = j.getString("removeFrom")
            Rows.update(context, id, restyle = false) { it.apps.remove(key) }
        }
        done(context.getString(R.string.phone_did_app))
        return JSONObject().put("ok", true)
    }

    private fun link(j: JSONObject): JSONObject {
        val address = j.getString("address").trim()
        if (address.isEmpty()) return JSONObject().put("error", "address")
        val name = j.optString("name").trim().ifEmpty { Uri.parse(Links.tidy(address)).host?.removePrefix("www.") ?: address }
        val key = Links.add(context, name, address)
        val section = j.optString("section")
        val row = Rows.load(context).firstOrNull { it.id == section }
        if (row == null || row.kind == Rows.FAVOURITES) {
            Prefs.setFavourites(context, Prefs.favourites(context).apply { add(key) })
        } else {
            Rows.update(context, row.id, restyle = false) { it.apps.add(key) }
        }
        Apps.forget()
        done(context.getString(R.string.phone_did_link, name))
        return JSONObject().put("ok", true)
    }

    private fun look(j: JSONObject): JSONObject {
        val id = j.getString("id")
        val value = j.getInt("value")
        when (id) {
            "theme" -> TunaTheme.IDS.getOrNull(value)?.let {
                TunaTheme.setChosen(context, it)
                Look.touch(context)
            }
            "preset" -> Look.applyPreset(context, value)
            else -> LOOKS[id]?.second?.set(context, value)
        }
        done(context.getString(R.string.phone_did_look))
        return JSONObject().put("ok", true)
    }

    /** A picture or video sent from the phone, saved on the TV and added to the background. */
    private fun background(r: Request): JSONObject {
        val type = (r.query["type"] ?: r.headers["content-type"] ?: "").lowercase()
        val isVideo = type.startsWith("video/")
        if (!isVideo && !type.startsWith("image/")) return JSONObject().put("error", "type")
        if (r.length <= 0 || r.length > MAX_UPLOAD) return JSONObject().put("error", "size")
        val extension = when {
            type.contains("png") -> "png"
            type.contains("gif") -> "gif"
            type.contains("webp") -> "webp"
            type.startsWith("image/") -> "jpg"
            type.contains("webm") -> "webm"
            type.contains("matroska") -> "mkv"
            else -> "mp4"
        }
        val file = Pictures.newBackgroundFile(context, extension)
        var left = r.length
        FileOutputStream(file).use { out ->
            val buf = ByteArray(256 * 1024)
            while (left > 0) {
                val n = r.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        if (left > 0) {
            file.delete()
            return JSONObject().put("error", "incomplete")
        }
        Backdrop.setVideos(context, Backdrop.videos(context) + Backdrop.Own(Uri.fromFile(file).toString(), isVideo))
        Backdrop.SOURCE.set(context, Backdrop.SOURCE_VIDEOS)
        done(context.getString(if (isVideo) R.string.phone_did_video else R.string.phone_did_picture))
        return JSONObject().put("ok", true)
    }

    private fun label(key: String) = apps().firstOrNull { it.pkg == key }?.label ?: key

    /** Moves an app from one of the viewer's own sections (or Favourites) to another. */
    private fun move(j: JSONObject): JSONObject {
        val key = j.getString("key")
        val rows = Rows.load(context)
        val from = rows.firstOrNull { it.id == j.optString("from") }
        val to = rows.firstOrNull { it.id == j.getString("to") } ?: return JSONObject().put("error", "section")
        if (from?.id == to.id) return JSONObject().put("ok", true)
        when {
            from == null -> {}
            from.kind == Rows.FAVOURITES -> Prefs.setFavourites(context, Prefs.favourites(context).apply { remove(key) })
            from.custom -> Rows.update(context, from.id, restyle = false) { it.apps.remove(key) }
        }
        when {
            to.kind == Rows.FAVOURITES -> Prefs.setFavourites(context, Prefs.favourites(context).apply { if (key !in this) add(key) })
            to.custom -> Rows.update(context, to.id, restyle = false) { if (key !in it.apps) it.apps.add(key) }
            else -> return JSONObject().put("error", "section")
        }
        done(context.getString(R.string.phone_did_move, label(key), to.name(context)))
        return JSONObject().put("ok", true)
    }

    /** Reads an upload into a file of its own, no bigger than [max]. Null if it didn't all arrive. */
    private fun receive(r: Request, max: Long, into: java.io.File): Boolean {
        if (r.length <= 0 || r.length > max) return false
        var left = r.length
        FileOutputStream(into).use { out ->
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = r.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        return left == 0L
    }

    /** A picture from the phone for one app's card. */
    private fun ownIcon(r: Request): JSONObject {
        val key = r.query["key"] ?: return JSONObject().put("error", "key")
        if (apps().none { it.pkg == key }) return JSONObject().put("error", "app")
        val temp = java.io.File(context.cacheDir, "phone-icon.tmp")
        try {
            if (!receive(r, MAX_PICTURE, temp)) return JSONObject().put("error", "size")
            if (!Pictures.saveTile(context, key, Uri.fromFile(temp))) return JSONObject().put("error", "picture")
        } finally {
            temp.delete()
        }
        icons.remove(key)
        Apps.forget()
        done(context.getString(R.string.phone_did_icon, label(key)))
        return JSONObject().put("ok", true)
    }

    // ------------------------------------------------------------------ who's watching

    private fun people(): JSONObject {
        val list = JSONArray()
        val currentId = Profiles.currentId(context)
        Profiles.all(context).forEach {
            list.put(JSONObject().put("id", it.id).put("name", it.name).put("colour", String.format("#%06X", it.colour and 0xFFFFFF))
                .put("locked", it.locked).put("current", it.id == currentId).put("photo", Profiles.hasPhoto(context, it.id)))
        }
        val colours = JSONArray()
        Profiles.COLOURS.forEach { colours.put(String.format("#%06X", it and 0xFFFFFF)) }
        return JSONObject().put("people", list).put("colours", colours)
            .put("ask", Profiles.ask(context))
            .put("askNames", JSONArray(Profiles.ASK_NAMES.map { context.getString(it) }))
    }

    /** Someone's own changes need their PIN, if they have one (wrong guesses run out). */
    private fun unlocked(id: String, pin: String?): Profiles.Profile? {
        val person = Profiles.all(context).firstOrNull { it.id == id } ?: return null
        if (!person.locked) return person
        synchronized(this) {
            if (wrongPins >= 5 || pin.isNullOrEmpty()) return null
            if (Profiles.pinMatches(person, pin)) return person
            wrongPins++
        }
        return null
    }

    private fun person(j: JSONObject): JSONObject {
        val name = j.optString("name").trim()
        when (j.getString("do")) {
            "add" -> {
                if (name.isEmpty()) return JSONObject().put("error", "name")
                if (Profiles.all(context).isEmpty()) {
                    val first = j.optString("first").trim()
                    if (first.isEmpty()) return JSONObject().put("error", "first")
                    Profiles.begin(context, first)
                }
                Profiles.add(context, name, copy = j.optBoolean("copy", true))
            }
            "ask" -> Profiles.setAsk(context, j.getInt("value").coerceIn(0, Profiles.ASK_NAMES.size - 1))
            else -> {
                val id = j.getString("id")
                val person = unlocked(id, j.optString("pin")) ?: return JSONObject().put("error", "pin")
                when (j.getString("do")) {
                    "rename" -> if (name.isNotEmpty()) Profiles.rename(context, person.id, name)
                    "colour" -> Profiles.COLOURS.getOrNull(j.getInt("value"))?.let { Profiles.setColour(context, person.id, it) }
                    "nophoto" -> Profiles.removePhoto(context, person.id)
                    "remove" -> {
                        if (person.id == Profiles.currentId(context)) return JSONObject().put("error", "current")
                        Profiles.remove(context, person.id)
                    }
                    "check" -> {}
                    else -> return JSONObject().put("error", "unknown")
                }
            }
        }
        done(context.getString(R.string.phone_did_person))
        return JSONObject().put("ok", true)
    }

    private fun personPhoto(r: Request): JSONObject {
        val person = unlocked(r.query["id"] ?: "", r.query["pin"]) ?: return JSONObject().put("error", "pin")
        val temp = java.io.File(context.cacheDir, "phone-photo.tmp")
        try {
            if (!receive(r, MAX_PICTURE, temp)) return JSONObject().put("error", "size")
            if (!Profiles.savePhoto(context, person.id, Uri.fromFile(temp))) return JSONObject().put("error", "picture")
        } finally {
            temp.delete()
        }
        done(context.getString(R.string.phone_did_person))
        return JSONObject().put("ok", true)
    }

    private fun done(what: String) {
        changed = true
        Prefs.appsChanged = true
        said(what)
    }

    /** An app's picture for the page, as a PNG no taller than 120. */
    private fun icon(key: String): ByteArray? {
        icons[key]?.let { return it }
        val app = apps().firstOrNull { it.pkg == key } ?: return null
        val wide = Look.TILE_STYLE.get(context) == Look.TILE_BANNER
        val picture: Drawable = app.own ?: (if (wide) app.banner ?: app.icon else app.icon ?: app.banner) ?: return null
        val h = 120
        val iw = picture.intrinsicWidth.takeIf { it > 0 } ?: h
        val ih = picture.intrinsicHeight.takeIf { it > 0 } ?: h
        val w = (h * iw / ih.toFloat()).toInt().coerceIn(40, 240)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copy = picture.constantState?.newDrawable()?.mutate() ?: picture
        copy.setBounds(0, 0, w, h)
        copy.draw(Canvas(bitmap))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        icons[key] = bytes
        return bytes
    }

    companion object {
        private val PORTS = 8686..8696
        private const val MAX_JSON = 256 * 1024
        private const val MAX_UPLOAD = 600L * 1024 * 1024
        private const val MAX_PICTURE = 30L * 1024 * 1024

        /** The looks the phone page offers, by name. */
        private val LOOKS: Map<String, Pair<Int, Look.Choice>> by lazy {
            linkedMapOf(
                "accent" to (R.string.set_accent to Look.ACCENT),
                "cards" to (R.string.set_tile_style to Look.TILE_STYLE),
                "size" to (R.string.set_card_size to Look.CARD_SIZE),
                "effects" to (R.string.set_fx to Effects.CHOICE),
                "panel" to (R.string.set_section_panel to Look.SECTION_PANEL),
                "clock" to (R.string.set_clock_face to Look.CLOCK_FACE),
                "dim" to (R.string.bg_dim to Backdrop.DIM),
            )
        }

        /** The TV's address on the home network, for the phone to open. */
        fun address(context: Context): InetAddress? {
            return try {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                val props = cm?.getLinkProperties(cm.activeNetwork)
                props?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            } catch (e: Exception) {
                null
            }
        }
    }
}
