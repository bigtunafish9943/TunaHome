package com.tunastream.home

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Free, open-source icon packs TunaHome can fetch and install for you, from F-Droid.
 *
 * Phone icon packs usually don't appear in the TV's Play Store, so TunaHome downloads the pack
 * from f-droid.org over HTTPS, checks the file really is the pack it asked for, and hands it to
 * Android, which asks you to confirm before installing anything.
 */
object IconPackStore {
    private const val TAG = "TunaHome"
    private const val API = "https://f-droid.org/api/v1/packages/"
    private const val REPO = "https://f-droid.org/repo/"
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val ACTION_RESULT = "com.tunastream.home.ICON_PACK_INSTALL_RESULT"
    private const val EXTRA_PACK = "pack"

    class Offer(val pkg: String, val name: String, val about: Int)

    val CATALOGUE = listOf(
        Offer("com.donnnno.arcticons", "Arcticons", R.string.pack_arcticons),
        Offer("com.donnnno.arcticons.light", "Arcticons Black", R.string.pack_arcticons_black),
        Offer("com.donnnno.arcticons.daynight", "Arcticons Day & Night", R.string.pack_arcticons_daynight),
        Offer("com.donnnno.arcticons.you", "Arcticons Material You", R.string.pack_arcticons_you),
        Offer("website.leifs.delta.foss", "Delta", R.string.pack_delta),
        Offer("dev.appstract.iconpack", "Appstract", R.string.pack_appstract),
        Offer("org.baitmooth.snow", "Snow", R.string.pack_snow),
        Offer("me.x2gd4.dollphone", "Dollphone", R.string.pack_dollphone),
        Offer("de.moooon.acrylicons", "Mondstern Acrylic", R.string.pack_acrylic),
        Offer("org.xphnx.ameixa", "Ameixa", R.string.pack_ameixa),
        Offer("org.xphnx.ameixamonochrome", "Ameixa Monochrome", R.string.pack_ameixa_mono),
        Offer("com.mookie.circo", "Circo", R.string.pack_circo),
        Offer("me.iacn.mbestyle", "MBE Style", R.string.pack_mbe),
        Offer("com.kn.paper_foss_theme", "Paper", R.string.pack_paper),
        Offer("org.materialos.icons", "MaterialOS", R.string.pack_materialos),
    )

    /** The pack just installed through TunaHome, to switch to once Android has finished. */
    @Volatile
    var waitingFor: String? = null

    /** What happened last time, for the Get icon packs row. */
    @Volatile
    var status: String = ""

    /** A pack waiting for the viewer to allow installs from TunaHome. */
    private var afterPermission: Offer? = null

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Call from onResume: carries on after the viewer allowed installs from TunaHome. */
    fun resumeIfWaiting(activity: Activity) {
        val offer = afterPermission ?: return
        if (canInstall(activity)) {
            afterPermission = null
            get(activity, offer)
        }
    }

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Downloads [offer] from F-Droid and asks Android to install it. */
    fun get(activity: Activity, offer: Offer) {
        if (!canInstall(activity)) {
            afterPermission = offer
            AlertDialog.Builder(activity)
                .setTitle(R.string.pack_permission_title)
                .setMessage(R.string.update_permission_body)
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                        )
                    } catch (e: Exception) {
                        Toast.makeText(activity, R.string.update_permission_manual, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> afterPermission = null }
                .show()
            return
        }

        val cancelled = booleanArrayOf(false)
        val progress = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.pack_downloading, offer.name))
            .setMessage(activity.getString(R.string.bg_copied_mb, 0))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled[0] = true }
            .show()
        val ui = Handler(Looper.getMainLooper())
        val app = activity.applicationContext

        thread(name = "TunaHome icon pack") {
            var failure: String? = null
            var file: File? = null
            try {
                file = download(app, offer.pkg, cancelled) { bytes ->
                    ui.post {
                        if (progress.isShowing) {
                            progress.setMessage(activity.getString(R.string.bg_copied_mb, (bytes / (1024 * 1024)).toInt()))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Icon pack ${offer.pkg}: $e")
                failure = e.message ?: e.toString()
                status = activity.getString(R.string.pack_failed_body, failure)
            }
            val done = file
            ui.post {
                if (progress.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                    progress.dismiss()
                }
                if (cancelled[0]) {
                    return@post
                }
                if (done == null) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.pack_failed_title)
                            .setMessage(activity.getString(R.string.pack_failed_body, failure ?: ""))
                            .setPositiveButton(R.string.close, null)
                            .show()
                    }
                    return@post
                }
                try {
                    waitingFor = offer.pkg
                    install(app, done, offer.pkg)
                } catch (e: Exception) {
                    waitingFor = null
                    Toast.makeText(app, activity.getString(R.string.pack_failed_body, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Finds the version F-Droid recommends, downloads it, and checks it is the right app. */
    private fun download(context: Context, pkg: String, cancelled: BooleanArray, progress: (Long) -> Unit): File? {
        val info = JSONObject(readText(API + pkg))
        val code = info.optLong("suggestedVersionCode", 0)
        if (code <= 0) {
            throw IOException(context.getString(R.string.pack_not_found))
        }
        val dir = File(context.cacheDir, "iconpacks")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "${pkg}_$code.apk")

        val conn = open(REPO + "${pkg}_$code.apk")
        try {
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (!cancelled[0]) {
                        val n = input.read(buf)
                        if (n <= 0) {
                            break
                        }
                        output.write(buf, 0, n)
                        total += n
                        if (total > MAX_BYTES) {
                            throw IOException(context.getString(R.string.pack_too_big))
                        }
                        progress(total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (cancelled[0]) {
            out.delete()
            return null
        }
        // Only ever install the pack that was asked for
        val archive = context.packageManager.getPackageArchiveInfo(out.path, 0)
        if (archive?.packageName != pkg) {
            out.delete()
            throw IOException(context.getString(R.string.pack_wrong_file))
        }
        return out
    }

    private fun install(context: Context, apk: File, pkg: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("pack.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 256 * 1024)
                    session.fsync(output)
                }
            }
            val result = Intent(context, InstallResult::class.java).setAction(ACTION_RESULT)
                .putExtra(EXTRA_PACK, pkg)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
        }
    }

    /** Android's answer: show its "Install this app?" screen, or say what went wrong. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        try {
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Log.w(TAG, "No install screen: $e")
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // Use it straight away, with icons showing, as the viewer was told
                    val pkg = intent.getStringExtra(EXTRA_PACK)
                    if (pkg != null && pkg == waitingFor) {
                        waitingFor = null
                        IconPacks.choose(context, pkg)
                        Look.TILE_STYLE.set(context, Look.TILE_ICON)
                        Prefs.appsChanged = true
                    }
                    IconPackStore.status = context.getString(R.string.pack_status_installed)
                    Toast.makeText(context, R.string.pack_now_using, Toast.LENGTH_LONG).show()
                }
                else -> {
                    waitingFor = null
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Icon pack install refused ($status): $message")
                    IconPackStore.status = context.getString(R.string.pack_status_refused, message ?: status.toString())
                    Toast.makeText(context, R.string.pack_not_installed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun open(address: String): HttpURLConnection {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TunaHome/" + BuildConfig.VERSION_NAME)
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("F-Droid answered $code")
        }
        return conn
    }

    private fun readText(address: String): String {
        val conn = open(address)
        try {
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
