package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Phone remote: the TV's remote on a phone, for when the real one is lost in the sofa. Off unless
 * turned on (TunaHome settings > Phone remote). While on, the Home button service answers on the
 * home network only; a phone joins once with the code shown in settings, and is remembered until
 * "Forget phones". Back, Home, Open apps, play and pause, volume, opening any app, sleep and Find my
 * remote, and a touchpad that moves TunaHome's pointer and taps where it points (the D-pad itself
 * needs Android 13, where it's on the page too).
 */
object PhoneRemote {
    val ON = Look.Switch("phone_remote_on", false)
    private const val PHONES = "phone_remote_phones"
    private const val CODE = "phone_remote_code"
    private val PORTS = intArrayOf(8791, 8792, 8793, 8794, 8795)
    private const val MAX_BODY = 4096

    private val random = SecureRandom()
    private var server: ServerSocket? = null
    var port = 0
        private set
    private var wrong = 0

    fun code(context: Context): String {
        val prefs = Prefs.of(context)
        return prefs.getString(CODE, null) ?: newCode(context)
    }

    fun newCode(context: Context): String {
        val code = (100000 + random.nextInt(900000)).toString()
        Prefs.of(context).edit().putString(CODE, code).apply()
        return code
    }

    private fun phones(context: Context): Set<String> = Prefs.of(context).getStringSet(PHONES, emptySet()) ?: emptySet()

    fun forgetPhones(context: Context) {
        Prefs.of(context).edit().remove(PHONES).apply()
        newCode(context)
    }

    /** Where to open it on the phone, e.g. http://192.168.1.30:8791 */
    fun address(): String? {
        if (port == 0) return null
        val ip = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress && it.hostAddress?.contains(':') == false }
        } catch (e: Exception) {
            null
        } ?: return null
        return "http://${ip.hostAddress}:$port"
    }

    // ------------------------------------------------------------------ settings

    /** TunaHome settings > Phone remote. */
    fun manage(activity: Activity, changed: () -> Unit) {
        val service = HomeButtonService.running
        if (service == null) {
            Toast.makeText(activity, R.string.remote_needs_service, Toast.LENGTH_LONG).show()
            return
        }
        if (!ON.get(activity)) {
            QuietDialog.Builder(activity)
                .setTitle(R.string.remote_title)
                .setMessage(R.string.remote_off_body)
                .setPositiveButton(R.string.remote_turn_on) { _, _ ->
                    ON.set(activity, true)
                    start(service)
                    changed()
                    manage(activity, changed)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        start(service)
        val where = address()
        if (where == null) {
            Toast.makeText(activity, R.string.remote_no_network, Toast.LENGTH_LONG).show()
            return
        }
        val code = code(activity)
        val pad = Math.round(Look.dp(activity, 20f))
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val size = Math.round(Look.dp(activity, 180f))
        QrCode.make("$where/#code=$code", size)?.let { bitmap ->
            box.addView(ImageView(activity).apply { setImageBitmap(bitmap) }, LinearLayout.LayoutParams(size, size))
        }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.remote_on_body, where, code)
            setTextColor(activity.themeColor(R.attr.tsText))
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        })
        QuietDialog.Builder(activity)
            .setTitle(R.string.remote_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.remote_turn_off) { _, _ ->
                ON.set(activity, false)
                stop()
                changed()
            }
            .setNeutralButton(R.string.remote_forget) { _, _ ->
                forgetPhones(activity)
                Toast.makeText(activity, R.string.remote_forgotten, Toast.LENGTH_LONG).show()
                changed()
            }
            .show()
    }

    // ------------------------------------------------------------------ the server

    fun start(service: HomeButtonService) {
        if (server != null || !ON.get(service)) return
        for (p in PORTS) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: Exception) {
                // Taken: the next
            }
        }
        val socket = server ?: return
        thread(name = "TunaHome phone remote") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                thread(name = "TunaHome phone remote request") { handle(service, client) }
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
            // Closed already
        }
        server = null
        port = 0
    }

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun read(input: InputStream): Req? {
        val head = StringBuilder()
        var last4 = 0
        while (head.length < 8 * 1024) {
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
        val n = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY)
        val bytes = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(bytes, got, n - got)
            if (r < 0) break
            got += r
        }
        return Req(first[0], first[1].substringBefore('?'), headers, String(bytes, 0, got, Charsets.UTF_8))
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Bad Request" }
        out.write(("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
    }

    private fun handle(service: HomeButtonService, client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 15_000
                val from: InetAddress = socket.inetAddress
                // The home network only
                if (!(from.isSiteLocalAddress || from.isLoopbackAddress || from.isLinkLocalAddress)) return
                val input = socket.getInputStream().buffered()
                val out = socket.getOutputStream()
                val r = read(input) ?: return
                when {
                    r.method == "GET" && (r.path == "/" || r.path == "/remote") ->
                        send(out, 200, "text/html; charset=utf-8", service.assets.open("phone/remote.html").use { it.readBytes() })
                    r.method == "POST" && r.path == "/api/join" -> join(service, r, out)
                    r.path.startsWith("/api/") -> {
                        if ((r.headers["x-token"] ?: "") !in phones(service)) {
                            return send(out, 403, "application/json", """{"error":"code"}""".toByteArray())
                        }
                        val answer = try {
                            api(service, r)
                        } catch (e: Exception) {
                            JSONObject().put("error", e.message ?: e.toString())
                        }
                        send(out, if (answer.has("error")) 400 else 200, "application/json", answer.toString().toByteArray())
                    }
                    else -> send(out, 404, "text/plain", "Not here".toByteArray())
                }
                out.flush()
            } catch (e: Exception) {
                // The phone went away
            }
        }
    }

    private fun join(context: Context, r: Req, out: OutputStream) {
        val tried = try {
            JSONObject(r.body).optString("code").trim()
        } catch (e: Exception) {
            ""
        }
        synchronized(this) {
            if (tried == code(context)) {
                wrong = 0
                val token = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val all = phones(context).toMutableSet()
                if (all.size >= 10) all.remove(all.first())
                all.add(token)
                Prefs.of(context).edit().putStringSet(PHONES, all).apply()
                return send(out, 200, "application/json", JSONObject().put("token", token).toString().toByteArray())
            }
            wrong++
            // Five wrong guesses: a new code, which only the TV's settings show
            if (wrong >= 5) {
                wrong = 0
                newCode(context)
            }
        }
        send(out, 403, "application/json", """{"error":"code"}""".toByteArray())
    }

    // ------------------------------------------------------------------ what the phone can do

    private val main = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        main.post { block() }
    }

    private fun media(context: Context, keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun api(service: HomeButtonService, r: Req): JSONObject {
        val body = try {
            JSONObject(r.body)
        } catch (e: Exception) {
            JSONObject()
        }
        val ok = JSONObject().put("ok", true)
        when (r.path) {
            "/api/info" -> return JSONObject()
                .put("dpad", Build.VERSION.SDK_INT >= 33)
                .put("tv", Build.MODEL)
                .put("findRemote", FindRemote.available(service))
            "/api/apps" -> {
                val pm = service.packageManager
                val list = JSONArray()
                val seen = HashSet<String>()
                for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                    try {
                        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).forEach {
                            val pkg = it.activityInfo.packageName
                            if (pkg != service.packageName && pkg !in Prefs.hidden(service) && seen.add(pkg)) {
                                list.put(JSONObject().put("pkg", pkg).put("name", it.loadLabel(pm).toString()))
                            }
                        }
                    } catch (e: Exception) {
                        // That kind can't be listed
                    }
                }
                return JSONObject().put("apps", list)
            }
            "/api/open" -> {
                val pkg = body.optString("pkg")
                val pm = service.packageManager
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
                    ?: return JSONObject().put("error", "That app can't be opened")
                // Kids mode and limits still apply: the Home button service checks as it comes up
                onMain { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return ok
            }
            "/api/move" -> {
                val dx = body.optDouble("dx", 0.0).toFloat().coerceIn(-400f, 400f)
                val dy = body.optDouble("dy", 0.0).toFloat().coerceIn(-400f, 400f)
                onMain { service.pointer.moveBy(dx, dy) }
                return ok
            }
            "/api/tap" -> {
                val long = body.optBoolean("long")
                onMain { service.pointer.tapHere(long) }
                return ok
            }
            "/api/scroll" -> {
                val dy = body.optDouble("dy", 0.0).toFloat().coerceIn(-800f, 800f)
                onMain { service.pointer.swipeBy(0f, dy) }
                return ok
            }
            "/api/key" -> {
                val key = body.optString("key")
                onMain {
                    when (key) {
                        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        "home" -> service.showHome()
                        "apps" -> service.startActivity(Intent(service, OpenAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "play" -> media(service, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        "next" -> media(service, KeyEvent.KEYCODE_MEDIA_NEXT)
                        "previous" -> media(service, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        "volup", "voldown", "mute" -> service.getSystemService(AudioManager::class.java).adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            when (key) { "volup" -> AudioManager.ADJUST_RAISE; "voldown" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE },
                            AudioManager.FLAG_SHOW_UI,
                        )
                        "sleep" -> service.sleepTv()
                        "find" -> FindRemote.find(service)
                        "pointer_off" -> service.pointer.off(false)
                        "corner" -> CornerActivity.open(service)
                        "corner_sound" -> Corner.send(service, Corner.SOUND)
                        "corner_full" -> Corner.send(service, Corner.FULL)
                        "corner_close" -> Corner.send(service, Corner.CLOSE)
                        "up", "down", "left", "right", "ok" -> if (Build.VERSION.SDK_INT >= 33) {
                            service.performGlobalAction(when (key) {
                                "up" -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
                                "down" -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                                "left" -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
                                "right" -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                                else -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
                            })
                        }
                    }
                }
                return ok
            }
        }
        return JSONObject().put("error", "Unknown")
    }
}
