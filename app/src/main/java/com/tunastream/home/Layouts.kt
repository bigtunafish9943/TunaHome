package com.tunastream.home

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saved layouts: everything about how TunaHome looks and is arranged (settings, sections,
 * favourites, shortcuts, your pictures and font), kept under a name. Switch between them like
 * profiles, or export one to the TV's Downloads folder to keep or copy to another TV.
 */
object Layouts {
    private const val PREFS_FILE = "prefs.json"
    /** Settings that belong to this TV and this moment, not to a layout. */
    private val NOT_SAVED = setOf(
        "changelog_seen_code", "update_last_check", "update_dismissed_code", "last_opened",
        // The TV's own: the same whichever layout or person is in
        "welcomed", "auto_backup_at", "replace_stock", "wake_app",
    )
    private val FILES = listOf("wallpaper.jpg", "fonts/custom.ttf")
    private const val TILES = "tiles"

    private fun dir(context: Context) = File(context.filesDir, "layouts").apply { mkdirs() }

    fun saved(context: Context): List<String> =
        dir(context).listFiles()?.filter { it.name.endsWith(".zip") }?.map { it.name.removeSuffix(".zip") }
            ?.sortedBy { it.lowercase() } ?: emptyList()

    private fun fileFor(context: Context, name: String) =
        File(dir(context), name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "Layout" } + ".zip")

    // ------------------------------------------------------------------ saving

    fun save(context: Context, name: String) {
        FileOutputStream(fileFor(context, name)).use { write(context, it) }
    }

    /** The current layout, into [file] (a person's setup, for Who's watching). */
    fun saveTo(context: Context, file: File) {
        FileOutputStream(file).use { write(context, it) }
    }

    /** A layout with nothing in it: loading it puts every setting back as it comes. */
    fun saveEmpty(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(PREFS_FILE))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
    }

    fun delete(context: Context, name: String) {
        fileFor(context, name).delete()
    }

    /** Everything that makes up the layout, as a zip. */
    private fun write(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PREFS_FILE))
            zip.write(prefsJson(context).toString().toByteArray())
            zip.closeEntry()
            val base = context.filesDir
            val files = FILES.map { File(base, it) } + (File(base, TILES).listFiles()?.toList() ?: emptyList())
            for (file in files.filter { it.isFile }) {
                zip.putNextEntry(ZipEntry("files/" + file.relativeTo(base).path))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun prefsJson(context: Context): JSONObject {
        val json = JSONObject()
        for ((key, value) in Prefs.of(context).all) {
            if (key in NOT_SAVED || value == null) continue
            json.put(key, JSONObject().apply {
                when (value) {
                    is Boolean -> put("t", "b").put("v", value)
                    is Int -> put("t", "i").put("v", value)
                    is Long -> put("t", "l").put("v", value)
                    is Float -> put("t", "f").put("v", value.toDouble())
                    is String -> put("t", "s").put("v", value)
                    is Set<*> -> put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                }
            })
        }
        return json
    }

    // ------------------------------------------------------------------ loading

    fun load(context: Context, name: String) {
        FileInputStream(fileFor(context, name)).use { read(context, it) }
    }

    /** Replaces the current layout with the one in [input]. Throws if it isn't a TunaHome layout. */
    fun read(context: Context, input: InputStream) {
        var prefs: JSONObject? = null
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val bytes = zip.readBytes()
                when {
                    entry.name == PREFS_FILE -> prefs = JSONObject(String(bytes))
                    entry.name.startsWith("files/") && !entry.name.contains("..") -> files[entry.name.removePrefix("files/")] = bytes
                }
                entry = zip.nextEntry
            }
        }
        val json = prefs ?: throw IllegalArgumentException("not a TunaHome layout")

        val edit = Prefs.of(context).edit()
        // Keep this TV's own entries; replace everything else
        val keep = Prefs.of(context).all.filterKeys { it in NOT_SAVED }
        edit.clear()
        for ((k, v) in keep) {
            when (v) {
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is String -> edit.putString(k, v)
                is Boolean -> edit.putBoolean(k, v)
            }
        }
        for (key in json.keys()) {
            val o = json.getJSONObject(key)
            when (o.getString("t")) {
                "b" -> edit.putBoolean(key, o.getBoolean("v"))
                "i" -> edit.putInt(key, o.getInt("v"))
                "l" -> edit.putLong(key, o.getLong("v"))
                "f" -> edit.putFloat(key, o.getDouble("v").toFloat())
                "s" -> edit.putString(key, o.getString("v"))
                "set" -> edit.putStringSet(key, o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        edit.commit()

        val base = context.filesDir
        FILES.forEach { File(base, it).delete() }
        File(base, TILES).listFiles()?.forEach { it.delete() }
        for ((path, bytes) in files) {
            val target = File(base, path)
            if (!target.canonicalPath.startsWith(base.canonicalPath)) continue
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    // ------------------------------------------------------------------ the TV's Downloads folder

    /** Writes the current layout to Downloads as "TunaHome <name> <date>.zip". Returns the file name. */
    fun export(context: Context, name: String): String = toDownloads(context, name) { write(context, it) }

    /** Copies a saved layout (not the current one) to Downloads. */
    fun exportSaved(context: Context, name: String): String =
        toDownloads(context, name) { out -> FileInputStream(fileFor(context, name)).use { it.copyTo(out) } }

    private fun toDownloads(context: Context, name: String, write: (OutputStream) -> Unit): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val fileName = "TunaHome $name $stamp.zip".replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Downloads isn't available")
            context.contentResolver.openOutputStream(uri)?.use { write(it) }
                ?: throw IllegalStateException("Downloads isn't available")
        } else {
            @Suppress("DEPRECATION")
            val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            folder.mkdirs()
            FileOutputStream(File(folder, fileName)).use { write(it) }
        }
        return fileName
    }

    /** A copy of the setup in Downloads once a day, the last three kept, for a reinstall or a new TV. */
    val AUTO_BACKUP = Look.Switch("layouts_auto_backup", true)
    private const val AUTO_NAME = "Automatic backup"
    private const val AUTO_KEEP = 3

    fun autoBackupIfDue(context: Context) {
        if (!AUTO_BACKUP.get(context)) return
        val prefs = Prefs.of(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("auto_backup_at", 0) < 24L * 60 * 60 * 1000) return
        prefs.edit().putLong("auto_backup_at", now).apply()
        val app = context.applicationContext
        kotlin.concurrent.thread(name = "TunaHome backup") {
            try {
                export(app, AUTO_NAME)
                prune(app)
            } catch (e: Exception) {
                // Tried again tomorrow
            }
        }
    }

    /** Removes all but the newest automatic backups (only TunaHome's own files are seen). */
    private fun prune(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val old = ArrayList<Uri>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'TunaHome $AUTO_NAME %.zip'", null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext()) {
                if (++n > AUTO_KEEP) {
                    old.add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
                }
            }
        }
        old.forEach { context.contentResolver.delete(it, null, null) }
    }

    /** Layouts this TunaHome exported to Downloads, newest first (name, address). */
    fun exported(context: Context): List<Pair<String, Uri>> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val found = ArrayList<Pair<String, Uri>>()
        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'TunaHome %.zip'", null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    found.add(c.getString(1) to Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
                }
            }
        } catch (e: Exception) {
            // Nothing we can list
        }
        return found
    }

    /** Starts TunaHome afresh so every screen and setting picks up the layout just loaded. */
    fun restart(activity: Activity) {
        val intent = Intent(activity, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
