package com.tunastream.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Find my remote: the SHIELD remote (2019 and later) has a buzzer for when it's lost in the sofa,
 * started from NVIDIA's own remote locator on the TV. Elsewhere, the TV's remote and Bluetooth
 * settings, where most TVs keep their own version.
 */
object FindRemote {
    private val LOCATOR = ComponentName("com.nvidia.remotelocator", "com.nvidia.remotelocator.ShieldRemoteLocatorActivity")

    /** Whether this TV has a remote locator TunaHome can start. */
    fun available(context: Context): Boolean = try {
        context.packageManager.getActivityInfo(LOCATOR, 0).exported
    } catch (e: Exception) {
        false
    }

    fun find(context: Context) {
        if (available(context)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).setComponent(LOCATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, R.string.find_remote_ringing, Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // Not allowed after all: the settings below
            }
        }
        Toast.makeText(context, R.string.find_remote_settings, Toast.LENGTH_LONG).show()
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No settings screen for it
        }
    }
}
