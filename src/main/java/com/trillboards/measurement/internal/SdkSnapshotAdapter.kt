package com.trillboards.measurement.internal

import com.trillboards.ctv.core.identity.BleScanResultData
import com.trillboards.ctv.core.identity.BleScanSnapshot
import com.trillboards.ctv.core.identity.WifiEnvironmentSnapshot
import com.trillboards.measurement.BleDevice
import com.trillboards.measurement.MeasurementSnapshot
import com.trillboards.measurement.WifiEnvironment

/**
 * Adapter that maps agent-core-lite scanner output into the SDK's public
 * [MeasurementSnapshot] / [BleDevice] / [WifiEnvironment] shapes.
 *
 * Phase 2 of the L9 plan deletes the SDK's duplicated scanner files and
 * consolidates on `agent-core-lite`'s production-hardened scanners. The
 * public SDK types stay because they are the WebView bridge's wire shape —
 * partners' creative code reads
 * `window.TrillboardsMeasurement.getSnapshot()` and parses the JSON; we
 * cannot break that field shape without breaking every shipped ad creative.
 *
 * This adapter is the one place that bridges agent-core-lite's
 * `BleScanResultData` → SDK's `BleDevice` and similar.
 */
internal object SdkSnapshotAdapter {

    /**
     * Build a [MeasurementSnapshot] from agent-core-lite scanner results.
     *
     * Both inputs are nullable — scanners that failed or are disabled
     * contribute empty fields. A snapshot with no BLE + no WiFi is still
     * valid; the WebView bridge surfaces `deviceCount=0` so creatives can
     * make a defensive decision.
     */
    fun fromAgentCore(
        bleSnapshot: BleScanSnapshot?,
        wifiEnvironment: WifiEnvironmentSnapshot?,
    ): MeasurementSnapshot {
        val devices = bleSnapshot?.devices?.map { it.toBleDevice() } ?: emptyList()
        return MeasurementSnapshot(
            bleDevices = devices,
            bleDeviceCount = bleSnapshot?.deviceCount ?: 0,
            scanDurationMs = bleSnapshot?.scanDurationMs ?: 0L,
            scanTimestampMs = bleSnapshot?.scanTimestampMs ?: System.currentTimeMillis(),
            wifiEnvironment = wifiEnvironment?.toWifiEnvironment(),
            csiMeasurement = null,
        )
    }

    /**
     * Map agent-core-lite's [BleScanResultData] → SDK's [BleDevice].
     * Field-for-field copy — agent-core-lite uses the same RPA-stable
     * parser output names.
     */
    private fun BleScanResultData.toBleDevice(): BleDevice = BleDevice(
        rawAddress = rawAddress,
        rssi = rssi,
        deviceType = deviceType,
        manufacturerCompanyId = manufacturerCompanyId,
        appleContinuitySubtype = appleContinuitySubtype,
        stableManufacturerPayloadHex = stableManufacturerPayloadHex,
        serviceUuids = serviceUuids,
        txPowerDbm = txPowerDbm,
        ibeaconUuid = ibeaconUuid,
        ibeaconMajor = ibeaconMajor,
        ibeaconMinor = ibeaconMinor,
    )

    /**
     * Map agent-core-lite's [WifiEnvironmentSnapshot] → SDK's [WifiEnvironment].
     * The SDK shape has fewer fields (no scanTimestampMs, no median signal,
     * no spread) because the WebView bridge consumer only renders the basic
     * five. agent-core-lite uses the wider shape internally for heartbeat
     * payload + server-side embedding composition.
     */
    private fun WifiEnvironmentSnapshot.toWifiEnvironment(): WifiEnvironment {
        val band = frequencyBand.ifBlank { "unknown" }
        return WifiEnvironment(
            networkCount = networkCount,
            signalStrengthDbm = connectedSignalDbm,
            frequencyMhz = connectedFrequencyMhz,
            channelWidthMhz = connectedChannelWidthMhz,
            linkSpeedMbps = connectedLinkSpeedMbps,
            frequencyBand = band,
            channelCongestion = channelCongestionRatio,
            rssiVariance = rssiVariance,
        )
    }
}
