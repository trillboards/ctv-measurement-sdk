package com.trillboards.measurement.internal

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralized permission and capability checks for BLE scanning.
 *
 * Encapsulates API-level branching so callers do not need to repeat
 * version checks.
 */
internal object PermissionChecker {

    /**
     * Whether the app has the required runtime permission for BLE scanning.
     *
     * - Android 12+ (API 31): checks [Manifest.permission.BLUETOOTH_SCAN]
     * - Android 11 and below: checks [Manifest.permission.ACCESS_FINE_LOCATION]
     */
    fun hasBlePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Whether the device hardware supports Bluetooth Low Energy.
     */
    fun isBleSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    /**
     * Whether Bluetooth is currently enabled on the device.
     *
     * Returns false if the [BluetoothAdapter] is unavailable (e.g. on
     * an emulator without BT support).
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        return manager.adapter?.isEnabled == true
    }
}
