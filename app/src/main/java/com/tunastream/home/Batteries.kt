package com.tunastream.home

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The battery of the remote and controllers connected over Bluetooth, for the top bar. Android
 * TV has no public way to read it before Android 12, so this asks each connected device the way
 * the TV's own Bluetooth settings do. Devices that don't report a level are left out, as are
 * controllers on a USB adapter.
 */
object Batteries {
    const val LOW = 20

    class Level(val name: String, val percent: Int)

    /** Whether Android lets TunaHome see the TV's Bluetooth devices. */
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun levels(context: Context): List<Level> {
        if (!allowed(context)) return emptyList()
        val adapter = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val devices: Set<BluetoothDevice> = try {
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            return emptyList()
        }
        val out = ArrayList<Level>()
        for (device in devices) {
            try {
                if (call(device, "isConnected") != true) continue
                val level = call(device, "getBatteryLevel") as? Int ?: continue
                if (level !in 0..100) continue
                val name = (if (Build.VERSION.SDK_INT >= 30) device.alias else null) ?: device.name ?: continue
                out.add(Level(name.trim(), level))
            } catch (e: SecurityException) {
                return out
            } catch (e: Exception) {
                // This one can't say
            }
        }
        return out.sortedBy { it.percent }
    }

    private fun call(device: BluetoothDevice, method: String): Any? = try {
        BluetoothDevice::class.java.getMethod(method).invoke(device)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    } catch (e: Exception) {
        null
    }
}
