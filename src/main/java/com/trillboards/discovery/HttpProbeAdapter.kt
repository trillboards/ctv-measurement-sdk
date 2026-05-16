package com.trillboards.discovery

/**
 * Outcome of a single [HttpProbeAdapter.probeWithReason] call.
 *
 * Either we got a sanitized `Server:` header back (success: `server!=null`,
 * `skipReason==null`), or the probe failed for a reason we lump into
 * `SKIP_NO_RESPONSE` (success: `server==null`, `skipReason==SKIP_NO_RESPONSE`).
 *
 * The Rust `probe_http()` surface only returns `Option<String>` — it
 * cannot tell us WHY a probe came back empty (timeout vs RST vs
 * missing-header vs PII-rejected). Cracking the UDL to add structured
 * error variants would be invasive (touches the .udl, regenerates
 * bindings, bumps the per-ABI .so size), so we deliberately bucket
 * every empty result as `no_response` for now. That's still leaps
 * better than the silent zero we ship today, and the per-source counter
 * lets us diff "all probes empty for screen X" from "no probes at all
 * for screen X" — which was the entire ask of the BLE silent-failure
 * postmortem.
 *
 * TODO-BY-2026-06-01: extend the Rust crate's UDL to surface
 * `ProbeError::Timeout` / `ProbeError::ConnectRefused` / `ProbeError::NoHeader`
 * variants so we can split the `no_response` bucket. Tracked in
 * `deferred-work.yaml` under `ctv-http-probe-skip-reason-granularity`.
 */
data class HttpProbeResult(
    val server: String?,
    val skipReason: String? = null
)

/**
 * Phase 4 — Thin Kotlin wrapper around the Rust `probe_http()` UniFFI binding.
 *
 * The Rust crate performs a synchronous HTTP HEAD request against
 * `http://<host>:<port>/` and returns the value of the `Server:` response
 * header, or null on:
 *   - connect failure (host down, port closed, RST),
 *   - request timeout,
 *   - missing `Server:` header,
 *   - PII-shaped header content (defensive — see `http_probe::sanitize_server_header`).
 *
 * The 1.5 s default timeout is enough for an embedded HTTP daemon on local
 * LAN to reply, but short enough that a hung host does NOT block the
 * heartbeat collector for more than a couple seconds.
 *
 * **Why so thin?** Same rationale as `ArpAdapter`: the parsing, sanitization,
 * and timeout policy live in Rust where they can be unit-tested without
 * spinning up a JVM. Kotlin just exposes the call with a sensible default
 * timeout and converts any FFI failure to a null result (consistent with
 * the Rust contract — the underlying call already returns `Option<String>`).
 */
object HttpProbeAdapter {

    /** Default per-probe timeout (connect + read). 1.5 s. */
    const val DEFAULT_TIMEOUT_MS: UInt = 1_500u

    // Skip-reason enum — kept as plain strings so the heartbeat-payload
    // pass-through stays primitive and the server-side classifier can
    // group on them without a Kotlin/JS shared schema dance. Mirrors
    // BleBeaconScanner / MdnsAdapter / SsdpAdapter SKIP_* constants.
    //
    // Only one bucket today — see [HttpProbeResult] docstring for why.
    const val SKIP_NO_RESPONSE: String = "no_response"
    const val SKIP_FFI_FAILURE: String = "ffi_failure"

    /**
     * Perform an HTTP HEAD probe and return the `Server:` header if present,
     * or `null` on any failure.
     *
     * Suspends nothing — Rust call is blocking. Caller is responsible for
     * dispatching to a non-main thread (e.g. via `Dispatchers.IO`).
     */
    fun probe(
        host: String,
        port: UShort,
        timeoutMs: UInt = DEFAULT_TIMEOUT_MS,
    ): String? = runCatching {
        // `probeHttp` is the top-level UniFFI-generated function in the
        // same `com.trillboards.discovery` package.
        probeHttp(host, port, timeoutMs)
    }.getOrNull()

    /**
     * Skip-reason-tagged variant of [probe]. Mirror of `BleScanSnapshot`,
     * `MdnsSnapshot`, and `SsdpDiscoveryResult` `skipReason` fields — every
     * recoverable failure ships its reason upward so the server-side
     * classifier can fire CW alarms on per-fleet skip-reason counters
     * instead of waiting for "absent rows" to be diagnosed by hand (the
     * silent-failure mode that lost 27h of BLE telemetry).
     *
     * Reasons (today — see [HttpProbeResult] docstring for the granularity
     * roadmap):
     *   - `no_response` — Rust `probe_http()` returned None. Could be
     *     timeout, connect refused, missing `Server:` header, or
     *     PII-shaped header rejected at the FFI boundary. We bucket all
     *     of these into one counter for now.
     *   - `ffi_failure` — `probeHttp` threw an UnsatisfiedLinkError or
     *     similar. The .so didn't bundle, the JNI call deadlocked, etc.
     *     Distinct from `no_response` because it's a Kotlin-side failure
     *     mode, not a network observation.
     *
     * Success: `server!=null`, `skipReason==null`.
     */
    fun probeWithReason(
        host: String,
        port: UShort,
        timeoutMs: UInt = DEFAULT_TIMEOUT_MS,
    ): HttpProbeResult = runCatching {
        val server = probeHttp(host, port, timeoutMs)
        if (server != null) {
            HttpProbeResult(server = server, skipReason = null)
        } else {
            HttpProbeResult(server = null, skipReason = SKIP_NO_RESPONSE)
        }
    }.getOrElse {
        // UnsatisfiedLinkError / generic Throwable. Distinguish from a
        // benign empty-response so the dashboard can tell "the .so isn't
        // loaded on this fleet" from "every host on this LAN is silent."
        HttpProbeResult(server = null, skipReason = SKIP_FFI_FAILURE)
    }
}
