package com.trillboards.ctv.core.identity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device GPS-fix collector for the heartbeat payload.
 *
 * Mirrors the lifecycle/threading/logging shape of the identity collectors
 * ([WifiScanCollector] / [BleBeaconScanner]) but — unlike those stateless
 * `object` collectors — it must hold a live [FusedLocationProviderClient]
 * subscription and cache the latest fix, so it is a per-service instance
 * started in `onCreate()` and stopped in the service teardown hook.
 *
 * Contract:
 *  - [start] subscribes to periodic location updates at ~heartbeat cadence and
 *    grabs the last-known fix immediately so the first heartbeat after startup
 *    can carry a coordinate without waiting a full update interval.
 *  - [getLastFix] returns the most recent cached [Fix] (non-blocking) or null
 *    when no fix is available yet / location permission is not granted.
 *  - [stop] removes the subscription and releases the client.
 *
 * Provider strategy (matches [com.trillboards.ctv.core.mdm.MdmLocationReporter],
 * which already runs FusedLocationProviderClient on the lite agent for MDM):
 *  - Prefer Google Play Services [FusedLocationProviderClient] (source="fused").
 *  - Fall back to the platform [LocationManager] when Play Services is missing
 *    or the fused client throws (source="gps"/"network"/"passive" depending on
 *    which provider delivered the fix).
 *  - When GPS is off the fused provider transparently degrades to network /
 *    coarse positioning; the legacy fallback explicitly registers the
 *    GPS → NETWORK → PASSIVE provider ladder for the same behavior.
 *
 * Degradation:
 *  - No location permission → [getLastFix] returns null, [start] logs once and
 *    no-ops (no crash, no subscription).
 *  - No fix yet → [getLastFix] returns null until the first callback lands.
 *  - Location services fully disabled → no fix arrives; [getLastFix] stays null.
 *
 * Privacy: the raw lat/lon are sent over TLS to the API and land in
 * `earner_screen_devices.geo_lat`/`geo_lon` (operator-cleared device geolocation
 * for venue placement). No PII is derived on-device.
 */
class LocationCollector(private val context: Context) {

    /**
     * A single cached location fix.
     *
     * @param lat decimal-degrees latitude (WGS84)
     * @param lon decimal-degrees longitude (WGS84)
     * @param accuracyM horizontal accuracy radius in meters (68% confidence per
     *   [Location.getAccuracy]); null when the platform doesn't report it
     * @param source provider that produced the fix — "fused" | "gps" |
     *   "network" | "passive"
     * @param timestampMs wall-clock time the fix was cached
     */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float?,
        val source: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var lastFix: Fix? = null

    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var legacyListener: LocationListener? = null
    private var useFused = false

    // True once a provider subscription is live. Gates [start] so repeated
    // calls are cheap no-ops while capturing, but a [start] after a *failed*
    // attempt (permission not yet granted, no provider, both ladders threw)
    // re-attempts — kiosks often receive the ACCESS_FINE/COARSE_LOCATION grant
    // (device-owner auto-grant / operator) AFTER the service has already come
    // up, so a one-shot start would strand GPS at null forever. The watchdog
    // loop re-invokes [start] every cycle to recover that case. AtomicBoolean
    // because start/stop can be driven from the watchdog coroutine while the
    // GMS callback thread mutates [lastFix].
    private val running = AtomicBoolean(false)

    // One-shot guards so a permission-denied / no-Play-Services kiosk doesn't
    // spam the log every heartbeat cycle (mirrors the BleBeaconScanner
    // "log once at warn, then stay quiet" posture for the silent-zero case).
    private val loggedNoPermission = AtomicBoolean(false)
    private val loggedNoFused = AtomicBoolean(false)

    /**
     * Start collecting location fixes. Tries [FusedLocationProviderClient]
     * first and falls back to [LocationManager] when Play Services is
     * unavailable. No-op (logs once) when location permission isn't granted.
     *
     * Idempotent and self-healing: while a subscription is live this is a cheap
     * no-op, so the watchdog loop can call it every cycle. After a *failed*
     * attempt (no permission, no provider, both ladders threw) it leaves the
     * collector un-started and a later call retries — this is how GPS recovers
     * once a deferred runtime permission grant lands. Internally re-[stop]s any
     * partial subscription before re-attempting so a retry never leaks a second
     * client/listener.
     */
    fun start() {
        // Already capturing — nothing to do. Cheap path for the per-cycle
        // watchdog retry.
        if (running.get()) return

        if (!hasLocationPermission()) {
            if (loggedNoPermission.compareAndSet(false, true)) {
                Log.w(TAG, "No location permission granted — GPS capture disabled (getLastFix() will return null)")
            }
            return
        }

        // A previous attempt may have left a half-registered subscription
        // (e.g. fused requestLocationUpdates succeeded but we now re-enter via
        // a retry, or a prior legacy registration partially landed). Tear down
        // before re-attempting so we never stack duplicate callbacks.
        teardownProviders()

        if (tryStartFused()) {
            useFused = true
            running.set(true)
            // The permission grant landed after a prior denial — let the next
            // genuine denial warn again.
            loggedNoPermission.set(false)
            Log.i(TAG, "Location capture started via FusedLocationProviderClient (interval=${UPDATE_INTERVAL_MS}ms)")
        } else if (startLegacy()) {
            useFused = false
            running.set(true)
            loggedNoPermission.set(false)
        } else {
            // Neither provider came up (no Play Services AND no enabled platform
            // provider, or both threw). Leave running=false so the next watchdog
            // cycle retries once a provider becomes available.
            useFused = false
        }
    }

    /**
     * Stop location collection and release the provider subscription.
     * Safe to call multiple times.
     */
    fun stop() {
        running.set(false)
        teardownProviders()
        Log.i(TAG, "Location capture stopped")
    }

    /**
     * Remove whichever provider subscription is live without touching the
     * [running] flag or logging "stopped". Used both by [stop] and by [start]'s
     * retry path so a re-attempt never leaks a duplicate callback/listener.
     */
    private fun teardownProviders() {
        stopFused()
        stopLegacy()
    }

    /**
     * The latest cached fix, or null when no fix has been received yet (or
     * permission is not granted). Non-blocking — returns the volatile cache.
     */
    fun getLastFix(): Fix? = lastFix

    // ── FusedLocationProviderClient (Google Play Services) ──

    private fun tryStartFused(): Boolean {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            fusedClient = client

            // BALANCED_POWER favors network/WiFi positioning when GPS is off and
            // upgrades to satellite when available — the right trade for an
            // always-plugged-in but possibly indoor DOOH kiosk. Heartbeat-cadence
            // interval keeps the radio cost negligible.
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS / 2)
                .setMaxUpdateDelayMillis(UPDATE_INTERVAL_MS * 2)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    updateFix(location, "fused")
                }
            }
            fusedCallback = callback

            // Re-check immediately before the call — permission can be revoked
            // between the start() gate and here on some OEM builds.
            if (!hasLocationPermission()) {
                clearFusedRefs()
                return false
            }

            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException requesting fused location updates: ${e.message}")
                clearFusedRefs()
                return false
            }

            // Seed the cache with the last-known fix so the first heartbeat after
            // startup carries a coordinate without waiting a full interval.
            try {
                client.lastLocation.addOnSuccessListener { location ->
                    if (location != null && lastFix == null) {
                        updateFix(location, "fused")
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException getting last fused location: ${e.message}")
            }

            true
        } catch (e: Throwable) {
            // NoClassDefFoundError / Play-Services-missing ApiException land here;
            // Throwable (not Exception) because the GMS class-not-found path is an
            // Error, mirroring the agent-core BYO-dependency crash class.
            if (loggedNoFused.compareAndSet(false, true)) {
                Log.w(TAG, "FusedLocationProviderClient unavailable, falling back to LocationManager: ${e.message}")
            }
            clearFusedRefs()
            false
        }
    }

    private fun stopFused() {
        try {
            fusedCallback?.let { callback ->
                fusedClient?.removeLocationUpdates(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping fused location: ${e.message}")
        }
        clearFusedRefs()
    }

    // Drop the client/callback refs without de-registering — used on the
    // tryStartFused() failure paths where the callback was never (or only
    // partially) registered, so a subsequent legacy start or retry doesn't
    // carry a stale fused subscription forward.
    private fun clearFusedRefs() {
        fusedClient = null
        fusedCallback = null
    }

    // ── LocationManager fallback ──

    /**
     * @return true when at least one platform provider was successfully
     *   registered (so the collector is considered running); false when no
     *   LocationManager / no enabled provider / every registration threw, in
     *   which case [start] keeps `running=false` and retries next cycle.
     */
    private fun startLegacy(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.w(TAG, "LocationManager unavailable — GPS capture disabled")
            return false
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                updateFix(location, providerToSource(location.provider))
            }

            // Pre-API-30 abstract members — no-op but required to satisfy the
            // interface on older devices.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        var registered = false
        try {
            // GPS → NETWORK → PASSIVE ladder. GPS off transparently leaves the
            // network/passive providers to deliver coarse fixes.
            for (provider in LEGACY_PROVIDERS) {
                if (locationManager.isProviderEnabled(provider)) {
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            UPDATE_INTERVAL_MS,
                            0f, // no minimum distance — kiosks are stationary; cadence is time-based
                            listener,
                            Looper.getMainLooper()
                        )
                        registered = true
                        Log.d(TAG, "Registered legacy location listener for provider=$provider")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException registering provider=$provider: ${e.message}")
                    }
                }
            }

            if (!registered) {
                // No enabled provider accepted a registration — nothing is
                // listening, so don't retain the listener or claim "started".
                Log.w(TAG, "No location provider available for LocationManager fallback — will retry")
                return false
            }
            legacyListener = listener

            // Seed last-known immediately from the best available provider.
            for (provider in LEGACY_PROVIDERS) {
                if (locationManager.isProviderEnabled(provider)) {
                    try {
                        @Suppress("DEPRECATION")
                        val loc = locationManager.getLastKnownLocation(provider)
                        if (loc != null && lastFix == null) {
                            updateFix(loc, providerToSource(provider))
                            break
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException reading last-known from provider=$provider: ${e.message}")
                    }
                }
            }
            Log.i(TAG, "Location capture started via LocationManager fallback (interval=${UPDATE_INTERVAL_MS}ms)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start legacy location updates: ${e.message}")
            // Roll back any partial registration so a retry starts clean.
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            return false
        }
    }

    private fun stopLegacy() {
        try {
            legacyListener?.let { listener ->
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                locationManager?.removeUpdates(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping legacy location: ${e.message}")
        }
        legacyListener = null
    }

    // ── Helpers ──

    private fun updateFix(location: Location, source: String) {
        lastFix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            // Location.hasAccuracy() guards against the rare provider that
            // reports a fix with no accuracy estimate; null flows to the wire.
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            source = source,
            timestampMs = System.currentTimeMillis()
        )
        Log.d(
            TAG,
            "Location fix updated: lat=${location.latitude}, lon=${location.longitude}, " +
                "accuracyM=${if (location.hasAccuracy()) location.accuracy else "n/a"}, source=$source"
        )
    }

    private fun providerToSource(provider: String?): String = when (provider) {
        LocationManager.GPS_PROVIDER -> "gps"
        LocationManager.NETWORK_PROVIDER -> "network"
        LocationManager.PASSIVE_PROVIDER -> "passive"
        else -> provider ?: "unknown"
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "LocationCollector"

        // Heartbeat cadence. The server only persists one geo per heartbeat, so
        // matching the heartbeat interval (~5 min) avoids burning radio on
        // updates that never reach the wire. The fused client coalesces requests
        // internally so this is an upper bound, not a busy-poll.
        private const val UPDATE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        // Build.VERSION_CODES gate not needed — GPS/NETWORK/PASSIVE provider
        // constants exist on every supported API level (minSdk 24/26).
        private val LEGACY_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}
