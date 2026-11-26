package com.tunastream.home

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.InputDevice
import java.io.File
import java.util.Locale

/**
 * Report a problem (on the phone page): what TunaHome sees on this TV right now, to copy into a
 * message. Its version, the TV, the controllers and USB devices, the Home button setting, recent
 * crashes and TunaHome's own log. Android only lets an app read its own log.
 */
object Report {
    private const val MAX_LOG_CHARS = 400_000

    fun text(context: Context): String {
        val out = StringBuilder()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        out.append("TunaHome ").append(version).append('\n')
        out.append("TV: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        out.append("Made: ").append(java.util.Date()).append("\n")
        out.append("Home button setting: ").append(if (HomeButtonService.running != null) "on" else "off").append('\n')
        out.append("Open apps lately: ").append(OpenApps.list(context).joinToString(", ").ifEmpty { "none" }).append('\n')

        // Watch Next as the TV gives it to TunaHome: which apps' shows it can read
        try {
            val shows = WatchNext.load(context)
            val byApp = shows.groupingBy { it.appPkg }.eachCount()
            out.append("Watch Next: ").append(shows.size).append(" shows")
            if (byApp.isNotEmpty()) out.append(" (").append(byApp.entries.joinToString(", ") { "${it.key} ${it.value}" }).append(')')
            WatchNext.problem?.let { out.append(", problem: ").append(it) }
            out.append('\n')
        } catch (e: Exception) {
            out.append("Watch Next: couldn't read: ").append(e).append('\n')
        }

        out.append("\nControllers and input devices:\n")
        try {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.isVirtual) continue
                val pad = d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                out.append("  ").append(if (pad) "[controller] " else "").append(d.name)
                    .append(String.format(Locale.ROOT, " (%04x:%04x, sources 0x%x)", d.vendorId, d.productId, d.sources)).append('\n')
            }
        } catch (e: Exception) {
            out.append("  (couldn't list: ").append(e).append(")\n")
        }
        try {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager?
            usb?.deviceList?.values?.forEach { d ->
                out.append(String.format(Locale.ROOT, "  USB %04x:%04x %s", d.vendorId, d.productId, d.productName ?: ""))
                for (i in 0 until d.interfaceCount) {
                    val f = d.getInterface(i)
                    out.append(String.format(Locale.ROOT, ", if%d class %d/%d/%d", f.id, f.interfaceClass, f.interfaceSubclass, f.interfaceProtocol))
                }
                out.append('\n')
            }
        } catch (e: Exception) {
            out.append("  (couldn't list USB: ").append(e).append(")\n")
        }

        out.append("\nRecent problems:\n")
        try {
            val crash = File(context.filesDir, "last_problem.txt")
            out.append(if (crash.isFile) crash.readText().take(20_000) else "  none\n")
        } catch (e: Exception) {
            out.append("  (couldn't read)\n")
        }

        out.append("\nLog:\n")
        val log = StringBuilder()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "3000", "--pid", android.os.Process.myPid().toString()))
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { log.append(it).append('\n') } }
            p.destroy()
        } catch (e: Exception) {
            log.append("(couldn't read the log: ").append(e).append(")\n")
        }
        if (log.length > MAX_LOG_CHARS) {
            log.delete(0, log.length - MAX_LOG_CHARS)
        }
        out.append(log)
        return out.toString()
    }
}
