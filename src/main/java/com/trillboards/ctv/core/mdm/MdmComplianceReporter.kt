package com.trillboards.ctv.core.mdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log

/**
 * Collects device compliance data for piggybacking on heartbeat.
 *
 * Data collected:
 * - OS version (Build.VERSION.RELEASE)
 * - Security patch level (Build.VERSION.SECURITY_PATCH)
 * - Free storage percentage (StatFs)
 * - Battery level (BatteryManager)
 * - Storage encryption status (DevicePolicyManager)
 *
 * NOTE: Installed-app inventory was previously collected here behind a runtime
 * disclosure flag, but Google Play's static bytecode scanner flags ANY APK
 * containing PackageManager.getInstalledPackages() under the User Data policy
 * regardless of runtime gating. The feature was unused (zero production
 * `app_whitelist` policy assignments) and the recurring policy rejection was
 * blocking all tablet releases. The call is removed entirely; the policy type
 * remains in the server-side API surface but is unenforceable on lite agents.
 */
class MdmComplianceReporter(
    private val context: Context
) {

    companion object {
        private const val TAG = "MdmComplianceReporter"
    }

    /**
     * Build a full compliance snapshot. Designed to be piggybacked on heartbeat.
     * All collection is best-effort; individual failures do not prevent other data.
     *
     * @return Map of compliance data keyed by metric name
     */
    fun buildSnapshot(): Map<String, Any?> {
        val snapshot = mutableMapOf<String, Any?>()

        // OS info
        snapshot["os_version"] = Build.VERSION.RELEASE
        snapshot["sdk_int"] = Build.VERSION.SDK_INT
        snapshot["manufacturer"] = Build.MANUFACTURER
        snapshot["model"] = Build.MODEL

        // Security patch level (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            snapshot["security_patch"] = Build.VERSION.SECURITY_PATCH
        }

        // Storage info
        collectStorageInfo(snapshot)

        // Battery info
        collectBatteryInfo(snapshot)

        // Encryption status
        collectEncryptionStatus(snapshot)

        return snapshot
    }

    private fun collectStorageInfo(snapshot: MutableMap<String, Any?>) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val freeBytes = stat.blockSizeLong * stat.availableBlocksLong
            val freePct = if (totalBytes > 0) {
                ((freeBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt()
            } else {
                0
            }

            snapshot["storage_total_mb"] = totalBytes / (1024 * 1024)
            snapshot["storage_free_mb"] = freeBytes / (1024 * 1024)
            snapshot["storage_free_pct"] = freePct
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect storage info", e)
            snapshot["storage_error"] = e.message
        }
    }

    private fun collectBatteryInfo(snapshot: MutableMap<String, Any?>) {
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) {
                    (level * 100 / scale)
                } else {
                    -1
                }

                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val powerSource = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "none"
                }

                snapshot["battery_level"] = batteryPct
                snapshot["battery_charging"] = isCharging
                snapshot["battery_power_source"] = powerSource
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect battery info", e)
            snapshot["battery_error"] = e.message
        }
    }

    private fun collectEncryptionStatus(snapshot: MutableMap<String, Any?>) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val status = dpm.storageEncryptionStatus
            val statusStr = when (status) {
                DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "unsupported"
                DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "inactive"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> "activating"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "active"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "active_default_key"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "active_per_user"
                else -> "unknown"
            }
            snapshot["encryption_status"] = statusStr
            snapshot["encryption_active"] = status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
                status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY ||
                status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect encryption status", e)
            snapshot["encryption_error"] = e.message
        }
    }

}
