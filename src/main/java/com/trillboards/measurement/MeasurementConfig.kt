package com.trillboards.measurement

/**
 * Signal sources collected by the SDK.
 *
 * Wire-format alignment: the agent-core-lite scanner stack already emits
 * `source='ble' | 'wifi' | 'mdns' | 'ssdp' | 'http_probe' | 'native_sensor'`
 * rows into `signal_observations`. This enum gates which scanners run on
 * each [TrillboardsMeasurement.scan] tick — a partner that wants to drop
 * mDNS (e.g. because their fleet is on a tightly-locked LAN) can do so
 * without forking the SDK.
 *
 * No audio, no CV — the agent-core (camera-enabled) SDK is the separate
 * fleet artifact for those signals.
 */
public enum class SignalSource {
    BLE,
    WIFI,
    MDNS,
    SSDP,
    HTTP_PROBE,
    NATIVE_SENSOR,
}

/**
 * Configuration for the Trillboards Measurement SDK.
 *
 * Use the [Builder] to construct an instance. The builder validates all values
 * at build time and clamps numeric parameters to safe ranges.
 *
 * Example:
 * ```kotlin
 * val config = MeasurementConfig.Builder("your-api-key", "your-device-id")
 *     .scanIntervalMs(30_000)
 *     .scanDurationMs(5_000)
 *     .enabledSources(setOf(SignalSource.BLE, SignalSource.WIFI))
 *     .debugLogging(false)
 *     .build()
 * ```
 */
public class MeasurementConfig private constructor(
    /** API key used to authenticate measurement payloads. Must be a `tb_ctv_*` key. */
    public val apiKey: String,
    /**
     * Device identifier used as the heartbeat fingerprint. Required for upload.
     *
     * The server hashes this with a daily-rotating pepper before persisting,
     * so passing a UUID, ANDROID_ID, or any stable per-install string is fine.
     * Partners should reuse the same `deviceId` across SDK invocations on the
     * same device — that's the surface the identity-graph clusters against.
     */
    public val deviceId: String,
    /** Interval between scheduled scans in milliseconds. Minimum 10,000. */
    public val scanIntervalMs: Long,
    /** Duration of each BLE scan window in milliseconds. Clamped to [MIN_SCAN_DURATION_MS]..[MAX_SCAN_DURATION_MS]. */
    public val scanDurationMs: Long,
    /**
     * Which signal sources to collect on each scan. Defaults to
     * [DEFAULT_ENABLED_SOURCES] (BLE + WiFi + mDNS + SSDP + HTTP probe).
     *
     * `NATIVE_SENSOR` is opt-in only — partners must explicitly add it to the
     * set if they want ambient light + barometer readings on the heartbeat.
     */
    public val enabledSources: Set<SignalSource>,
    /** Whether BLE scanning is enabled. Mirrors `SignalSource.BLE in enabledSources`. */
    public val enableBle: Boolean,
    /** Whether WiFi environment scanning is enabled. Mirrors `SignalSource.WIFI in enabledSources`. */
    public val enableWifi: Boolean,
    /** Whether ESP32 CSI (WiFi Channel State Information) sensing is enabled. When false, CSI hardware is not discovered. */
    public val enableCsi: Boolean,
    /** Whether debug logging via [android.util.Log] is enabled. */
    public val debugLogging: Boolean,
    /**
     * Base URL of the Trillboards ingest API. Defaults to production
     * (`https://api.trillboards.com`); override only for partner-private
     * staging or on-prem deployments.
     */
    public val apiBaseUrl: String,
) {

    /**
     * Builder for [MeasurementConfig].
     *
     * @param apiKey Non-empty API key (typically a `tb_ctv_*` key issued
     *   via the partner-self-serve flow).
     * @param deviceId Non-empty per-device identifier. Becomes the
     *   heartbeat `fingerprint` field; the server hashes it with a daily
     *   pepper. Partners should reuse the same value across SDK calls on
     *   the same device.
     */
    public class Builder(
        private val apiKey: String,
        private val deviceId: String,
    ) {

        private var scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS
        private var scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS
        private var enabledSources: Set<SignalSource> = DEFAULT_ENABLED_SOURCES
        private var enableCsi: Boolean = false
        private var debugLogging: Boolean = false
        private var apiBaseUrl: String = DEFAULT_API_BASE_URL

        /**
         * Set the interval between scheduled scans.
         * Values below [MIN_SCAN_INTERVAL_MS] are clamped up.
         */
        public fun scanIntervalMs(value: Long): Builder = apply {
            this.scanIntervalMs = value
        }

        /**
         * Set the duration of each BLE scan window.
         * Values below [MIN_SCAN_DURATION_MS] are clamped up; values above [MAX_SCAN_DURATION_MS] are clamped down.
         */
        public fun scanDurationMs(value: Long): Builder = apply {
            this.scanDurationMs = value
        }

        /**
         * Set which signal sources the SDK collects on each scan.
         * Empty sets are rejected at [build] time.
         */
        public fun enabledSources(value: Set<SignalSource>): Builder = apply {
            this.enabledSources = value.toSet()
        }

        /**
         * Enable or disable BLE scanning. Convenience wrapper around
         * [enabledSources] — `enableBle(false)` removes [SignalSource.BLE]
         * from the current set; `enableBle(true)` adds it back. Default true.
         */
        public fun enableBle(value: Boolean): Builder = apply {
            this.enabledSources = if (value) {
                this.enabledSources + SignalSource.BLE
            } else {
                this.enabledSources - SignalSource.BLE
            }
        }

        /**
         * Enable or disable WiFi environment scanning. Same convenience-wrapper
         * pattern as [enableBle]. Default true.
         */
        public fun enableWifi(value: Boolean): Builder = apply {
            this.enabledSources = if (value) {
                this.enabledSources + SignalSource.WIFI
            } else {
                this.enabledSources - SignalSource.WIFI
            }
        }

        /** Enable or disable ESP32 CSI sensing. Default false. */
        public fun enableCsi(value: Boolean): Builder = apply {
            this.enableCsi = value
        }

        /** Enable or disable debug logging. Default false. */
        public fun debugLogging(value: Boolean): Builder = apply {
            this.debugLogging = value
        }

        /**
         * Override the ingest API base URL. Default is production
         * (`https://api.trillboards.com`); set this only for partner-private
         * staging or on-prem deployments.
         */
        public fun apiBaseUrl(value: String): Builder = apply {
            this.apiBaseUrl = value
        }

        /**
         * Build the configuration, validating all parameters.
         *
         * @throws IllegalArgumentException if [apiKey] or [deviceId] is blank
         *   or [enabledSources] is empty.
         */
        public fun build(): MeasurementConfig {
            require(apiKey.isNotBlank()) { "apiKey must not be blank" }
            require(deviceId.isNotBlank()) { "deviceId must not be blank" }
            require(enabledSources.isNotEmpty()) { "enabledSources must not be empty" }

            return MeasurementConfig(
                apiKey = apiKey,
                deviceId = deviceId,
                scanIntervalMs = scanIntervalMs.coerceAtLeast(MIN_SCAN_INTERVAL_MS),
                scanDurationMs = scanDurationMs.coerceIn(MIN_SCAN_DURATION_MS, MAX_SCAN_DURATION_MS),
                enabledSources = enabledSources,
                enableBle = SignalSource.BLE in enabledSources,
                enableWifi = SignalSource.WIFI in enabledSources,
                enableCsi = enableCsi,
                debugLogging = debugLogging,
                apiBaseUrl = apiBaseUrl,
            )
        }
    }

    public companion object {
        /** Minimum allowed scan interval (10 seconds). */
        public const val MIN_SCAN_INTERVAL_MS: Long = 10_000L

        /** Minimum allowed scan duration (1 second). */
        public const val MIN_SCAN_DURATION_MS: Long = 1_000L

        /** Maximum allowed scan duration (10 seconds). */
        public const val MAX_SCAN_DURATION_MS: Long = 10_000L

        /** Default scan interval (30 seconds — matches tablet-agent fleet cadence). */
        public const val DEFAULT_SCAN_INTERVAL_MS: Long = 30_000L

        /** Default scan duration (5 seconds). */
        public const val DEFAULT_SCAN_DURATION_MS: Long = 5_000L

        /** Default ingest API base URL. */
        public const val DEFAULT_API_BASE_URL: String = "https://api.trillboards.com"

        /**
         * Default enabled signal sources — BLE + WiFi + mDNS + SSDP + HTTP probe.
         * Matches the full agent-core-lite scanner stack minus the opt-in
         * [SignalSource.NATIVE_SENSOR] (ambient light + barometer).
         */
        public val DEFAULT_ENABLED_SOURCES: Set<SignalSource> = setOf(
            SignalSource.BLE,
            SignalSource.WIFI,
            SignalSource.MDNS,
            SignalSource.SSDP,
            SignalSource.HTTP_PROBE,
        )
    }
}
