package com.trillboards.measurement

/**
 * Immutable snapshot of a single measurement scan cycle.
 *
 * Captures the BLE devices discovered during one scan window along with
 * timing metadata and optional WiFi / CSI sensing data. Snapshots are cached
 * by [TrillboardsMeasurement] and can be pushed to a WebView via the
 * JavaScript bridge.
 *
 * @property bleDevices List of discovered BLE devices, sorted by RSSI descending
 *   (strongest/closest first). Capped at 100 entries.
 * @property bleDeviceCount Number of devices in [bleDevices]. Provided as a
 *   convenience to avoid calling `bleDevices.size`.
 * @property scanDurationMs Wall-clock duration of the scan in milliseconds.
 * @property scanTimestampMs Unix epoch millis when the scan started.
 * @property wifiEnvironment WiFi environment signals. Null if WiFi scanning is
 *   disabled or unavailable.
 * @property csiMeasurement CSI measurement from ESP32 WiFi sensing hardware.
 *   Null if no ESP32 is present or CSI sensing is disabled.
 */
public data class MeasurementSnapshot(
    public val bleDevices: List<BleDevice>,
    public val bleDeviceCount: Int,
    public val scanDurationMs: Long,
    public val scanTimestampMs: Long,
    /** WiFi environment signals. Null if WiFi scanning is disabled or unavailable. */
    public val wifiEnvironment: WifiEnvironment? = null,
    /** CSI measurement from ESP32 WiFi sensing hardware. Null if no ESP32 present. */
    public val csiMeasurement: CsiMeasurement? = null,
)

/**
 * Structured WiFi environment signals for venue intelligence.
 *
 * These signals feed into the unified intelligence pipeline:
 * - Moment embedding (composeMomentDescription) for pgvector semantic search
 * - Evidence grade hierarchy for venue classification trust
 * - VAS formula as environmental context
 */
public data class WifiEnvironment(
    /** Number of visible WiFi access points — venue density proxy. */
    public val networkCount: Int,
    /** RSSI of connected AP in dBm (-120..0). */
    public val signalStrengthDbm: Int,
    /** Center frequency of connected AP in MHz (2412-7115). */
    public val frequencyMhz: Int,
    /** Channel width of connected AP: 20, 40, 80, or 160 MHz. Null if unavailable. */
    public val channelWidthMhz: Int?,
    /** Link speed of connected AP in Mbps. Null if unavailable. */
    public val linkSpeedMbps: Int?,
    /** Frequency band: "2.4ghz", "5ghz", "6ghz", or "unknown". */
    public val frequencyBand: String,
    /** Fraction of visible APs on same channel as connected (0.0-1.0). */
    public val channelCongestion: Float,
    /** RSSI variance over scan window — motion indicator. */
    public val rssiVariance: Float,
)

/**
 * Measurement from a single CSI (Channel State Information) collection window.
 *
 * Populated only when the partner enables [SignalSource.NATIVE_SENSOR] *and*
 * has Trillboards ESP32 CSI hardware on the same network as the SDK host.
 * Default-disabled — most partners will see `csiMeasurement = null`.
 *
 * The ESP32 hardware broadcasts CSI frames on UDP port 5005 via the
 * `_trillboards-csi._udp.` mDNS service. The CSI processor (Rust binary
 * shipped via @trillboards/edge-csi) does the actual frame parsing — the
 * SDK currently stubs frame parsing and returns null until the Rust
 * dependency lands.
 */
public data class CsiMeasurement(
    /** ESP32 node identifier extracted from frame headers. */
    public val nodeId: Int,
    /** Estimated number of occupants from amplitude-variance heuristic. */
    public val occupantCount: Int,
    /** Normalized motion indicator (0.0 = no motion, 1.0 = high motion). */
    public val motionScore: Float,
    /** Normalized signal quality (0.0 = poor, 1.0 = excellent). */
    public val signalQuality: Float,
    /** Number of OFDM subcarriers reported by the ESP32. */
    public val subcarrierCount: Int,
    /** Effective frame capture rate during the collection window. */
    public val captureRateHz: Float,
    /** Average RSSI across all received frames. */
    public val avgRssiDbm: Int,
    /** Total number of valid ADR-018 frames received. */
    public val framesProcessed: Int,
    /** Unix epoch millis when the collection window started. */
    public val windowStartMs: Long,
    /** Unix epoch millis when the collection window ended. */
    public val windowEndMs: Long,
)
