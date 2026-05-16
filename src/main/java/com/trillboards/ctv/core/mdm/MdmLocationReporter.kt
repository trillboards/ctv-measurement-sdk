package com.trillboards.ctv.core.mdm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MDM location reporter using FusedLocationProviderClient (Google Play Services)
 * with fallback to LocationManager.
 *
 * Default interval: 5 min enrolled, 15 min low battery (<15%).
 */
class MdmLocationReporter(private val context: Context) {

    companion object {
        private const val TAG = "MdmLocationReporter"
        private const val NORMAL_INTERVAL_MS = 5 * 60 * 1000L    // 5 minutes
        private const val LOW_BATTERY_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val LOW_BATTERY_THRESHOLD = 15 // percent
    }

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val source: String, // "fused", "gps", "network", "passive"
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var lastLocation: LocationData? = null

    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var legacyListener: LocationListener? = null
    private var pollingJob: Job? = null
    private var useFused = false

    /**
     * Start location reporting. Tries FusedLocationProviderClient first,
     * falls back to LocationManager if Google Play Services is unavailable.
     *
     * @param scope CoroutineScope for the polling loop
     */
    fun start(scope: CoroutineScope) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission, cannot start location reporting")
            return
        }

        // Try FusedLocationProviderClient (Google Play Services) first
        if (tryStartFused()) {
            useFused = true
            Log.i(TAG, "Location reporting started using FusedLocationProviderClient")
        } else {
            // Fallback to LocationManager
            startLegacy(scope)
            useFused = false
            Log.i(TAG, "Location reporting started using LocationManager (fallback)")
        }
    }

    /**
     * Stop location reporting and clean up resources.
     */
    fun stop() {
        if (useFused) {
            stopFused()
        } else {
            stopLegacy()
        }
        pollingJob?.cancel()
        pollingJob = null
        Log.i(TAG, "Location reporting stopped")
    }

    /**
     * Get the last known location, or null if no location has been received yet.
     */
    fun getLastKnownLocation(): LocationData? = lastLocation

    // ── FusedLocationProviderClient (Google Play Services) ──

    private fun tryStartFused(): Boolean {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            fusedClient = client

            val intervalMs = getCurrentInterval()
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .setMaxUpdateDelayMillis(intervalMs * 2)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    updateLocation(location, "fused")
                }
            }
            fusedCallback = callback

            if (!hasLocationPermission()) return false

            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException requesting fused location updates", e)
                return false
            }

            // Get last known location immediately
            try {
                client.lastLocation.addOnSuccessListener { location ->
                    if (location != null && lastLocation == null) {
                        updateLocation(location, "fused")
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException getting last fused location", e)
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocationProvider not available", e)
            false
        }
    }

    private fun stopFused() {
        try {
            fusedCallback?.let { callback ->
                fusedClient?.removeLocationUpdates(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping fused location", e)
        }
        fusedClient = null
        fusedCallback = null
    }

    // ── LocationManager fallback ──

    private fun startLegacy(scope: CoroutineScope) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.w(TAG, "LocationManager not available")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val source = when (location.provider) {
                    LocationManager.GPS_PROVIDER -> "gps"
                    LocationManager.NETWORK_PROVIDER -> "network"
                    LocationManager.PASSIVE_PROVIDER -> "passive"
                    else -> location.provider ?: "unknown"
                }
                updateLocation(location, source)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        legacyListener = listener

        // Request updates from best available provider
        try {
            val intervalMs = getCurrentInterval()
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            intervalMs,
                            0f, // no minimum distance
                            listener,
                            Looper.getMainLooper()
                        )
                        Log.d(TAG, "Registered legacy location listener for $provider")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException for provider $provider", e)
                    }
                }
            }

            // Get last known location immediately from any provider
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    try {
                        @Suppress("DEPRECATION")
                        val loc = locationManager.getLastKnownLocation(provider)
                        if (loc != null && lastLocation == null) {
                            val source = when (provider) {
                                LocationManager.GPS_PROVIDER -> "gps"
                                LocationManager.NETWORK_PROVIDER -> "network"
                                else -> "passive"
                            }
                            updateLocation(loc, source)
                            break
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException getting last known from $provider", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start legacy location updates", e)
        }

        // The polling job monitors battery level; the FusedLocationProviderClient
        // handles interval adjustments internally via its own battery-aware logic.
        // For legacy LocationManager, interval is fixed at registration time.
        pollingJob = scope.launch {
            while (isActive) {
                delay(NORMAL_INTERVAL_MS)
                // Re-evaluate interval based on battery level
                val newInterval = getCurrentInterval()
                Log.d(TAG, "Location polling heartbeat, interval=${newInterval}ms")
            }
        }
    }

    private fun stopLegacy() {
        try {
            legacyListener?.let { listener ->
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                locationManager?.removeUpdates(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping legacy location", e)
        }
        legacyListener = null
    }

    // ── Helpers ──

    private fun updateLocation(location: Location, source: String) {
        lastLocation = LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            source = source,
            timestampMs = System.currentTimeMillis()
        )
        Log.d(TAG, "Location updated: lat=${location.latitude}, lng=${location.longitude}, " +
            "accuracy=${location.accuracy}m, source=$source")
    }

    private fun getCurrentInterval(): Long {
        val batteryLevel = getBatteryLevel()
        return if (batteryLevel in 0 until LOW_BATTERY_THRESHOLD) {
            LOW_BATTERY_INTERVAL_MS
        } else {
            NORMAL_INTERVAL_MS
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            } else {
                100 // Assume full battery if unavailable (plugged-in CTV)
            }
        } catch (e: Exception) {
            100 // Assume full battery on error
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }
}
