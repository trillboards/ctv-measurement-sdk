package com.trillboards.measurement.internal

import com.trillboards.measurement.BleDevice
import com.trillboards.measurement.CsiMeasurement
import com.trillboards.measurement.MeasurementSnapshot
import com.trillboards.measurement.WifiEnvironment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes [MeasurementSnapshot], [BleDevice], and [CsiMeasurement] to JSON
 * strings for the WebView JavaScript bridge.
 *
 * Uses [org.json.JSONObject] and [org.json.JSONArray] from the Android
 * SDK — no external JSON dependencies required.
 */
internal object BridgePayloadSerializer {

    /**
     * Convert a Float to Double safely for JSON serialization.
     * NaN and Infinity are replaced with 0.0 to avoid producing
     * invalid JSON ("NaN"/"Infinity" are not valid JSON numbers).
     */
    private fun Float.safeDouble(): Double =
        if (this.isFinite()) this.toDouble() else 0.0

    /**
     * Serialize a [MeasurementSnapshot] to a JSON string.
     *
     * Output structure (consumed by @trillboards/ctv-measurement bridge.ts:parseBleSnapshot):
     * ```json
     * {
     *   "devices": [ { "rawAddress": "...", "rssi": -55, "manufacturerCompanyId": 76, ... }, ... ],
     *   "deviceCount": 5,
     *   "scanDurationMs": 5023,
     *   "scanTimestampMs": 1712345678000,
     *   "csiMeasurement": { "nodeId": 1, "occupantCount": 3, ... }
     * }
     * ```
     *
     * The wire-format field names (`devices`, `deviceCount`) intentionally
     * differ from the Kotlin data class field names (`bleDevices`, `bleDeviceCount`)
     * to match the JS SDK consumer's BleSnapshot type. Renaming either side
     * silently breaks the bridge end-to-end.
     *
     * `csiMeasurement` is omitted entirely when null (no ESP32 hardware present),
     * matching the JS SDK's optional `csiMeasurement?: CsiMeasurement` field.
     */
    fun toJson(snapshot: MeasurementSnapshot): String {
        val obj = JSONObject()
        val devicesArray = JSONArray()
        for (device in snapshot.bleDevices) {
            devicesArray.put(toJson(device))
        }
        obj.put("devices", devicesArray)
        obj.put("deviceCount", snapshot.bleDeviceCount)
        obj.put("scanDurationMs", snapshot.scanDurationMs)
        obj.put("scanTimestampMs", snapshot.scanTimestampMs)
        snapshot.wifiEnvironment?.let { obj.put("wifiEnvironment", toJson(it)) }
        snapshot.csiMeasurement?.let { obj.put("csiMeasurement", toJson(it)) }
        return obj.toString()
    }

    /**
     * Serialize a [WifiEnvironment] to a [JSONObject].
     *
     * Wire-format field names match the JS SDK's WifiEnvironmentSnapshot type.
     */
    fun toJson(wifi: WifiEnvironment): JSONObject {
        val obj = JSONObject()
        obj.put("networkCount", wifi.networkCount)
        obj.put("signalStrengthDbm", wifi.signalStrengthDbm)
        obj.put("frequencyMhz", wifi.frequencyMhz)
        obj.put("channelWidthMhz", wifi.channelWidthMhz ?: JSONObject.NULL)
        obj.put("linkSpeedMbps", wifi.linkSpeedMbps ?: JSONObject.NULL)
        obj.put("frequencyBand", wifi.frequencyBand)
        obj.put("channelCongestion", wifi.channelCongestion.safeDouble())
        obj.put("rssiVariance", wifi.rssiVariance.safeDouble())
        return obj
    }

    /**
     * Serialize a [BleDevice] to a [JSONObject].
     *
     * Raw MAC + RPA-stable parsed manufacturer fields are emitted; the API
     * server applies the daily KMS-backed pepper.
     */
    fun toJson(device: BleDevice): JSONObject {
        val obj = JSONObject()
        obj.put("rawAddress", device.rawAddress)
        obj.put("rssi", device.rssi)
        obj.put("deviceType", device.deviceType)
        device.manufacturerCompanyId?.let { obj.put("manufacturerCompanyId", it) }
        device.appleContinuitySubtype?.let { obj.put("appleContinuitySubtype", it) }
        device.stableManufacturerPayloadHex?.let { obj.put("stableManufacturerPayloadHex", it) }
        device.serviceUuids?.let { obj.put("serviceUuids", JSONArray(it)) }
        device.txPowerDbm?.let { obj.put("txPowerDbm", it) }
        device.ibeaconUuid?.let { obj.put("ibeaconUuid", it) }
        device.ibeaconMajor?.let { obj.put("ibeaconMajor", it) }
        device.ibeaconMinor?.let { obj.put("ibeaconMinor", it) }
        return obj
    }

    /**
     * Serialize a [CsiMeasurement] to a [JSONObject].
     *
     * Float values are converted to Double via [Float.toDouble] because
     * [JSONObject.put] only accepts Double, not Float. The JS SDK parses
     * these back as plain `number`.
     */
    fun toJson(csi: CsiMeasurement): JSONObject {
        val obj = JSONObject()
        obj.put("nodeId", csi.nodeId)
        obj.put("occupantCount", csi.occupantCount)
        obj.put("motionScore", csi.motionScore.toDouble())
        obj.put("signalQuality", csi.signalQuality.toDouble())
        obj.put("subcarrierCount", csi.subcarrierCount)
        obj.put("captureRateHz", csi.captureRateHz.toDouble())
        obj.put("avgRssiDbm", csi.avgRssiDbm)
        obj.put("framesProcessed", csi.framesProcessed)
        obj.put("windowStartMs", csi.windowStartMs)
        obj.put("windowEndMs", csi.windowEndMs)
        return obj
    }
}
