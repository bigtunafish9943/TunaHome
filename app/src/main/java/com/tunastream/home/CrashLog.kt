package com.tunastream.home

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a note of the last time TunaHome stopped with an error, so it can be read in Settings
 * (Updates, Last problem) and passed on, instead of needing a computer to read the TV's logs.
 * Nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE = "last_problem.txt"
    private const val TIMES = "problem_times.txt"
    private var installed = false

    fun install(context: Context) {
        if (installed) {
            return
        }
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                // When, for the safety net below (the last three times)
                val times = File(app.filesDir, TIMES)
                val kept = (if (times.exists()) times.readLines() else emptyList()).takeLast(2) +
                    System.currentTimeMillis().toString()
                times.writeText(kept.joinToString("\n"))
                File(app.filesDir, FILE).writeText(
                    "TunaHome ${BuildConfig.VERSION_NAME} on ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                        "$time, thread ${thread.name}\n\n$trace"
                )
            } catch (e: Throwable) {
                // Nothing more to do: let Android handle it as usual
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Whether TunaHome stopped with an error twice in the last three minutes. The Home button
     * service then lets the TV's own home screen open instead, so a TunaHome that keeps failing
     * can never leave the TV without a home screen.
     */
    fun failingLately(context: Context): Boolean = try {
        val file = File(context.filesDir, TIMES)
        val now = System.currentTimeMillis()
        file.exists() && file.readLines().mapNotNull { it.trim().toLongOrNull() }.count { now - it in 0..180_000 } >= 2
    } catch (e: Exception) {
        false
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE)
        return if (file.exists()) file.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
