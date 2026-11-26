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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Keeps TunaHome up to date from its GitHub releases, the way TunaReceive's updater does.
 *
 * An update is installed only when all of these hold:
 *  - it comes from this project's own releases on GitHub, over HTTPS;
 *  - the file has the exact expected name;
 *  - its SHA-256 matches the checksum GitHub publishes for that file (a release without one is
 *    refused);
 *  - Android itself accepts it, which it does only when it is signed with the same key as the
 *    installed app. That last check is what stops anyone else's APK from replacing TunaHome.
 */
object Updater {
    private const val TAG = "TunaHome"
    private const val REPO = "bigtunafish9943/TunaHome"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val PREF_DISMISSED_CODE = "update_dismissed_code"
    private const val PREF_LAST_CHECK = "update_last_check"
    // Hourly: releases can come several in an evening, and waiting six hours left the TV well behind
    private const val AUTO_CHECK_INTERVAL_MS = 60L * 60 * 1000
    private const val ACTION_INSTALL_RESULT = "com.tunastream.home.UPDATE_INSTALL_RESULT"

    /** A release waiting for the viewer to allow installs from TunaHome. */
    private var pendingAfterPermission: Release? = null
    private var busy = false

    class Release(
        val code: Int,
        val version: String,
        val apkName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
    )

    // ------------------------------------------------------------------ checking

    /** Reads the latest release. Runs on a background thread. Null when none is usable. */
    private fun fetchLatest(): Release? {
        val json = JSONObject(readText(RELEASES_API))
        val tag = json.optString("tag_name", "")
        val code = versionCodeFromTag(tag)
        if (code <= 0) {
            Log.w(TAG, "Updater: release tag not understood: $tag")
            return null
        }
        val version = if (tag.startsWith("v")) tag.substring(1) else tag
        val assets = json.optJSONArray("assets") ?: return null
        val wanted = "TunaHome-$version.apk"
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (wanted != asset.optString("name")) {
                continue
            }
            val digest = asset.optString("digest", "")
            val url = asset.optString("browser_download_url", "")
            if (!digest.startsWith("sha256:") || digest.length != 7 + 64) {
                Log.w(TAG, "Updater: $wanted has no published checksum, so it is not offered")
                return null
            }
            if (!url.startsWith(DOWNLOAD_PREFIX, ignoreCase = true)) {
                Log.w(TAG, "Updater: unexpected download address for $wanted")
                return null
            }
            return Release(
                code, version, wanted, url,
                digest.substring(7).lowercase(Locale.ROOT), asset.optLong("size", 0)
            )
        }
        Log.w(TAG, "Updater: release $tag has no $wanted")
        return null
    }

    /** "v0.1.42" → 42. Releases are numbered by build, and the build number is the version code. */
    private fun versionCodeFromTag(tag: String): Int {
        val dot = tag.lastIndexOf('.')
        if (dot < 0 || dot == tag.length - 1) {
            return -1
        }
        return tag.substring(dot + 1).toIntOrNull() ?: -1
    }

    /**
     * Checks for an update and offers it. When [userAsked] is false this is the quiet check made
     * when the home screen shows: at most every hour, and a version the viewer said "Later"
     * to isn't offered again.
     */
    fun check(activity: Activity, userAsked: Boolean) {
        val prefs = Prefs.of(activity)
        if (busy) {
            return
        }
        if (!userAsked) {
            val last = prefs.getLong(PREF_LAST_CHECK, 0)
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) {
                return
            }
        } else {
            Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        busy = true

        thread(name = "TunaHome update check") {
            var found: Release? = null
            var failed = false
            try {
                found = fetchLatest()
            } catch (e: Exception) {
                Log.w(TAG, "Updater: check failed: $e")
                failed = true
            }
            val release = found
            Handler(Looper.getMainLooper()).post {
                busy = false
                if (activity.isFinishing || activity.isDestroyed) {
                    return@post
                }
                if (!failed) {
                    prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                }
                if (release == null || release.code <= BuildConfig.VERSION_CODE) {
                    if (userAsked) {
                        val text = if (failed) {
                            activity.getString(R.string.update_check_failed)
                        } else {
                            activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
                        }
                        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
                    }
                    return@post
                }
                if (!userAsked && prefs.getInt(PREF_DISMISSED_CODE, 0) == release.code) {
                    return@post
                }
                offer(activity, release)
            }
        }
    }

    private fun offer(activity: Activity, release: Release) {
        QuietDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, release.version))
            .setMessage(activity.getString(R.string.update_available_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(activity, release) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                Prefs.of(activity).edit().putInt(PREF_DISMISSED_CODE, release.code).apply()
            }
            .show()
    }

    /** Call from onResume: carries on an update that was waiting for the install permission. */
    fun resumeIfWaiting(activity: Activity) {
        val release = pendingAfterPermission
        if (release != null && canInstall(activity)) {
            pendingAfterPermission = null
            startUpdate(activity, release)
        }
    }

    // ------------------------------------------------------------------ installing

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun startUpdate(activity: Activity, release: Release) {
        if (!canInstall(activity)) {
            // Android asks once whether TunaHome may install apps; send the viewer to that switch
            pendingAfterPermission = release
            QuietDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_body)
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.packageName)
                            )
                        )
                    } catch (e: Exception) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        } catch (ignored: Exception) {
                            Toast.makeText(
                                activity, R.string.update_permission_manual, Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> pendingAfterPermission = null }
                .show()
            return
        }

        val cancelled = booleanArrayOf(false)
        val progress = QuietDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_downloading_title, release.version))
            .setMessage(activity.getString(R.string.update_downloading, 0))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled[0] = true }
            .show()
        busy = true

        val ui = Handler(Looper.getMainLooper())
        thread(name = "TunaHome update download") {
            var apk: File? = null
            var error: String? = null
            try {
                apk = download(activity, release, cancelled) { percent ->
                    ui.post {
                        progress.setMessage(activity.getString(R.string.update_downloading, percent))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Updater: download failed: $e")
                error = e.message
            }
            val done = apk
            val failure = error
            ui.post {
                busy = false
                if (progress.isShowing && !activity.isFinishing && !activity.isDestroyed) {
                    progress.dismiss()
                }
                if (cancelled[0] || activity.isFinishing || activity.isDestroyed) {
                    return@post
                }
                if (done == null) {
                    QuietDialog.Builder(activity)
                        .setTitle(R.string.update_failed_title)
                        .setMessage(failure ?: activity.getString(R.string.update_check_failed))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@post
                }
                try {
                    install(activity.applicationContext, done)
                } catch (e: Exception) {
                    Log.w(TAG, "Updater: install failed: $e")
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_install_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Downloads the APK and checks it against GitHub's published SHA-256. Null if cancelled. */
    private fun download(
        context: Context,
        release: Release,
        cancelled: BooleanArray,
        onProgress: (Int) -> Unit,
    ): File? {
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException(context.getString(R.string.update_no_folder))
        }
        // Only ever keep the update being downloaded now
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, release.apkName)
        val sha = MessageDigest.getInstance("SHA-256")

        val conn = open(release.apkUrl)
        try {
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val total = if (release.size > 0) release.size else conn.contentLength.toLong()
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastPercent = -1
                    while (!cancelled[0]) {
                        val n = input.read(buf)
                        if (n <= 0) {
                            break
                        }
                        output.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val percent = minOf(100L, got * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
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

        val hex = sha.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        if (hex != release.sha256) {
            out.delete()
            throw IOException(context.getString(R.string.update_checksum_mismatch))
        }
        return out
    }

    /** Hands the checked APK to Android, which asks the viewer to confirm and verifies the signature. */
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("TunaHome.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val result = Intent(context, InstallResultReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The system adds the result to this intent, so it has to be mutable
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, result, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Receives Android's answer to an install: show its confirmation screen, or say what went wrong. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
            )
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.w(TAG, "Updater: no install confirmation screen: $e")
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Updater: install refused ($status): $message")
                val text = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                ) {
                    R.string.update_signature_mismatch
                } else {
                    R.string.update_install_cancelled
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ network

    private fun open(address: String): HttpURLConnection {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TunaHome/" + BuildConfig.VERSION_NAME)
        if (address.startsWith("https://api.github.com/")) {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("GitHub answered $code")
        }
        return conn
    }

    private fun readText(address: String): String {
        val conn = open(address)
        try {
            return conn.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                input.copyTo(bytes)
                String(bytes.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }
}
