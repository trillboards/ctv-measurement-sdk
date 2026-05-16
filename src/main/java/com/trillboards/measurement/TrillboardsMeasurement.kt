package com.trillboards.measurement

import android.content.Context
import android.webkit.WebView
import com.trillboards.ctv.core.AgentConfig
import com.trillboards.ctv.core.identity.BleBeaconScanner
import com.trillboards.ctv.core.identity.NativeSensorCollector
import com.trillboards.ctv.core.identity.toLegacy
import com.trillboards.discovery.MdnsAdapter
import com.trillboards.ctv.core.identity.SsdpDeviceInfo
import com.trillboards.ctv.core.identity.WifiEnvironmentAnalyzer
import com.trillboards.ctv.core.identity.WifiScanCollector
import com.trillboards.ctv.core.models.DeviceCapabilityPayload
import com.trillboards.ctv.core.models.HeartbeatPayload
import com.trillboards.ctv.core.net.ApiClient
import com.trillboards.discovery.HttpProbeAdapter
import com.trillboards.discovery.SsdpAdapter
import com.trillboards.measurement.internal.HeartbeatUploader
import com.trillboards.measurement.internal.Logger
import com.trillboards.measurement.internal.MeasurementBridge
import com.trillboards.measurement.internal.ScanScheduler
import com.trillboards.measurement.internal.SdkSnapshotAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Main entry point for the Trillboards Measurement SDK.
 *
 * Singleton facade that coordinates:
 *  - BLE / WiFi / mDNS / SSDP / HTTP-probe / native-sensor scanning via the
 *    canonical [com.trillboards.ctv.core.identity] scanner stack shipped in
 *    `agent-core-lite` (production-hardened across the tablet-agent fleet —
 *    658K signal_observations rows/day).
 *  - WebView bridge attachment for ad-creative consumption of cached snapshots.
 *  - Heartbeat POST to `/openrtb/v1/heartbeat` via [ApiClient.sendHeartbeat] —
 *    same wire contract as the in-house fleet, so partner-emitted signals
 *    land in the same `signal_observations` / `resolved_device_observations`
 *    L0/L1 tables as the in-house fleet.
 *
 * Lifecycle:
 * 1. [initialize] — call once from `Application.onCreate()` or `Activity.onCreate()`.
 * 2. [setConsentStatus] — gate all scanning behind user/publisher consent.
 * 3. [scan] / [startScheduledScans] — begin measurement + upload.
 * 4. [attachToWebView] — expose snapshots to ad creatives (optional).
 * 5. [shutdown] — release resources when done.
 *
 * Thread safety: all public methods are safe to call from any thread.
 * The internal coroutine scope uses [Dispatchers.Main] with a [SupervisorJob]
 * so child failures do not cancel siblings.
 *
 * Phase 2 of the L9 CTV-SDK plan consolidates this SDK onto agent-core-lite:
 * the six duplicated scanner files that previously lived under
 * `com.trillboards.measurement.internal` have been deleted; their
 * production-hardened siblings under `com.trillboards.ctv.core.identity` are
 * the single source of truth.
 */
public object TrillboardsMeasurement {

    private const val TAG = "TrillboardsMeasurement"
    private const val AGENT_TYPE = "ctv-measurement-sdk-android"
    private const val AGENT_VERSION = "1.0.0"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var config: MeasurementConfig? = null

    @Volatile
    private var cachedSnapshot: MeasurementSnapshot? = null

    @Volatile
    private var consentGranted: Boolean = false

    @Volatile
    private var scheduledScansRequested: Boolean = false

    private var scope: CoroutineScope? = null
    private var scheduler: ScanScheduler? = null
    private var uploader: HeartbeatUploader? = null

    @Volatile
    private var bridge: MeasurementBridge? = null

    private val initLock = Any()

    /**
     * Whether the SDK has been initialized via [initialize].
     */
    public val isInitialized: Boolean
        get() = appContext != null && config != null

    /**
     * Initialize the SDK. Must be called before any other method.
     *
     * Calling initialize a second time with the same or different config
     * is idempotent — the SDK reuses the existing state without crashing.
     *
     * @param context Any Android [Context]. The SDK retains only the
     *   application context to prevent Activity leaks.
     * @param config SDK configuration built via [MeasurementConfig.Builder].
     */
    public fun initialize(context: Context, config: MeasurementConfig) {
        synchronized(initLock) {
            if (isInitialized) {
                Logger.d(TAG, "Already initialized — ignoring duplicate call")
                return
            }
            this.appContext = context.applicationContext
            this.config = config
            this.scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            this.scheduler = ScanScheduler()
            // Construct the ApiClient + uploader once at init time so the
            // OkHttp client + HMAC nonce machinery is allocated exactly once
            // per SDK lifetime. AgentConfig.heartbeatPath defaults to
            // `/openrtb/v1/heartbeat` — identical wire route as the in-house
            // tablet-agent fleet.
            val agentConfig = AgentConfig(
                apiBaseUrl = config.apiBaseUrl,
                sharedPrefsName = "trillboards_measurement_sdk_prefs",
                overlayRefreshAction = "com.trillboards.measurement.OVERLAY_REFRESH",
                overlayBlackoutAction = "com.trillboards.measurement.OVERLAY_BLACKOUT",
            )
            val apiClient = ApiClient(agentConfig) { config.apiKey }
            this.uploader = HeartbeatUploader(apiClient)

            Logger.enabled = config.debugLogging
            Logger.d(
                TAG,
                "Initialized: scanInterval=${config.scanIntervalMs}ms, " +
                    "scanDuration=${config.scanDurationMs}ms, " +
                    "sources=${config.enabledSources.joinToString(",")}, " +
                    "apiBaseUrl=${config.apiBaseUrl}"
            )
        }
    }

    /**
     * Set whether the publisher has obtained consent for measurement.
     *
     * When [granted] is false, [scan] returns null immediately and
     * [startScheduledScans] is a no-op. Consent can be toggled at any time.
     *
     * If consent is re-granted and [startScheduledScans] was previously requested,
     * scans will automatically resume.
     *
     * @param granted true if consent has been obtained, false otherwise.
     */
    public fun setConsentStatus(granted: Boolean) {
        consentGranted = granted
        Logger.d(TAG, "Consent status set to $granted")
        if (!granted) {
            stopScheduledScans()
        } else if (scheduledScansRequested && isInitialized) {
            // Re-grant: resume scheduled scans that were previously requested
            val cfg = config ?: return
            val currentScope = scope ?: return
            val currentScheduler = scheduler ?: return
            currentScheduler.start(currentScope, cfg.scanIntervalMs) {
                scan()
            }
            Logger.d(TAG, "Scheduled scans resumed after consent re-granted")
        }
    }

    /**
     * Perform a single measurement scan and return a [MeasurementSnapshot].
     *
     * Pipeline per tick:
     *  1. Run every scanner in [MeasurementConfig.enabledSources] in parallel
     *     where independent (BLE + WiFi + mDNS + SSDP all run concurrently).
     *  2. Build a [HeartbeatPayload] from the collected rows.
     *  3. POST to the configured ingest endpoint via [ApiClient.sendHeartbeat]
     *     (honors `Retry-After` on 429; logs and skips on permanent failures).
     *  4. Compose a [MeasurementSnapshot] for the WebView bridge cache.
     *
     * @return The latest [MeasurementSnapshot], or null if not initialized,
     *   consent is not granted, or every enabled scanner failed.
     */
    public suspend fun scan(): MeasurementSnapshot? {
        if (!isInitialized) {
            Logger.w(TAG, "scan() called before initialize() — ignoring")
            return null
        }

        if (!consentGranted) {
            Logger.d(TAG, "Consent not granted — scan skipped")
            return null
        }

        val cfg = config ?: return null
        val ctx = appContext ?: return null
        val up = uploader ?: return null

        return try {
            // ── BLE ──
            val bleSnapshot = if (SignalSource.BLE in cfg.enabledSources) {
                try {
                    BleBeaconScanner.scan(ctx, cfg.scanDurationMs)
                } catch (e: Exception) {
                    Logger.w(TAG, "BLE scan failed: ${e.message}")
                    null
                }
            } else null

            // ── WiFi (scan + environment analysis) ──
            val wifiScanSnapshot = if (SignalSource.WIFI in cfg.enabledSources) {
                try { WifiScanCollector.collect(ctx) } catch (e: Exception) {
                    Logger.w(TAG, "WiFi scan failed: ${e.message}")
                    null
                }
            } else null
            val wifiEnvironment = if (SignalSource.WIFI in cfg.enabledSources && wifiScanSnapshot != null) {
                try { WifiEnvironmentAnalyzer.analyze(ctx, wifiScanSnapshot) } catch (e: Exception) {
                    Logger.w(TAG, "WiFi environment analysis failed: ${e.message}")
                    null
                }
            } else null

            // ── mDNS ──
            //
            // Phase 1 cutover: legacy `MdnsDiscovery.discover()` shim was
            // deleted. Call the Rust-backed `MdnsAdapter` directly and
            // translate to the legacy `com.trillboards.ctv.core.identity.
            // MdnsSnapshot` consumed by SdkSnapshotAdapter / HeartbeatPayload.
            val mdnsSnapshot = if (SignalSource.MDNS in cfg.enabledSources) {
                try {
                    MdnsAdapter.discover(ctx)?.toLegacy()
                } catch (e: Exception) {
                    Logger.w(TAG, "mDNS discovery failed: ${e.message}")
                    null
                }
            } else null

            // ── SSDP ──
            val ssdpDevices: List<SsdpDeviceInfo>? = if (SignalSource.SSDP in cfg.enabledSources) {
                try {
                    SsdpAdapter.discoverWithReason(ctx).devices.map { d ->
                        SsdpDeviceInfo(
                            location = d.location,
                            server = d.server,
                            friendlyName = d.friendlyName,
                            manufacturer = d.manufacturer,
                            modelName = d.modelName,
                            udn = d.udn,
                            st = d.st,
                        )
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "SSDP discovery failed: ${e.message}")
                    null
                }
            } else null

            // ── HTTP probe ──
            //
            // HttpProbeAdapter takes specific host/port pairs. The canonical
            // partner flow is "probe whatever responded to mDNS / SSDP on
            // port 80/8080" — those hosts produced a `Server:` header that
            // we want to identify. Build the candidate list from mDNS host
            // names + SSDP locations (best-effort URL parse).
            val httpProbes: List<Map<String, Any?>>? =
                if (SignalSource.HTTP_PROBE in cfg.enabledSources) {
                    runProbes(ssdpDevices, mdnsSnapshot)
                } else null

            // ── Native sensor (opt-in) ──
            val nativeSensorSnapshot = if (SignalSource.NATIVE_SENSOR in cfg.enabledSources) {
                try { NativeSensorCollector.collect(ctx) } catch (e: Exception) {
                    Logger.w(TAG, "Native sensor collection failed: ${e.message}")
                    null
                }
            } else null

            // ── Compose heartbeat payload (canonical wire shape) ──
            val payload = HeartbeatPayload(
                fingerprint = cfg.deviceId,
                screenId = null,
                status = "online",
                metadata = mapOf(
                    "agent_type" to AGENT_TYPE,
                    "agent_version" to AGENT_VERSION,
                ),
                capabilities = buildCapabilities(),
                nearbyBleDevices = bleSnapshot?.devices,
                bleDeviceCount = bleSnapshot?.deviceCount,
                nearbyWifiNetworks = wifiScanSnapshot?.networks,
                wifiNetworkCount = wifiScanSnapshot?.uniqueBssidCount,
                wifiEnvironment = wifiEnvironment,
                discoveredNetworkDevices = mdnsSnapshot?.devices,
                networkDeviceCount = mdnsSnapshot?.deviceCount,
                ssdpDevices = ssdpDevices,
                httpProbes = httpProbes,
                nativeSensorSnapshot = nativeSensorSnapshot,
            )

            // ── POST heartbeat (honors Retry-After on 429) ──
            up.upload(payload)

            // ── Build local snapshot for WebView bridge ──
            val snapshot = SdkSnapshotAdapter.fromAgentCore(
                bleSnapshot = bleSnapshot,
                wifiEnvironment = wifiEnvironment,
            )

            cachedSnapshot = snapshot
            val parts = mutableListOf<String>()
            if (bleSnapshot != null) parts.add("${bleSnapshot.deviceCount} BLE devices")
            if (wifiEnvironment != null) parts.add("${wifiEnvironment.networkCount} WiFi APs")
            if (mdnsSnapshot != null) parts.add("${mdnsSnapshot.deviceCount} mDNS")
            if (ssdpDevices != null) parts.add("${ssdpDevices.size} SSDP")
            if (httpProbes != null) parts.add("${httpProbes.size} HTTP probes")
            Logger.d(TAG, "Scan complete: ${parts.joinToString(", ")}")

            // Push to attached WebView via CustomEvent (push mode).
            // No-op if no bridge is attached or the WebView reference has been cleared.
            bridge?.pushSnapshot(snapshot)

            snapshot
        } catch (e: Exception) {
            Logger.w(TAG, "Scan failed", e)
            null
        }
    }

    /**
     * Return the most recent [MeasurementSnapshot], or null if no scan
     * has completed yet.
     */
    public fun getSnapshot(): MeasurementSnapshot? = cachedSnapshot

    /**
     * Attach the measurement JavaScript bridge to a [WebView].
     *
     * After attachment, ad creatives can call:
     * - `window.TrillboardsMeasurement.getSnapshot()` — returns JSON string
     * - `window.TrillboardsMeasurement.getVersion()` — returns bridge version
     *
     * The SDK holds the WebView via a [java.lang.ref.WeakReference] to prevent
     * Activity/Fragment leaks.
     *
     * Must be called before `WebView.loadUrl()`.
     *
     * @param webView The WebView to attach the bridge to.
     */
    public fun attachToWebView(webView: WebView) {
        if (!isInitialized) {
            Logger.w(TAG, "attachToWebView() called before initialize() — ignoring")
            return
        }
        bridge = MeasurementBridge.attach(webView) { cachedSnapshot }
        Logger.d(TAG, "Bridge attached to WebView")
    }

    /**
     * Start periodic scans at the interval configured in [MeasurementConfig].
     *
     * Each scan result is cached, uploaded to the ingest API, and if a
     * WebView bridge is attached, pushed to the WebView via a CustomEvent.
     *
     * Records the intent to scan even if consent is not yet granted or SDK is not
     * yet initialized — scans will auto-start when consent is re-granted.
     */
    public fun startScheduledScans() {
        scheduledScansRequested = true

        if (!isInitialized) {
            Logger.w(TAG, "startScheduledScans() called before initialize() — request recorded, will start after init+consent")
            return
        }

        if (!consentGranted) {
            Logger.d(TAG, "Consent not granted — scheduled scans not started")
            return
        }

        val cfg = config ?: return
        val currentScope = scope ?: return
        val currentScheduler = scheduler ?: return

        currentScheduler.start(currentScope, cfg.scanIntervalMs) {
            scan()
        }
        Logger.d(TAG, "Scheduled scans started at ${cfg.scanIntervalMs}ms interval")
    }

    /**
     * Stop periodic scans. Safe to call even if scans are not running.
     */
    public fun stopScheduledScans() {
        scheduledScansRequested = false
        scheduler?.stop()
        Logger.d(TAG, "Scheduled scans stopped")
    }

    /**
     * Release all SDK resources. After shutdown, [initialize] must be
     * called again before the SDK can be used.
     *
     * Cancels all coroutines, stops scheduled scans, and clears cached data.
     */
    public fun shutdown() {
        synchronized(initLock) {
            scheduledScansRequested = false
            scheduler?.stop()
            scope?.cancel()
            scope = null
            scheduler = null
            uploader = null
            bridge = null
            config = null
            appContext = null
            cachedSnapshot = null
            consentGranted = false
            Logger.d(TAG, "Shutdown complete")
            Logger.enabled = false
        }
    }

    /**
     * Minimal device-capabilities payload. The full agent reports chipset /
     * NPU / camera / MDM fields via [com.trillboards.ctv.core.inference.HardwareManifestLite];
     * the public SDK ships a conservative default since we don't gate on
     * those signals server-side for ctv-measurement-SDK heartbeats. Partners
     * who want richer capability reporting should integrate agent-core
     * directly.
     */
    private fun buildCapabilities(): DeviceCapabilityPayload = DeviceCapabilityPayload(
        agentType = AGENT_TYPE,
        agentVersion = AGENT_VERSION,
    )

    /**
     * Run HTTP HEAD probes against the union of mDNS hosts + SSDP location
     * hosts on ports 80 and 8080. Returns one row per probe that produced a
     * non-null `Server:` header (or a non-null skip reason).
     *
     * Bounded at 16 probes per tick to keep the heartbeat hot-path budget
     * predictable — at most ~24 s of wall-clock if every probe times out
     * (16 × 1.5 s = 24 s, single-threaded). Most LANs have ≤5 distinct
     * responders, so the bound is rarely binding in practice.
     */
    private fun runProbes(
        ssdpDevices: List<SsdpDeviceInfo>?,
        mdnsSnapshot: com.trillboards.ctv.core.identity.MdnsSnapshot?,
    ): List<Map<String, Any?>>? {
        val candidates = mutableListOf<Pair<String, Int>>()

        // mDNS hosts — only include rows with a non-null host
        mdnsSnapshot?.devices?.forEach { d ->
            d.host?.takeIf { it.isNotBlank() }?.let { host ->
                val port = d.port ?: 80
                candidates += host to port
            }
        }

        // SSDP locations — parse "<scheme>://<host>:<port>/..." defensively
        ssdpDevices?.forEach { d ->
            try {
                val uri = java.net.URI(d.location)
                val host = uri.host
                val port = if (uri.port > 0) uri.port else 80
                if (!host.isNullOrBlank()) candidates += host to port
            } catch (_: Exception) {
                // Malformed location URL — Rust core normalizes most of these
                // but defend against edge cases that slip through.
            }
        }

        val bounded = candidates.distinct().take(MAX_PROBES_PER_TICK)
        if (bounded.isEmpty()) return null

        return bounded.mapNotNull { (host, port) ->
            val portU = port.coerceIn(1, 65535).toUShort()
            val result = HttpProbeAdapter.probeWithReason(host, portU)
            if (result.server == null) null else mapOf(
                "host" to host,
                "port" to port,
                "server" to result.server,
            )
        }.takeIf { it.isNotEmpty() }
    }

    private const val MAX_PROBES_PER_TICK = 16
}
