package com.trillboards.measurement

/**
 * Immutable representation of a discovered BLE device.
 *
 * Raw MAC addresses are sent over TLS to the API; the API server applies a
 * KMS-backed daily-rotating HMAC pepper. Edge no longer hashes MACs (see
 * Phase 2 of the proximity-identity-graph plan). Device names are dropped
 * (free-text PII risk).
 *
 * Parsed manufacturer payload fields are populated from
 * `internal.BleAdvertisementParser` and remain stable across MAC rotation.
 *
 * @property rawAddress Raw MAC address.
 * @property rssi Received Signal Strength Indicator in dBm (typically -100 to -20).
 * @property deviceType Bluetooth device type constant (BluetoothDevice.DEVICE_TYPE_*).
 *   0 = UNKNOWN, 1 = CLASSIC, 2 = LE, 3 = DUAL.
 */
public data class BleDevice(
    public val rawAddress: String,
    public val rssi: Int,
    public val deviceType: Int,
    public val manufacturerCompanyId: Int? = null,
    public val appleContinuitySubtype: Int? = null,
    public val stableManufacturerPayloadHex: String? = null,
    public val serviceUuids: List<String>? = null,
    public val txPowerDbm: Int? = null,
    public val ibeaconUuid: String? = null,
    public val ibeaconMajor: Int? = null,
    public val ibeaconMinor: Int? = null
)
