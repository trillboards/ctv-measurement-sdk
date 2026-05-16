package com.trillboards.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Thin Kotlin facade over the UniFFI-generated `runSsdpDiscovery` function.
 *
 * Why a separate adapter class instead of letting consumers call
 * `runSsdpDiscovery` directly?
 *   1. Consumer-side ergonomics: agents (`agent-core`, `agent-core-lite`,
 *      `trillboards-measurement-sdk`) already have one mDNS adapter and a
 *      future ARP adapter; a per-protocol adapter keeps the call sites
 *      uniform (`MdnsAdapter.discover(...)`, `SsdpAdapter.discover(...)`).
 *   2. Decouples the agent from the UniFFI generated package layout.
 *      If we ever swap UniFFI for a different bindgen the adapter API
 *      stays stable.
 *   3. Gives us a single place to add Android-specific guards (battery
 *      check, multicast lock acquisition, etc.) without touching every
 *      caller.
 *
 * The agent eventually calls `discover(timeoutMs)` once per heartbeat at
 * the same cadence as mDNS (~30 min). Default timeout is 5 s — generous
 * enough that even sleepy IoT respond inside the listen window.
 *
 * Privacy: SSDP devices return raw `friendlyName` / `manufacturer` /
 * `modelName` / `UDN` strings. The Rust core drops PII rows (personal-name
 * patterns, embedded MAC/email) before crossing the FFI boundary. The
 * remaining strings travel UNHASHED to the API server, which HMAC-hashes
 * them under the daily KMS-backed pepper (matches mDNS contract). The
 * agent never holds peppered hashes.
 */
/**
 * Skip-reason-tagged result returned by [SsdpAdapter.discoverWithReason].
 * Mirror of `BleScanSnapshot` and `MdnsSnapshot` so the heartbeat builder
 * can pass every adapter through one [SkipReasonAggregator] codepath.
 *
 * `devices` is always non-null but may be empty. `skipReason` is non-null
 * when the best-effort path bailed (couldn't acquire the multicast lock,
 * FFI threw). Empty + null skipReason = "discovery ran cleanly, no
 * responders on LAN" — observable noise, NOT a failure.
 */
data class SsdpDiscoveryResult(
    val devices: List<SsdpDevice>,
    val skipReason: String? = null
)

object SsdpAdapter {

    // Skip-reason enum — kept as plain strings so the heartbeat-payload
    // pass-through stays primitive. Mirrors BleBeaconScanner / MdnsAdapter
    // SKIP_* constants.
    const val SKIP_MULTICAST_LOCK_FAILED: String = "multicast_lock_failed"
    const val SKIP_FFI_FAILURE: String = "ffi_failure"

    /**
     * Default M-SEARCH listen window. 5 s strikes the balance between
     * giving sleepy IoT enough time (3 s MX header response spread + 2 s
     * for description.xml fetches) and keeping the heartbeat tight
     * (30-min cadence × 5 s = ~0.3% of agent CPU duty cycle).
     */
    const val DEFAULT_TIMEOUT_MS: Int = 5_000

    /**
     * Run a single SSDP discovery cycle. Returns the parsed device list,
     * which the caller serializes as `ssdp_devices` JSONArray on the
     * heartbeat payload.
     *
     * @param timeoutMs M-SEARCH listen window in milliseconds. Pass 0 to
     *   skip the network call entirely (used by the JUnit smoke test
     *   to verify the FFI binding without binding a UDP socket).
     *
     * @return List of [SsdpDevice] rows, possibly empty (no responders,
     *   timeout=0, or every responder PII-rejected). Returns empty list
     *   on FFI exception so the caller never has to handle [DiscoveryException]
     *   from the heartbeat hot path.
     */
    @JvmStatic
    fun discover(timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<SsdpDevice> {
        val ms = timeoutMs.coerceAtLeast(0).toUInt()
        return try {
            runSsdpDiscovery(ms).devices
        } catch (e: DiscoveryException) {
            // FFI-level failure: the Rust core threw `DiscoveryError::Network`
            // (UDP bind / multicast send failed), `Parse` (malformed XML),
            // `Timeout` (top-level deadline), or `Internal` (logic bug).
            // None of these are recoverable in-line. Log and degrade to empty
            // — the heartbeat still ships, mDNS / WiFi / BLE rows still land.
            //
            // We deliberately do NOT log the timeout path because that's the
            // common-case empty-LAN result, not an error.
            Log.w(TAG, "SSDP discovery failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Context-taking overload that holds a [WifiManager.MulticastLock] across
     * the M-SEARCH listen window. Without this lock the Android framework
     * silently drops inbound multicast UDP at netd / kernel layer, so the
     * Rust UDP socket waits for replies that never reach userspace and the
     * adapter returns an empty list even when UPnP devices are responding.
     *
     * Mirror of [MdnsAdapter.discover]'s lock-then-listen pattern. The mDNS
     * code wraps the same way; SSDP needs the same wrapper for the same
     * reason. Failure to acquire the lock (e.g. WifiManager unavailable in
     * the emulator) falls through to the lock-free path so the adapter
     * still works in test environments.
     *
     * Always pair with the no-arg [discover] (test-friendly, no Android
     * deps) — this overload is the production path on Android tablets.
     */
    @JvmStatic
    @JvmOverloads
    fun discover(context: Context, timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<SsdpDevice> {
        val multicastLock = acquireMulticastLock(context)
        return try {
            discover(timeoutMs)
        } finally {
            multicastLock?.let {
                runCatching { it.release() }
                    .onFailure { e -> Log.d(TAG, "MulticastLock.release failed: ${e.message}") }
            }
        }
    }

    /**
     * Skip-reason-tagged variant of [discover]. Mirror of the `BleScanSnapshot`
     * and `MdnsSnapshot` `skipReason` field — every recoverable failure ships
     * its reason upward so the server-side classifier can fire CW alarms on
     * per-fleet skip-reason counters instead of waiting for "absent rows" to
     * be diagnosed by hand (the silent-failure pattern that lost 27h of BLE
     * telemetry).
     *
     * Reasons:
     *   - `multicast_lock_failed` — `WifiManager.createMulticastLock` returned
     *     null or `acquire()` threw. Without the lock Android drops inbound
     *     multicast UDP at netd, so the adapter would otherwise return empty
     *     even when UPnP devices are responding.
     *   - `ffi_failure` — the Rust core threw any [DiscoveryException]
     *     (network/parse/timeout/internal) or an UnsatisfiedLinkError.
     *
     * Empty devices + null skip = "discovery ran cleanly, no responders".
     * That's still observable noise, but distinct from a misconfigured kiosk.
     */
    @JvmStatic
    @JvmOverloads
    fun discoverWithReason(
        context: Context,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): SsdpDiscoveryResult {
        val multicastLock = acquireMulticastLock(context)
        if (multicastLock == null) {
            // Promoted from the previous silent emptyList() return path.
            // A kiosk that's been deployed without the multicast permission
            // (or that fails to acquire because WifiManager is unavailable)
            // would otherwise look identical to "no UPnP devices on LAN" —
            // the exact silent-failure pattern PR #4480 fixes for BLE.
            Log.w(TAG, "Multicast lock acquire failed — skipping (WifiManager unavailable or lock denied)")
            return SsdpDiscoveryResult(emptyList(), SKIP_MULTICAST_LOCK_FAILED)
        }
        val ms = timeoutMs.coerceAtLeast(0).toUInt()
        return try {
            val snap = runSsdpDiscovery(ms)
            lastCapturedAtMsInternal = snap.capturedAtMs.toLong()
            SsdpDiscoveryResult(devices = snap.devices, skipReason = null)
        } catch (e: Throwable) {
            // DiscoveryException + UnsatisfiedLinkError + any other
            // Throwable lands here. Log + ship the skip reason so the
            // heartbeat builder counter increments.
            Log.w(TAG, "SSDP discovery failed: ${e.javaClass.simpleName}: ${e.message}")
            SsdpDiscoveryResult(emptyList(), SKIP_FFI_FAILURE)
        } finally {
            runCatching { multicastLock.release() }
                .onFailure { e -> Log.d(TAG, "MulticastLock.release failed: ${e.message}") }
        }
    }

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
     * Returns the captured-at wall-clock millis from the last completed
     * snapshot. Used by tests / probes that need to confirm the FFI
     * binding ran. Returns null when [discover] has never been called.
     *
     * Intentionally NOT thread-safe — a parallel [discover] caller may
     * see a stale value. Callers needing strict ordering should call
     * [runSsdpDiscovery] directly.
     */
    @JvmStatic
    fun lastCapturedAtMs(): Long? = lastCapturedAtMsInternal

    private const val TAG: String = "SsdpAdapter"
    @Volatile private var lastCapturedAtMsInternal: Long? = null

    /**
     * Returns the full snapshot (devices + captured_at_ms) so callers
     * who need both fields don't have to call [discover] twice. Same
     * error-degrade-to-empty behavior as [discover].
     */
    @JvmStatic
    fun snapshot(timeoutMs: Int = DEFAULT_TIMEOUT_MS): SsdpSnapshot? {
        val ms = timeoutMs.coerceAtLeast(0).toUInt()
        return try {
            val snap = runSsdpDiscovery(ms)
            lastCapturedAtMsInternal = snap.capturedAtMs.toLong()
            snap
        } catch (e: DiscoveryException) {
            Log.w(TAG, "SSDP snapshot failed: ${e.message}")
            null
        }
    }
}
