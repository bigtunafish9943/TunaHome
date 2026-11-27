package com.tunastream.home

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Messages from your PC: TunaHome linked to TunaStream, so messages from its home or phone page
 * pop up on this TV over whatever is on, streaming or not, and Goodnight puts the TV to sleep.
 *
 * Linking: TunaHome finds the PC on the home network, asks to be linked, and the PC's owner
 * allows it on TunaStream's home page; TunaHome then holds a token that can only read its
 * messages. TunaStream's certificate is its own (not from a certificate authority), so the one
 * seen when linking is remembered and only that one is trusted afterwards.
 */
object PcLink {
    private const val TAG = "TunaHome"
    private const val URL_KEY = "pc_link_url"
    private const val TOKEN = "pc_link_token"
    private const val PIN = "pc_link_pin"
    private const val NAME = "pc_link_name"
    private const val LAST = "pc_link_last"
    private const val POLL_MS = 10_000L

    fun linked(context: Context): Boolean = !Prefs.of(context).getString(TOKEN, null).isNullOrEmpty()

    fun pcName(context: Context): String = Prefs.of(context).getString(NAME, null) ?: "PC"

    fun unlink(context: Context) {
        Prefs.of(context).edit().remove(URL_KEY).remove(TOKEN).remove(PIN).remove(NAME).remove(LAST).apply()
    }

    // ------------------------------------------------------------------ HTTPS, pinned

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /**
     * A connection that trusts only the certificate [pin] names; with no pin yet, whichever the
     * PC shows, reported through [seen] so it can be remembered.
     */
    private fun open(url: String, pin: String?, seen: ((String) -> Unit)? = null): HttpsURLConnection {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No certificate")
                val hash = sha256(leaf)
                if (pin != null && hash != pin) throw java.security.cert.CertificateException("Not the linked PC")
                seen?.invoke(hash)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trust), null)
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl.socketFactory
            // The PC is reached by its address, which its certificate doesn't name: the pin is the check
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connectTimeout = 5000
            readTimeout = 8000
        }
    }

    private fun read(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
    }

    // ------------------------------------------------------------------ linking

    private class Found(val name: String, val base: String)

    /** PCs running TunaStream on the home network, by their streaming service. */
    private fun discover(context: Context, done: (List<Found>) -> Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = ArrayList<Found>()
        val main = Handler(Looper.getMainLooper())
        var listener: NsdManager.DiscoveryListener? = null
        val finish = Runnable {
            try {
                listener?.let { nsd.stopServiceDiscovery(it) }
            } catch (e: Exception) {
                // Already stopped
            }
            done(synchronized(found) { found.distinctBy { it.base } })
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post(finish)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        if (host.contains(':')) return
                        // The web page is one port above streaming's (47989 → 47990)
                        synchronized(found) { found.add(Found(info.serviceName, "https://$host:${info.port + 1}")) }
                    }
                })
            }
        }
        try {
            nsd.discoverServices("_nvstream._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            main.post(finish)
            return
        }
        main.postDelayed(finish, 5000)
    }

    /** TunaHome settings > Messages from your PC. */
    fun manage(activity: Activity, changed: () -> Unit) {
        if (linked(activity)) {
            QuietDialog.Builder(activity)
                .setTitle(activity.getString(R.string.link_linked_to, pcName(activity)))
                .setMessage(R.string.link_linked_body)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.link_unlink) { _, _ ->
                    unlink(activity)
                    changed()
                }
                .show()
            return
        }
        val looking = Toast.makeText(activity, R.string.link_looking, Toast.LENGTH_LONG)
        looking.show()
        discover(activity) { pcs ->
            looking.cancel()
            if (activity.isFinishing) return@discover
            val labels = pcs.map { it.name } + activity.getString(R.string.link_by_address)
            QuietDialog.Builder(activity)
                .setTitle(R.string.link_pick)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < pcs.size) ask(activity, pcs[which].name, pcs[which].base, changed)
                    else byAddress(activity, changed)
                }
                .show()
        }
    }

    private fun byAddress(activity: Activity, changed: () -> Unit) {
        val box = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.20"
            setSingleLine()
            setTextColor(activity.themeColor(R.attr.tsText))
        }
        val pad = Math.round(Look.dp(activity, 20f))
        val frame = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0) }
        frame.addView(box)
        QuietDialog.Builder(activity)
            .setTitle(R.string.link_address)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = box.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
                if (host.isNotEmpty()) {
                    val base = if (host.contains(':')) "https://$host" else "https://$host:47990"
                    ask(activity, host, base, changed)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        box.requestFocus()
    }

    /** Asks the PC, then waits (up to ten minutes) for its owner to allow it there. */
    private fun ask(activity: Activity, name: String, base: String, changed: () -> Unit) {
        val tvName = try {
            Settings.Global.getString(activity.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        } ?: android.os.Build.MODEL
        var waiting: android.app.AlertDialog? = null
        var stop = false
        thread(name = "TunaHome link") {
            var pin: String? = null
            val result: Int = try {
                val c = open("$base/api/tunahome/request", null) { pin = it }
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("name", tvName).toString().toByteArray()) }
                val r = read(c)
                val request = r.optString("request")
                if (!r.optBoolean("status") || request.isEmpty()) {
                    R.string.link_failed
                } else {
                    activity.runOnUiThread {
                        waiting = QuietDialog.Builder(activity)
                            .setTitle(R.string.link_allow_title)
                            .setMessage(activity.getString(R.string.link_allow_body, tvName))
                            .setNegativeButton(R.string.cancel) { _, _ -> stop = true }
                            .setOnCancelListener { stop = true }
                            .show()
                    }
                    var outcome = R.string.link_timed_out
                    val until = System.currentTimeMillis() + 10 * 60_000L
                    while (!stop && System.currentTimeMillis() < until) {
                        Thread.sleep(2000)
                        val claim = try {
                            read(open("$base/api/tunahome/claim?request=$request", pin))
                        } catch (e: Exception) {
                            continue
                        }
                        if (!claim.optBoolean("status")) {
                            outcome = R.string.link_turned_down
                            break
                        }
                        val token = claim.optString("token")
                        if (token.isNotEmpty()) {
                            Prefs.of(activity).edit().putString(URL_KEY, base).putString(TOKEN, token).putString(PIN, pin)
                                .putString(NAME, name).remove(LAST).apply()
                            outcome = R.string.link_done
                            break
                        }
                    }
                    if (stop) 0 else outcome
                }
            } catch (e: Exception) {
                Log.w(TAG, "Linking to $base: $e")
                R.string.link_failed
            }
            activity.runOnUiThread {
                waiting?.dismiss()
                if (result != 0) Toast.makeText(activity, activity.getString(result, name), Toast.LENGTH_LONG).show()
                changed()
                HomeButtonService.running?.startPcLink()
            }
        }
    }

    // ------------------------------------------------------------------ messages (in the Home button service)

    class Poller(private val service: HomeButtonService) {
        private val main = Handler(Looper.getMainLooper())
        private var running = false
        private var busy = false

        private val tick: Runnable = object : Runnable {
            override fun run() {
                if (!running) return
                poll()
                main.postDelayed(this, POLL_MS)
            }
        }

        fun start() {
            if (running || !linked(service)) return
            running = true
            main.post(tick)
        }

        fun stop() {
            running = false
            main.removeCallbacks(tick)
        }

        private fun poll() {
            if (busy) return
            val prefs = Prefs.of(service)
            val base = prefs.getString(URL_KEY, null) ?: return stop()
            val token = prefs.getString(TOKEN, null) ?: return stop()
            val pin = prefs.getString(PIN, null)
            val last = prefs.getLong(LAST, -1)
            busy = true
            thread(name = "TunaHome messages") {
                try {
                    val c = open("$base/api/tv-inbox?after=${last.coerceAtLeast(0)}", pin)
                    c.setRequestProperty("Authorization", "Bearer $token")
                    if (c.responseCode == 401) {
                        // The PC took the link back (Access tokens): forget it
                        unlink(service)
                        main.post { stop() }
                        return@thread
                    }
                    val r = read(c)
                    val now = r.optLong("now")
                    var newest = maxOf(last, r.optLong("newest"))
                    val messages = r.optJSONArray("messages")
                    // The first look after linking only learns where things are
                    if (last >= 0 && messages != null) {
                        for (i in 0 until messages.length()) {
                            val m = messages.getJSONObject(i)
                            newest = maxOf(newest, m.optLong("id"))
                            if (now - m.optLong("at") > 300) continue
                            val text = m.optString("text")
                            val goodnight = m.optString("kind") == "goodnight"
                            main.post { show(if (goodnight) service.getString(R.string.link_goodnight) else text, goodnight) }
                        }
                    }
                    prefs.edit().putLong(LAST, newest).apply()
                } catch (e: Exception) {
                    // The PC is asleep or off: tried again shortly
                } finally {
                    busy = false
                }
            }
        }

        /** A banner at the top of the screen over whatever is on, with a chime. */
        private fun show(text: String, goodnight: Boolean) {
            // A stream shows its own messages over the game
            if (!service.streamingNow() || goodnight) {
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dp = service.resources.displayMetrics.density
                val banner = TextView(service).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    gravity = Gravity.CENTER
                    val pad = (18 * dp).toInt()
                    setPadding(pad * 2, pad, pad * 2, pad)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.argb(225, 12, 22, 38))
                        cornerRadius = 18 * dp
                        setStroke((2 * dp).toInt(), Color.argb(255, 63, 182, 232))
                    }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (36 * dp).toInt()
                }
                try {
                    wm.addView(banner, lp)
                    banner.alpha = 0f
                    banner.animate().alpha(1f).setDuration(300).start()
                    main.postDelayed({
                        banner.animate().alpha(0f).setDuration(500).withEndAction {
                            try {
                                wm.removeView(banner)
                            } catch (e: Exception) {
                                // Gone already
                            }
                        }.start()
                    }, 9000)
                } catch (e: Exception) {
                    Toast.makeText(service, text, Toast.LENGTH_LONG).show()
                }
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
                    main.postDelayed({ tone.release() }, 600)
                } catch (e: Exception) {
                    // No chime
                }
            }
            if (goodnight) {
                // A few seconds to read it, then the TV sleeps
                main.postDelayed({ service.sleepTv() }, 5000)
            }
        }
    }
}
