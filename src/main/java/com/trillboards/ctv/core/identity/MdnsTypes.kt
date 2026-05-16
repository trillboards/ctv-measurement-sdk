package com.trillboards.ctv.core.identity

/**
 * Legacy in-process data classes consumed by `HeartbeatPayload.kt` /
 * `ApiClient.kt`. Phase 1 of the multi-protocol device discovery rewrite
 * moved the actual mDNS implementation to the Rust-backed
 * [com.trillboards.discovery.MdnsAdapter]; this file retains ONLY the
 * value types so the heartbeat wire format stays BYTE-IDENTICAL while the
 * legacy `object MdnsDiscovery { fun discover() }` entry-point is deleted.
 *
 * Why we kept the data classes instead of using
 * [com.trillboards.discovery.MdnsAdapter.NetworkDevice] directly:
 *  - `HeartbeatPayload.discoveredNetworkDevices` is part of the wire
 *    contract — changing the type changes the import path inside the
 *    agents' codebase but NOT the on-the-wire JSON shape.
 *  - The compatibility shim's only reason to exist was the
 *    `MdnsDiscovery.discover(context, durationMs)` static dispatch.
 *    Call sites now invoke `MdnsAdapter.discover(...)` directly and
 *    translate the rust-typed snapshot into these legacy types in one
 *    place (DeviceAgentService / TrillboardsMeasurement).
 *
 * Field names mirror the legacy 3-fork `MdnsDiscovery.kt` classes so the
 * JSON shape on the wire (`discovered_network_devices`,
 * `network_device_count`) is preserved across the cutover. Any field
 * rename here also requires a heartbeat payload schema bump on the
 * server side.
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

/**
 * Per-discovery-cycle snapshot. `skipReason` is null on success and
 * non-null when the underlying [com.trillboards.discovery.MdnsAdapter]
 * bailed (catalog: `multicast_lock_failed`, `discovery_in_progress`,
 * `ffi_failure`). Mirrors `BleScanSnapshot.skipReason` (PR #4480) so the
 * server-side skip-reason classifier sees a per-screen counter for the
 * failure mode instead of indistinguishable "no devices" rows.
 */
public data class MdnsSnapshot(
    val devices: List<NetworkDevice>,
    val deviceCount: Int,
    val discoveryDurationMs: Long,
    val discoveryTimestampMs: Long,
    val skipReason: String? = null
)

/**
 * Translate a [com.trillboards.discovery.MdnsSnapshot] (the Rust-typed
 * adapter snapshot) into the legacy [MdnsSnapshot] above.
 *
 * Phase 1 cutover: the 3-fork `MdnsDiscovery.kt` shim is gone. Call sites
 * invoke `MdnsAdapter.discover(...)` directly and use this single
 * function to translate. Keeping the translation here means there is
 * exactly one place where the legacy field-name mapping lives, so a
 * future server-side schema bump only edits this file.
 */
public fun com.trillboards.discovery.MdnsSnapshot.toLegacy(): MdnsSnapshot =
    MdnsSnapshot(
        devices = devices.map { d ->
            NetworkDevice(
                serviceType = d.serviceType,
                instanceName = d.instanceName,
                host = d.host,
                port = d.port,
                mdnsModel = d.mdnsModel,
                mdnsVendor = d.mdnsVendor,
                mdnsSoftwareVersion = d.mdnsSoftwareVersion
            )
        },
        deviceCount = deviceCount,
        discoveryDurationMs = discoveryDurationMs,
        discoveryTimestampMs = discoveryTimestampMs,
        skipReason = skipReason
    )
