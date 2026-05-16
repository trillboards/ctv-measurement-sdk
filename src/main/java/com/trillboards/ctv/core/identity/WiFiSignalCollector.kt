package com.trillboards.ctv.core.identity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address

/**
 * Connected-AP signal collector.
 *
 * NOTE: this file pre-dates Phase 2 of the proximity-identity-graph plan and
 * still emits SHA-256 hashes for connected BSSID/SSID/gatewayIp. The Phase 2
 * privacy invariant (no edge-side MAC hashing) targets `BleBeaconScanner.kt`
 * and `WifiScanCollector.kt` only — those are the high-volume scan-result
 * paths. This collector emits ONE BSSID/SSID/gateway per heartbeat, which is
 * not on the critical path for resolved-device clustering. Migrating it
 * requires updating 3 DeviceAgentService callers in agent-core-lite,
 * android-tv-agent (lite + full), and tablet-agent — out of scope here.
 *
 * Tracked for Phase 3 cleanup once the server-side RotatingPepperService is
 * deployed.
 */
data class WiFiSignalResult(
    val bssidHash: String,
    val ssidHash: String,
    val gatewayIpHash: String?,
    val signalStrengthDbm: Int?
)

object WiFiSignalCollector {

    private const val TAG = "WiFiSignalCollector"

    fun collect(context: Context): WiFiSignalResult? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null

            val connectionInfo = wifiManager.connectionInfo ?: return null
            val bssid = connectionInfo.bssid

            // BSSID can be null, "02:00:00:00:00:00" (unknown), or a valid MAC
            if (bssid.isNullOrBlank() || bssid == "02:00:00:00:00:00") {
                Log.d(TAG, "No valid BSSID available")
                return null
            }

            val ssid = connectionInfo.ssid?.removeSurrounding("\"") ?: ""

            // Legacy edge-side hashing — see file header for migration rationale.
            val bssidHash = CryptoUtils.sha256(bssid)
            val ssidHash = if (ssid.isNotBlank()) CryptoUtils.sha256(ssid) else ""

            val gatewayIpHash = resolveGatewayIp(context, wifiManager)?.let { CryptoUtils.sha256(it) }

            val rssi = connectionInfo.rssi
            val signalDbm = if (rssi != 0 && rssi != -127) rssi else null

            WiFiSignalResult(
                bssidHash = bssidHash,
                ssidHash = ssidHash,
                gatewayIpHash = gatewayIpHash,
                signalStrengthDbm = signalDbm
            ).also {
                Log.d(TAG, "Collected WiFi signals: BSSID=${bssidHash.take(12)}..., signal=${signalDbm}dBm, gateway=${if (gatewayIpHash != null) "set" else "missing"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect WiFi signals: ${e.message}")
            null
        }
    }

    /**
     * Two-path gateway resolution. Legacy [WifiManager.getDhcpInfo] returns
     * `gateway=0` on Android 13+ for many OEM builds even when DHCP succeeded —
     * so we ALSO probe [ConnectivityManager.getLinkProperties], which carries
     * the resolved DHCP server / default-route gateway via the modern Network
     * API. First non-null wins. Returns dotted-quad IPv4 or null.
     */
    private fun resolveGatewayIp(context: Context, wifiManager: WifiManager): String? {
        try {
            val dhcpInfo = wifiManager.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                return intToIp(dhcpInfo.gateway)
            }
        } catch (_: Exception) { /* fall through */ }
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val link = cm.getLinkProperties(activeNetwork) ?: return null
            for (route in link.routes) {
                if (!route.isDefaultRoute) continue
                val gw = route.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) {
                    return gw.hostAddress
                }
            }
            link.dhcpServerAddress?.let { server ->
                if (server is Inet4Address && !server.isAnyLocalAddress) {
                    return server.hostAddress
                }
            }
        } catch (_: Exception) { /* fall through */ }
        return null
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
