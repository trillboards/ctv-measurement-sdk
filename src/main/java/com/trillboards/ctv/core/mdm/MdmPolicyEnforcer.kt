package com.trillboards.ctv.core.mdm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Enforces MDM policies on the device. Listens for policy data from socket events
 * and delegates to existing device capabilities.
 *
 * Supported policy domains:
 * - volume: AudioManager.setStreamVolume()
 * - kiosk: delegates to KioskLockManager interface
 * - power_schedule: AlarmManager for on/off times
 * - display: brightness via Settings.System
 * - app_whitelist: NOT SUPPORTED on lite agent (see note below). Server-side
 *   policy assignments are accepted but evaluated as compliant (no enforcement).
 * - network: WifiManager config
 * - content, geofence, security: report-only (no local enforcement)
 *
 * NOTE on app_whitelist removal: Google Play's User Data policy flags any APK
 * containing PackageManager.getInstalledPackages() regardless of runtime
 * gating, blocking releases of com.trillboards.ctv.tablet. The feature was
 * unused in production (zero `app_whitelist` policy assignments) so the
 * client-side enforcement is removed entirely. If app whitelisting is needed
 * in the future, implement it via DPC.setApplicationsBlocked() (Device Owner)
 * which does not require enumerating installed apps.
 */
class MdmPolicyEnforcer(
    private val context: Context
) {

    companion object {
        private const val TAG = "MdmPolicyEnforcer"
        private const val POWER_ON_ACTION = "com.trillboards.ctv.mdm.POWER_ON"
        private const val POWER_OFF_ACTION = "com.trillboards.ctv.mdm.POWER_OFF"
        private const val POWER_ON_REQUEST_CODE = 9001
        private const val POWER_OFF_REQUEST_CODE = 9002
    }

    data class PolicyComplianceResult(
        val domain: String,
        val compliant: Boolean,
        val details: Map<String, Any?> = emptyMap()
    )

    // Cached policies for evaluateLocally()
    @Volatile
    private var cachedPolicies: JSONObject? = null

    /**
     * Apply all policies from a JSON payload. This is the main entry point for policy enforcement.
     *
     * @param policiesJson Full policies JSON object with domain keys
     */
    fun applyPolicies(policiesJson: JSONObject) {
        cachedPolicies = policiesJson
        Log.i(TAG, "Applying policies with ${policiesJson.length()} domains")

        policiesJson.optJSONObject("volume")?.let { applyVolumePolicy(it) }
        policiesJson.optJSONObject("kiosk")?.let { applyKioskPolicy(it) }
        policiesJson.optJSONObject("power_schedule")?.let { applyPowerSchedulePolicy(it) }
        policiesJson.optJSONObject("display")?.let { applyDisplayPolicy(it) }
        policiesJson.optJSONObject("app_whitelist")?.let { applyAppWhitelistPolicy(it) }
        policiesJson.optJSONObject("network")?.let { applyNetworkPolicy(it) }

        // Report-only domains: content, geofence, security
        // These are stored in cachedPolicies but not locally enforced.
        // The server evaluates compliance from heartbeat data.
        policiesJson.optJSONObject("content")?.let {
            Log.d(TAG, "Content policy received (report-only)")
        }
        policiesJson.optJSONObject("geofence")?.let {
            Log.d(TAG, "Geofence policy received (report-only)")
        }
        policiesJson.optJSONObject("security")?.let {
            Log.d(TAG, "Security policy received (report-only)")
        }
    }

    /**
     * Evaluate local compliance snapshot against cached policies without contacting the server.
     */
    fun evaluateLocally(): List<PolicyComplianceResult> {
        val policies = cachedPolicies ?: return emptyList()
        val results = mutableListOf<PolicyComplianceResult>()

        policies.optJSONObject("volume")?.let {
            results.add(evaluateVolumeCompliance(it))
        }
        policies.optJSONObject("display")?.let {
            results.add(evaluateDisplayCompliance(it))
        }
        policies.optJSONObject("app_whitelist")?.let {
            results.add(evaluateAppWhitelistCompliance(it))
        }
        policies.optJSONObject("security")?.let {
            results.add(evaluateSecurityCompliance(it))
        }
        policies.optJSONObject("network")?.let {
            results.add(evaluateNetworkCompliance(it))
        }

        return results
    }

    // ── Volume ──

    private fun applyVolumePolicy(policy: JSONObject) {
        val level = policy.optInt("level", -1)
        if (level !in 0..100) {
            Log.w(TAG, "Invalid volume level in policy: $level")
            return
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val scaledVolume = (level * maxVolume / 100)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaledVolume, 0)

            val muteEnabled = policy.optBoolean("mute", false)
            if (muteEnabled) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    0
                )
            }

            Log.i(TAG, "Volume policy applied: level=$level, mute=$muteEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply volume policy", e)
        }
    }

    private fun evaluateVolumeCompliance(policy: JSONObject): PolicyComplianceResult {
        val targetLevel = policy.optInt("level", -1)
        if (targetLevel !in 0..100) {
            return PolicyComplianceResult("volume", true, mapOf("reason" to "no_target"))
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentPct = if (maxVolume > 0) (currentVolume * 100 / maxVolume) else 0
        val compliant = Math.abs(currentPct - targetLevel) <= 5 // 5% tolerance

        return PolicyComplianceResult(
            domain = "volume",
            compliant = compliant,
            details = mapOf(
                "target" to targetLevel,
                "current" to currentPct
            )
        )
    }

    // ── Kiosk ──

    private fun applyKioskPolicy(policy: JSONObject) {
        // Kiosk enforcement is delegated to DeviceAgentService via broadcast.
        // We log the intent here; the service picks it up.
        val enabled = policy.optBoolean("enabled", false)
        Log.i(TAG, "Kiosk policy: enabled=$enabled (delegation via broadcast)")

        val intent = Intent("com.trillboards.ctv.mdm.KIOSK_POLICY").apply {
            putExtra("enabled", enabled)
            policy.optString("pin", "").takeIf { it.isNotEmpty() }?.let {
                putExtra("pin", it)
            }
        }
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(context)
                .sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast kiosk policy", e)
        }
    }

    // ── Power Schedule ──

    private fun applyPowerSchedulePolicy(policy: JSONObject) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel existing alarms first
        cancelPowerAlarm(alarmManager, POWER_ON_REQUEST_CODE, POWER_ON_ACTION)
        cancelPowerAlarm(alarmManager, POWER_OFF_REQUEST_CODE, POWER_OFF_ACTION)

        val enabled = policy.optBoolean("enabled", true)
        if (!enabled) {
            Log.i(TAG, "Power schedule disabled")
            return
        }

        // Schedule power on time (e.g., "08:00")
        policy.optString("on_time", "").takeIf { it.contains(":") }?.let { timeStr ->
            scheduleDailyAlarm(alarmManager, timeStr, POWER_ON_REQUEST_CODE, POWER_ON_ACTION)
            Log.i(TAG, "Power ON scheduled at $timeStr")
        }

        // Schedule power off time (e.g., "22:00")
        policy.optString("off_time", "").takeIf { it.contains(":") }?.let { timeStr ->
            scheduleDailyAlarm(alarmManager, timeStr, POWER_OFF_REQUEST_CODE, POWER_OFF_ACTION)
            Log.i(TAG, "Power OFF scheduled at $timeStr")
        }
    }

    private fun scheduleDailyAlarm(
        alarmManager: AlarmManager,
        timeStr: String,
        requestCode: Int,
        action: String
    ) {
        try {
            val parts = timeStr.split(":")
            val hour = parts[0].toInt()
            val minute = if (parts.size > 1) parts[1].toInt() else 0

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // If the time is in the past today, schedule for tomorrow
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(action).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm for $timeStr", e)
        }
    }

    private fun cancelPowerAlarm(
        alarmManager: AlarmManager,
        requestCode: Int,
        action: String
    ) {
        try {
            val intent = Intent(action).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel power alarm", e)
        }
    }

    // ── Display ──

    private fun applyDisplayPolicy(policy: JSONObject) {
        val brightness = policy.optInt("brightness", -1)
        if (brightness !in 0..100) {
            Log.w(TAG, "Invalid brightness in display policy: $brightness")
            return
        }

        try {
            if (Settings.System.canWrite(context)) {
                val scaledBrightness = (brightness * 255 / 100)
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    scaledBrightness
                )
                // Disable auto-brightness if setting manual brightness
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Log.i(TAG, "Display brightness set to $brightness%")
            } else {
                Log.w(TAG, "WRITE_SETTINGS permission not granted, cannot set brightness")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply display policy", e)
        }
    }

    private fun evaluateDisplayCompliance(policy: JSONObject): PolicyComplianceResult {
        val targetBrightness = policy.optInt("brightness", -1)
        if (targetBrightness !in 0..100) {
            return PolicyComplianceResult("display", true, mapOf("reason" to "no_target"))
        }

        return try {
            val currentBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val currentPct = currentBrightness * 100 / 255
            val compliant = Math.abs(currentPct - targetBrightness) <= 5

            PolicyComplianceResult(
                domain = "display",
                compliant = compliant,
                details = mapOf(
                    "target" to targetBrightness,
                    "current" to currentPct
                )
            )
        } catch (e: Exception) {
            PolicyComplianceResult("display", false, mapOf("error" to (e.message ?: "unknown")))
        }
    }

    // ── App Whitelist (no-op on lite agent — see class header) ──

    private fun applyAppWhitelistPolicy(policy: JSONObject) {
        // Intentionally no-op. Server-side `app_whitelist` policy assignments
        // are accepted but never enforced on the lite tablet build because
        // PackageManager.getInstalledPackages() is forbidden by Google Play's
        // User Data policy here. See class header for the full rationale.
        Log.d(TAG, "app_whitelist policy received but not enforced on this build")
    }

    private fun evaluateAppWhitelistCompliance(policy: JSONObject): PolicyComplianceResult {
        return PolicyComplianceResult(
            "app_whitelist",
            true,
            mapOf("reason" to "policy_type_not_supported_on_lite_build")
        )
    }

    // ── Network ──

    @Suppress("DEPRECATION")
    private fun applyNetworkPolicy(policy: JSONObject) {
        val ssid = policy.optString("ssid", "").takeIf { it.isNotEmpty() } ?: return
        val password = policy.optString("password", "")
        val securityType = policy.optString("security", "WPA2")

        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (!wifiManager.isWifiEnabled) {
                Log.w(TAG, "WiFi is disabled, cannot apply network policy")
                return
            }

            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                when (securityType.uppercase()) {
                    "WPA2", "WPA" -> {
                        preSharedKey = "\"$password\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    }
                    "WEP" -> {
                        wepKeys[0] = "\"$password\""
                        wepTxKeyIndex = 0
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    }
                    "OPEN", "NONE" -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                    else -> {
                        preSharedKey = "\"$password\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    }
                }
            }

            val networkId = wifiManager.addNetwork(config)
            if (networkId != -1) {
                wifiManager.enableNetwork(networkId, true)
                wifiManager.reconnect()
                Log.i(TAG, "Network policy applied: connected to $ssid")
            } else {
                Log.w(TAG, "Failed to add network: $ssid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply network policy", e)
        }
    }

    private fun evaluateNetworkCompliance(policy: JSONObject): PolicyComplianceResult {
        val requiredSsid = policy.optString("ssid", "").takeIf { it.isNotEmpty() }
            ?: return PolicyComplianceResult("network", true, mapOf("reason" to "no_required_ssid"))

        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val currentSsid = connectionInfo?.ssid?.replace("\"", "") ?: ""
            val compliant = currentSsid == requiredSsid

            PolicyComplianceResult(
                domain = "network",
                compliant = compliant,
                details = mapOf(
                    "required_ssid" to requiredSsid,
                    "current_ssid" to currentSsid
                )
            )
        } catch (e: Exception) {
            PolicyComplianceResult("network", false, mapOf("error" to (e.message ?: "unknown")))
        }
    }

    // ── Security (report-only) ──

    private fun evaluateSecurityCompliance(policy: JSONObject): PolicyComplianceResult {
        val requireEncryption = policy.optBoolean("require_encryption", false)
        val minSecurityPatch = policy.optString("min_security_patch", "")

        val details = mutableMapOf<String, Any?>()

        if (requireEncryption) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            val encryptionStatus = dpm.storageEncryptionStatus
            val encrypted = encryptionStatus == android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
                encryptionStatus == android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
            details["encryption_required"] = true
            details["encryption_active"] = encrypted
        }

        if (minSecurityPatch.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val currentPatch = Build.VERSION.SECURITY_PATCH
            details["min_security_patch"] = minSecurityPatch
            details["current_security_patch"] = currentPatch
            details["patch_compliant"] = currentPatch >= minSecurityPatch
        }

        val compliant = details.entries
            .filter { it.key.endsWith("_active") || it.key.endsWith("_compliant") }
            .all { it.value == true }

        return PolicyComplianceResult(
            domain = "security",
            compliant = if (details.isEmpty()) true else compliant,
            details = details
        )
    }

    // ── Utility ──

    private fun jsonArrayToStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optString(i, "")
            if (item.isNotEmpty()) {
                list.add(item)
            }
        }
        return list
    }
}
