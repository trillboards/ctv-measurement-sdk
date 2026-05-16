package com.trillboards.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapter wrapping the Rust `discovery-core` crate's `runMdnsDiscovery` so the
 * 3-fork Android consumers (`agent-core`, `agent-core-lite`,
 * `trillboards-measurement-sdk`) can drop their copy-pasted `MdnsDiscovery.kt`
 * implementations and call this instead.
 *
 * Wire-format contract: the heartbeat payload's `discovered_network_devices`
 * JSONArray emitted by `ApiClient.kt` reads exactly the fields produced by
 * [discover]. The translated [NetworkDevice] / [MdnsSnapshot] data classes
 * keep the legacy field names (`serviceType`, `instanceName`, `host`, `port`,
 * `mdnsModel`, `mdnsVendor`, `mdnsSoftwareVersion`) so the JSON shape is
 * BYTE-IDENTICAL to what the legacy Kotlin path produced.
 *
 * Permission contract:
 *  - The crate uses `mdns-sd` 0.19.1, a pure-Rust multicast-DNS responder.
 *    On Android, multicast packets are silently dropped by the framework
 *    unless the caller holds a [WifiManager.MulticastLock]. We acquire and
 *    release the lock around every [discover] call here; the Rust side knows
 *    nothing about Android's multicast-state plumbing on purpose (keeps the
 *    crate portable to Linux/Windows agent hosts).
 *  - The lock requires `CHANGE_WIFI_MULTICAST_STATE` in the consuming app's
 *    AndroidManifest. The 3 forks already declare `INTERNET` — the
 *    multicast permission is added in the Phase 1 PR.
 *  - The MulticastLock `setReferenceCounted(false)` mode is safe even when
 *    multiple discoveries overlap: `acquire()` is idempotent. We use
 *    reference-counting mode here (default) and pair every acquire with a
 *    release inside a try/finally.
 *
 * Concurrency: an [java.util.concurrent.atomic.AtomicBoolean] gate prevents
 * two parallel [discover] calls from spinning up two `ServiceDaemon`s on
 * the same multicast socket. If a second call arrives while one is already
 * running, this returns null (legacy behavior).
 *
 * Error handling: [discover] swallows every exception thrown by the FFI
 * call (DiscoveryException.Daemon, DiscoveryException.InvalidServiceTypes,
 * UnsatisfiedLinkError if the .so somehow didn't bundle, generic Throwable
 * for anything weirder) and returns null. The legacy contract was the same
 * (caller falls back to its cached snapshot on null).
 */
public data class NetworkDevice(
    val serviceType: String,
    val instanceName: String,
    val host: String?,
    val port: Int?,
    val mdnsModel: String? = null,
    val mdnsVendor: String? = null,
    val mdnsSoftwareVersion: String? = null
)

public data class MdnsSnapshot(
    val devices: List<NetworkDevice>,
    val deviceCount: Int,
    val discoveryDurationMs: Long,
    val discoveryTimestampMs: Long,
    /**
     * Why this discovery cycle produced zero rows, when zero rows is
     * unexpected. Null = discovery ran successfully (rows may be 0 because
     * no responders are on the LAN — observable noise). Non-null = the
     * best-effort path bailed and the heartbeat builder should surface
     * the reason on the payload so a server-side cron can detect the
     * per-screen drop and alert.
     *
     * Mirrors `BleScanSnapshot.skipReason` (PR #4480). Class fix for the
     * silent-zero-rows pattern that lost 27h of BLE data without an
     * alarm: every recoverable failure now ships its reason upward
     * instead of returning null and being indistinguishable from "no
     * devices in range."
     */
    val skipReason: String? = null
)

public object MdnsAdapter {

    private const val TAG = "MdnsAdapter"

    // Skip-reason enum — kept as plain strings so the heartbeat-payload
    // pass-through stays primitive and the server-side classifier can
    // group on them without a Kotlin/JS shared schema dance. Mirrors
    // BleBeaconScanner's SKIP_* constants (PR #4480).
    public const val SKIP_MULTICAST_LOCK_FAILED: String = "multicast_lock_failed"
    public const val SKIP_DISCOVERY_IN_PROGRESS: String = "discovery_in_progress"
    public const val SKIP_FFI_FAILURE: String = "ffi_failure"

    /**
     * Default service types — same list as the legacy 3-fork `MdnsDiscovery.kt`,
     * with the same trailing-dot convention. The Rust core normalizes both
     * shapes (`_googlecast._tcp.` and `_googlecast._tcp.local.`) so this list
     * does NOT need to change as we add Phase 2-4 protocols.
     */
    private val DEFAULT_SERVICE_TYPES: List<String> = listOf(
        "_roku._tcp.",              // Roku streaming devices
        "_airplay._tcp.",           // Apple TV, AirPlay-compatible devices
        "_googlecast._tcp.",        // Chromecast, Google Home, Nest
        "_amzn-wplay._tcp.",        // Amazon Echo, Fire TV (WPlay protocol)
        "_androidtvremote2._tcp.",  // Android TV remote protocol
        "_ipp._tcp.",               // Printers (IPP)
        "_http._tcp.",              // Generic HTTP services (smart home, etc.)
        "_trillboards-csi._udp."    // ESP32 CSI sensing nodes
    )

    /**
     * Bounds on the discovery timeout; mirrors the legacy
     * [com.trillboards.ctv.core.identity.MdnsDiscovery] constants so the
     * agent's heartbeat-wall-clock budget is unchanged.
     */
    private const val DEFAULT_DISCOVERY_DURATION_MS: Long = 8_000L
    private const val MAX_DISCOVERY_DURATION_MS: Long = 15_000L
    private const val MIN_DISCOVERY_DURATION_MS: Long = 3_000L

    private val discoveryInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Discover network devices via the Rust mDNS core.
     *
     * @param context Application context — used to obtain the [WifiManager]
     *   for the multicast lock.
     * @param discoveryDurationMs Wall-clock budget. Clamped into
     *   [3000, 15000] ms; the Rust core also clamps so a buggy caller can't
     *   hang the daemon.
     * @param serviceTypes Optional override of [DEFAULT_SERVICE_TYPES]. Phase
     *   1 callers do NOT pass this — they want the same 8 types the legacy
     *   code listened on.
     * @return [MdnsSnapshot] with per-device rows, or null if discovery
     *   could not run (already in progress, FFI errored, .so missing, etc).
     */
    @JvmOverloads
    public suspend fun discover(
        context: Context,
        discoveryDurationMs: Long = DEFAULT_DISCOVERY_DURATION_MS,
        serviceTypes: List<String> = DEFAULT_SERVICE_TYPES
    ): MdnsSnapshot? = withContext(Dispatchers.IO) {
        if (!discoveryInProgress.compareAndSet(false, true)) {
            // Already-in-progress is the one skip we deliberately do NOT
            // surface upward — it's a benign duplicate-call guard, not a
            // misconfiguration. Caller's previous in-flight call will
            // emit the snapshot. Log at debug so it stays out of warn-
            // level dashboards. Returning null preserves the historical
            // benign-skip contract; the heartbeat builder already treats
            // null as "use cached snapshot".
            Log.d(TAG, "Discovery already in progress — skipping")
            return@withContext null
        }

        val effectiveDuration =
            discoveryDurationMs.coerceIn(MIN_DISCOVERY_DURATION_MS, MAX_DISCOVERY_DURATION_MS)
        val started = System.currentTimeMillis()
        val multicastLock = acquireMulticastLock(context)

        // If we couldn't acquire the multicast lock, Android's framework
        // silently drops inbound multicast packets at the netd / kernel
        // layer regardless of `INTERNET` permission. Returning empty here
        // would look identical to "no devices on LAN" — instead surface
        // SKIP_MULTICAST_LOCK_FAILED so the heartbeat builder can count
        // it. WiFi unavailable in the emulator is the common-case false
        // trigger; keep the log at warn-level so production kiosks
        // running without a multicast lock light up the per-fleet
        // skip-reason CW dashboard.
        if (multicastLock == null) {
            Log.w(TAG, "Multicast lock acquire failed — skipping (WifiManager unavailable or lock denied)")
            discoveryInProgress.set(false)
            return@withContext emptySnapshotWithReason(SKIP_MULTICAST_LOCK_FAILED, started)
        }

        try {
            val snapshot = runCatching {
                runMdnsDiscovery(
                    timeoutMs = effectiveDuration.toUInt(),
                    serviceTypes = serviceTypes
                )
            }.getOrElse { e ->
                // DiscoveryException + UnsatisfiedLinkError + every other
                // Throwable lands here. Promoted from the previous "log +
                // return null" to "log + return snapshot with
                // SKIP_FFI_FAILURE" so the per-screen drop is observable
                // server-side. Caller's heartbeat-builder cache fallback
                // continues to work the same way (null devices → cached
                // snapshot).
                Log.w(TAG, "mDNS discovery failed: ${e.javaClass.simpleName}: ${e.message}")
                return@withContext emptySnapshotWithReason(SKIP_FFI_FAILURE, started)
            }

            val translated = translateSnapshot(snapshot, started)
            Log.d(
                TAG,
                "mDNS discovery complete: ${translated.deviceCount} devices in ${translated.discoveryDurationMs}ms"
            )
            translated
        } finally {
            multicastLock.let {
                runCatching { it.release() }
                    .onFailure { e -> Log.d(TAG, "MulticastLock.release failed: ${e.message}") }
            }
            discoveryInProgress.set(false)
        }
    }

    /**
     * Build an empty snapshot tagged with a [skipReason]. Mirror of
     * `BleBeaconScanner.emptySnapshotWithReason` so server-side classifiers
     * see the same shape from every adapter.
     */
    internal fun emptySnapshotWithReason(reason: String, startedMs: Long): MdnsSnapshot =
        MdnsSnapshot(
            devices = emptyList(),
            deviceCount = 0,
            discoveryDurationMs = 0L,
            discoveryTimestampMs = startedMs,
            skipReason = reason
        )

    /**
     * Acquire a [WifiManager.MulticastLock]. Returns null if WifiManager is
     * unavailable (e.g. emulator without a WiFi service). Caller releases it
     * in `finally`.
     *
     * Without this lock Android's framework drops multicast packets at the
     * netd / kernel layer regardless of `INTERNET` permission, so the
     * `mdns-sd` daemon would just sit listening to silence.
     */
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return runCatching {
            wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()
    }

    /**
     * Translate the Rust-side [DiscoverySnapshot] into the legacy
     * [MdnsSnapshot] / [NetworkDevice] shape so heartbeat JSON serialization
     * does not change in Phase 1. Phase 2-4 add NEW optional fields here.
     */
    internal fun translateSnapshot(
        snapshot: DiscoverySnapshot,
        startedMs: Long
    ): MdnsSnapshot {
        val durationMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L)
        val devices = snapshot.devices.map { d ->
            NetworkDevice(
                serviceType = d.serviceType,
                instanceName = d.instanceName,
                // Legacy contract: `host` and `port` are nullable on the
                // Kotlin side. Rust always emits a non-empty `host` (we
                // dropped rows without an address upstream) and a non-zero
                // port. Convert UShort → Int? here, treat 0 as null per the
                // legacy `if (info.port > 0) info.port else null` shape.
                host = d.host.takeIf { it.isNotBlank() },
                port = d.port.toInt().takeIf { it > 0 },
                mdnsModel = d.model,
                mdnsVendor = d.vendor,
                mdnsSoftwareVersion = d.softwareVersion
            )
        }
        return MdnsSnapshot(
            devices = devices,
            deviceCount = devices.size,
            discoveryDurationMs = durationMs,
            discoveryTimestampMs = startedMs
        )
    }
}
