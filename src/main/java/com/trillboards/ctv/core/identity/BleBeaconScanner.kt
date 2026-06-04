package com.trillboards.ctv.core.identity

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE beacon scanner — discovers Bluetooth Low Energy devices within range (~100m).
 *
 * Scans for a short window (configurable, default 5 seconds) to conserve battery,
 * then returns a snapshot of discovered devices. RSSI (signal strength) is used as
 * a proximity estimator for identity edge confidence.
 *
 * Privacy: raw MAC addresses are sent over TLS to the API and HMAC-hashed there
 * with a KMS-backed daily-rotating pepper. Edge no longer hashes — that broke
 * server-side rotation policy. Device names are dropped (free-text PII risk).
 *
 * Each scan record is also fed through [BleAdvertisementParser] which extracts
 * RPA-stable manufacturer payloads (Apple Continuity sub-types, Microsoft Swift
 * Pair, Google Fast Pair, iBeacon, Eddystone) — these survive MAC rotation and
 * power the resolved-device clustering done in ClickHouse.
 *
 * Permission required: BLUETOOTH_SCAN (Android 12+, declared with neverForUser).
 */
data class BleScanResultData(
    val rawAddress: String,
    val rssi: Int,
    val deviceType: Int,  // BluetoothDevice.DEVICE_TYPE_*
    // RPA-stable parsed manufacturer/Continuity fields. All nullable so older
    // agents emitting only (rawAddress, rssi, deviceType) keep working.
    val manufacturerCompanyId: Int? = null,
    val appleContinuitySubtype: Int? = null,
    val stableManufacturerPayloadHex: String? = null,
    val serviceUuids: List<String>? = null,
    val txPowerDbm: Int? = null,
    val ibeaconUuid: String? = null,
    val ibeaconMajor: Int? = null,
    val ibeaconMinor: Int? = null,
    // Venue-insights PR 7 (E.2): per-device address type from
    // `BluetoothDevice.getAddressType()` (API 31+). Mirrored from agent-core
    // — see that fork for full doc. lite-fork agents on older budget
    // hardware will mostly emit null here (server defaults to 'unknown').
    val addrType: String? = null,
    // Venue-insights PR 7 (E.3): paired-device flag from
    // `BluetoothDevice.getBondState()`. Mirrored from agent-core fork.
    val isPaired: Boolean? = null
)

data class BleScanSnapshot(
    val devices: List<BleScanResultData>,
    val deviceCount: Int,
    val scanDurationMs: Long,
    val scanTimestampMs: Long,
    /**
     * Why this scan produced zero rows, when zero rows is unexpected.
     * Mirrors the agent-core fork (PR #4480). Phase 0 of the
     * redis-stream-scale-fix plan extends the structured-skip channel
     * across both forks so the heartbeat builder's
     * [SkipReasonAggregator] sees the same shape regardless of which
     * fork it lives in.
     */
    val skipReason: String? = null
)

object BleBeaconScanner {

    private const val TAG = "BleBeaconScanner"
    // Per-cycle scan window. Mirror of agent-core fork (PR 7 E.1): bumped
    // 5s → 30s to capture multiple ADV intervals from slower (Samsung ~1-2 Hz)
    // advertisers. The lite fork keeps LOW_POWER so no radio-thrash concern.
    private const val DEFAULT_SCAN_DURATION_MS = 30000L

    // Skip-reason enum — mirror of agent-core's BleBeaconScanner SKIP_*
    // constants. Kept in lockstep so both forks emit identical wire
    // strings to the server-side classifier.
    const val SKIP_PERMISSION_DENIED = "permission_denied"
    const val SKIP_BLE_NOT_SUPPORTED = "ble_not_supported"
    const val SKIP_ADAPTER_UNAVAILABLE = "adapter_unavailable"
    const val SKIP_BLUETOOTH_DISABLED = "bluetooth_disabled"
    const val SKIP_SCANNER_UNAVAILABLE = "scanner_unavailable"
    const val SKIP_ALREADY_IN_PROGRESS = "already_in_progress"
    // Bumped 10s → 60s mirror of agent-core fork.
    private const val MAX_SCAN_DURATION_MS = 60000L
    private const val MAX_DEVICES = 100

    // Venue-insights PR 7 (E.2): BluetoothDevice address-type constants.
    // Mirror of agent-core fork — see that file for full doc.
    private const val BT_ADDR_TYPE_PUBLIC = 0
    private const val BT_ADDR_TYPE_RANDOM = 1
    private const val BT_ADDR_TYPE_ANONYMOUS = 0xff
    private const val BT_ADDR_TYPE_UNKNOWN_SENTINEL = 0xfe

    private val scanInProgress = AtomicBoolean(false)

    /**
     * Scan for BLE devices for a bounded time window.
     *
     * @param context Application context
     * @param scanDurationMs Duration to scan in milliseconds (default 5000, max 10000)
     * @return [BleScanSnapshot] or null if BLE is not available/permitted
     */
    suspend fun scan(
        context: Context,
        scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS
    ): BleScanSnapshot? = withContext(Dispatchers.IO) {
        if (!scanInProgress.compareAndSet(false, true)) {
            // Already-in-progress is the one skip we deliberately do NOT
            // surface upward — it's a benign duplicate-call guard, not a
            // misconfiguration. Caller's previous in-flight scan will
            // emit the snapshot. Mirrors agent-core fork.
            Log.d(TAG, "Scan already in progress — skipping")
            return@withContext null
        }

        try {
            val effectiveDuration = scanDurationMs.coerceIn(1000L, MAX_SCAN_DURATION_MS)
            val skipNow = System.currentTimeMillis()

            if (!hasPermission(context)) {
                Log.w(TAG, "Missing BLUETOOTH_SCAN permission — skipping (no perm grant)")
                return@withContext emptySnapshotWithReason(SKIP_PERMISSION_DENIED, scanStart = skipNow)
            }

            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Log.w(TAG, "BLE not supported on this device — skipping")
                return@withContext emptySnapshotWithReason(SKIP_BLE_NOT_SUPPORTED, scanStart = skipNow)
            }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return@withContext emptySnapshotWithReason(SKIP_ADAPTER_UNAVAILABLE, scanStart = skipNow)

            val adapter: BluetoothAdapter = bluetoothManager.adapter
                ?: run {
                    Log.w(TAG, "BluetoothAdapter not available — skipping")
                    return@withContext emptySnapshotWithReason(SKIP_ADAPTER_UNAVAILABLE, scanStart = skipNow)
                }

            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth disabled — skipping")
                return@withContext emptySnapshotWithReason(SKIP_BLUETOOTH_DISABLED, scanStart = skipNow)
            }

            val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
                ?: run {
                    Log.w(TAG, "BluetoothLeScanner not available — skipping")
                    return@withContext emptySnapshotWithReason(SKIP_SCANNER_UNAVAILABLE, scanStart = skipNow)
                }

            val scanStart = System.currentTimeMillis()
            val discoveredDevices = CopyOnWriteArrayList<ScanResult>()

            // Venue-insights PR 7 (E.1): lite fork stays on LOW_POWER. lite
            // ships to budget hardware where combo BT+WiFi radios dominate
            // and LOW_LATENCY would risk WiFi-throughput regression. agent-
            // core does the hardware-gated LOW_LATENCY decision via
            // DeviceProfile.hasSeparateBtWifiRadios — lite has no equivalent
            // capability matrix, so the safe default applies.
            //
            // setLegacy(false) + setPhy(PHY_LE_ALL_SUPPORTED) opt into BLE 5
            // extended advertising — captures both the legacy 1M PHY and the
            // BLE 5 Coded/2M PHY broadcasters. No-op on older devices; the
            // setters exist since API 26.
            val settingsBuilder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0) // Immediate results
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setLegacy(false)
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
            val settings = settingsBuilder.build()

            Log.i(
                TAG,
                "[BleBeaconScanner] scanMode=LOW_POWER" +
                    ", window=${effectiveDuration}ms" +
                    ", setLegacy=false" +
                    ", phy=PHY_LE_ALL_SUPPORTED" +
                    ", fork=lite"
            )

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.let {
                        if (discoveredDevices.size < MAX_DEVICES * 2) {
                            discoveredDevices.add(it)
                        }
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.let {
                        for (r in it) {
                            if (discoveredDevices.size < MAX_DEVICES * 2) {
                                discoveredDevices.add(r)
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed with error code: $errorCode")
                }
            }

            try {
                scanner.startScan(emptyList<ScanFilter>(), settings, callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException starting BLE scan: ${e.message}")
                return@withContext null
            }

            // Wait for the scan duration
            delay(effectiveDuration)

            // Stop the scan
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {}

            val scanDuration = System.currentTimeMillis() - scanStart

            if (discoveredDevices.isEmpty()) {
                Log.d(TAG, "No BLE devices discovered")
                return@withContext BleScanSnapshot(
                    devices = emptyList(),
                    deviceCount = 0,
                    scanDurationMs = scanDuration,
                    scanTimestampMs = scanStart
                )
            }

            // Deduplicate by device address, keep strongest RSSI per address
            val dedupedByAddress = discoveredDevices
                .filter { it.device?.address != null }
                .groupBy { it.device.address }
                .mapValues { (_, scans) -> scans.maxByOrNull { it.rssi } ?: scans.first() }
                .values
                .sortedByDescending { it.rssi } // Strongest (closest) first
                .take(MAX_DEVICES)

            val devices = dedupedByAddress.map { result ->
                buildResultData(
                    rawAddress = result.device.address,
                    rssi = result.rssi,
                    deviceType = try { result.device.type } catch (_: Exception) { 0 },
                    scanRecordBytes = try { result.scanRecord?.bytes } catch (_: Exception) { null },
                    // Venue-insights PR 7 (E.2): address type from
                    // BluetoothDevice.getAddressType() (API 31+).
                    addrType = readAddressType(result.device),
                    // Venue-insights PR 7 (E.3): bondState from
                    // BluetoothDevice.getBondState().
                    isPaired = readBondState(result.device)
                )
            }

            val snapshot = BleScanSnapshot(
                devices = devices,
                deviceCount = devices.size,
                scanDurationMs = scanDuration,
                scanTimestampMs = scanStart
            )

            Log.d(TAG, "BLE scan complete: ${devices.size} devices in ${scanDuration}ms")
            // Venue-insights PR 7 (E.2/E.3): mirror of agent-core fork —
            // per-cycle addr_type + is_paired distribution log.
            if (devices.isNotEmpty()) {
                val addrTypeHistogram = devices.groupingBy { it.addrType ?: "null" }.eachCount()
                val pairedCount = devices.count { it.isPaired == true }
                Log.i(
                    TAG,
                    "[BleBeaconScanner] capture summary: " +
                        "addr_type=$addrTypeHistogram, " +
                        "is_paired_count=$pairedCount/${devices.size}"
                )
            }
            snapshot
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan failed: ${e.message}")
            null
        } finally {
            scanInProgress.set(false)
        }
    }

    /**
     * Pure-domain builder used by [scan] and exposed for JVM unit tests so the
     * parser-wiring path can be exercised without an Android emulator.
     *
     * Mirror of agent-core fork — [addrType] and [isPaired] are venue-insights
     * PR 7 (E.2/E.3) additions captured at the scan-callback boundary in [scan].
     */
    @JvmStatic
    @JvmOverloads
    fun buildResultData(
        rawAddress: String,
        rssi: Int,
        deviceType: Int,
        scanRecordBytes: ByteArray?,
        addrType: String? = null,
        isPaired: Boolean? = null
    ): BleScanResultData {
        val parsed = BleAdvertisementParser.parse(scanRecordBytes ?: byteArrayOf())
        return BleScanResultData(
            rawAddress = rawAddress,
            rssi = rssi,
            deviceType = deviceType,
            manufacturerCompanyId = parsed.manufacturerCompanyId,
            appleContinuitySubtype = parsed.appleContinuitySubtype,
            stableManufacturerPayloadHex = parsed.stableManufacturerPayload?.let {
                // Reuse parser's lookup-table hex helper (~3x faster than
                // joinToString + "%02x".format on the ~33K call/sec hot path).
                BleAdvertisementParser.bytesToHex(it)
            },
            serviceUuids = parsed.serviceUuids.takeIf { it.isNotEmpty() },
            txPowerDbm = parsed.txPowerDbm,
            ibeaconUuid = parsed.ibeaconUuid,
            ibeaconMajor = parsed.ibeaconMajor,
            ibeaconMinor = parsed.ibeaconMinor,
            addrType = addrType,
            isPaired = isPaired
        )
    }

    /**
     * Venue-insights PR 7 (E.2) — mirror of agent-core fork.
     */
    private fun readAddressType(device: BluetoothDevice?): String? {
        if (device == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            when (val raw = device.addressType) {
                BT_ADDR_TYPE_PUBLIC -> "public"
                BT_ADDR_TYPE_RANDOM -> "random"
                BT_ADDR_TYPE_ANONYMOUS -> "anonymous"
                BT_ADDR_TYPE_UNKNOWN_SENTINEL -> "unknown"
                else -> {
                    Log.d(TAG, "Unknown BluetoothDevice.addressType raw=$raw")
                    "unknown"
                }
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Venue-insights PR 7 (E.3) — mirror of agent-core fork.
     */
    private fun readBondState(device: BluetoothDevice?): Boolean? {
        if (device == null) return null
        return try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build an empty snapshot tagged with a [skipReason]. Mirror of the
     * agent-core fork. Class fix for the silent-zero-rows pattern: every
     * `null` return used to mean "scanner couldn't run — produce no
     * observable signal." Now every recoverable failure ships the reason
     * upward so the heartbeat builder can surface it on the wire.
     */
    private fun emptySnapshotWithReason(reason: String, scanStart: Long): BleScanSnapshot =
        BleScanSnapshot(
            devices = emptyList(),
            deviceCount = 0,
            scanDurationMs = 0,
            scanTimestampMs = scanStart,
            skipReason = reason
        )

    private fun hasPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: BLUETOOTH_SCAN
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Older: ACCESS_FINE_LOCATION covers BLE scanning
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

}
