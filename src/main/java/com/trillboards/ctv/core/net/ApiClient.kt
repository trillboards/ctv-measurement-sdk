package com.trillboards.ctv.core.net

import android.util.Log
import com.trillboards.ctv.core.AgentConfig
import com.trillboards.ctv.core.models.DeviceCapabilityPayload
import com.trillboards.ctv.core.models.HeartbeatPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * API client for agent-core-lite.
 * This version does NOT include audience metrics or Gemini analysis methods
 * since the lite version has no ML capabilities.
 */
class ApiClient(
    private val config: AgentConfig,
    private val deviceTokenProvider: () -> String? = { null }
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ScreenResolution(
        val screenId: String,
        val venueType: String?,
        val deviceToken: String?
    )

    private fun currentDeviceToken(): String? =
        deviceTokenProvider().orEmpty().trim().takeIf { it.isNotEmpty() }

    private fun authRequestBuilder(url: String): Request.Builder {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val builder = Request.Builder()
            .url(url)
            .header("X-Device-Timestamp", timestamp)
            .header("X-Device-Nonce", nonce)

        currentDeviceToken()?.let { token ->
            builder.header("X-Device-Token", token)
            builder.header("X-Trillboard-Device-Token", token)
        }

        return builder
    }

    suspend fun fetchScreenId(fingerprint: String): String? = withContext(Dispatchers.IO) {
        fetchScreenResolution(fingerprint)?.screenId
    }

    suspend fun fetchScreenResolution(fingerprint: String): ScreenResolution? = withContext(Dispatchers.IO) {
        runCatching {
            val request = authRequestBuilder("${config.apiBaseUrl}/v2/earner/check-screen/$fingerprint")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                if (!json.optBoolean("status")) return@use null
                val data = json.optJSONObject("data") ?: return@use null
                val screenId = data.optString("_id", data.optString("screenId", null))
                if (screenId.isNullOrEmpty()) return@use null
                val venueType = data.optString("venue_type", "").ifEmpty { null }
                val deviceToken = data.optString("device_token", "").ifEmpty { null }
                ScreenResolution(
                    screenId = screenId,
                    venueType = venueType,
                    deviceToken = deviceToken
                )
            }
        }.onFailure { Log.w(TAG, "fetchScreenResolution failed", it) }.getOrNull()
    }

    suspend fun sendHeartbeat(payload: HeartbeatPayload): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val json = payloadToJson(payload)
            val request = authRequestBuilder("${config.apiBaseUrl}${config.heartbeatPath}")
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JSONObject(body)
            }
        }.onFailure { Log.w(TAG, "sendHeartbeat failed", it) }.getOrNull()
    }

    /**
     * Result of [sendHeartbeatWithResult]. Mirrors the trillboards-api-client
     * `TrillboardsError.retryAfterSeconds` parse so the public Measurement SDK
     * can implement Retry-After-aware backoff without forking OkHttp wiring.
     *
     * - [SUCCESS] — 2xx response, [body] populated with parsed JSON.
     * - [RATE_LIMITED] — 429 response; [retryAfterSeconds] populated when the
     *   server emits a `Retry-After:` header (in seconds; HTTP-date format
     *   coerced via [java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME]).
     * - [PERMANENT_FAILURE] — any other non-2xx response (4xx ≠ 429, 5xx after
     *   transport retry exhausted). Caller should NOT retry these.
     * - [TRANSPORT_FAILURE] — OkHttp threw (IOException, SSL, etc.). Caller
     *   may retry with backoff.
     */
    sealed class HeartbeatResult {
        data class Success(val body: JSONObject) : HeartbeatResult()
        data class RateLimited(val retryAfterSeconds: Long?) : HeartbeatResult()
        data class PermanentFailure(val httpStatus: Int) : HeartbeatResult()
        data class TransportFailure(val cause: Throwable) : HeartbeatResult()
    }

    /**
     * 429-aware variant of [sendHeartbeat]. Returns a structured result so
     * callers (e.g. the public Measurement SDK) can implement Retry-After-
     * aware backoff without forking the OkHttp + auth wiring.
     *
     * NOT a behavior change for [sendHeartbeat] — that method still returns
     * `JSONObject?` for back-compat with the tablet-agent fleet's heartbeat
     * scheduler. Future flavors should migrate to this method.
     */
    suspend fun sendHeartbeatWithResult(payload: HeartbeatPayload): HeartbeatResult =
        withContext(Dispatchers.IO) {
            try {
                val json = payloadToJson(payload)
                val request = authRequestBuilder("${config.apiBaseUrl}${config.heartbeatPath}")
                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body?.string()
                            if (body.isNullOrBlank()) {
                                // 2xx with empty body is still success; server-side
                                // some endpoints return 204 No Content.
                                HeartbeatResult.Success(JSONObject())
                            } else {
                                HeartbeatResult.Success(JSONObject(body))
                            }
                        }
                        response.code == 429 -> {
                            val raw = response.header("Retry-After")
                            val seconds = parseRetryAfter(raw)
                            Log.w(TAG, "sendHeartbeatWithResult got 429; Retry-After=$raw → ${seconds}s")
                            HeartbeatResult.RateLimited(seconds)
                        }
                        else -> {
                            Log.w(TAG, "sendHeartbeatWithResult got ${response.code}")
                            HeartbeatResult.PermanentFailure(response.code)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendHeartbeatWithResult transport failure", t)
                HeartbeatResult.TransportFailure(t)
            }
        }

    /**
     * Parse the `Retry-After` HTTP header. The RFC permits two forms:
     *   - A non-negative integer (number of seconds).
     *   - An HTTP-date (RFC 1123).
     *
     * Returns null when the header is absent, blank, or unparseable. Returns
     * the delay in seconds otherwise (HTTP-date is converted to seconds from
     * the local clock).
     */
    private fun parseRetryAfter(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return try {
            val instant = java.time.ZonedDateTime
                .parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
            val deltaMs = instant.toEpochMilli() - System.currentTimeMillis()
            (deltaMs / 1000L).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pure-domain HeartbeatPayload → JSON serializer.
     *
     * Exposed for unit testing the wire contract — JSON-roundtrip tests build
     * a payload, call this helper, and assert each new field is present.
     */
    fun payloadToJson(payload: HeartbeatPayload): JSONObject = JSONObject().apply {
        put("schema_version", payload.schemaVersion)
        put("fingerprint", payload.fingerprint)
        payload.screenId?.let { put("screenId", it) }
        put("status", payload.status)
        put("metadata", JSONObject().apply {
            payload.metadata.forEach { (key, value) -> if (value != null) put(key, value) }
        })
        put("capabilities", capabilitiesJson(payload.capabilities))
        payload.advertisingId?.let { put("advertising_id", it) }
        payload.advertisingIdType?.let { put("advertising_id_type", it) }
        payload.limitAdTracking?.let { put("limit_ad_tracking", it) }
        payload.wifiBssidHash?.let { put("wifi_bssid_hash", it) }
        payload.wifiSsidHash?.let { put("wifi_ssid_hash", it) }
        payload.gatewayIpHash?.let { put("gateway_ip_hash", it) }
        payload.nearbyWifiNetworks?.let { networks ->
            put("nearby_wifi_networks", org.json.JSONArray().apply {
                for (n in networks) {
                    put(JSONObject().apply {
                        put("raw_bssid", n.rawBssid)
                        put("signal_dbm", n.signalStrengthDbm)
                        put("frequency_mhz", n.frequencyMhz)
                        n.channelWidthMhz?.let { put("channel_width_mhz", it) }
                        // Codex P2 (PR #5753): mirror agent-core's wire serialization
                        // for the 3 Cut-E.4 enrichment fields. Without these lines,
                        // agent-core-lite fleet devices collect the values locally
                        // (WifiScanCollector now populates them) but never ship them
                        // to the server — the new CH columns stay empty for that
                        // fleet slice.
                        put("capabilities", n.capabilities)
                        put("is_80211mc_responder", n.is80211mcResponder)
                        n.vendorOui2Byte?.let { put("vendor_oui_2byte", it) }
                    })
                }
            })
        }
        payload.wifiNetworkCount?.let { put("wifi_network_count", it) }
        payload.wifiEnvironment?.let { env ->
            put("wifi_environment", JSONObject().apply {
                put("network_count", env.networkCount)
                put("connected_signal_dbm", env.connectedSignalDbm)
                put("connected_frequency_mhz", env.connectedFrequencyMhz)
                env.connectedChannelWidthMhz?.let { put("connected_channel_width_mhz", it) }
                env.connectedLinkSpeedMbps?.let { put("connected_link_speed_mbps", it) }
                put("frequency_band", env.frequencyBand)
                put("channel_congestion_ratio", env.channelCongestionRatio.toDouble())
                put("rssi_variance", env.rssiVariance.toDouble())
                put("unique_bssid_count", env.uniqueBssidCount)
                put("median_signal_dbm", env.medianSignalDbm)
                put("signal_spread_dbm", env.signalSpreadDbm)
                put("scan_timestamp_ms", env.scanTimestampMs)
            })
        }
        payload.nearbyBleDevices?.let { devices ->
            put("nearby_ble_devices", org.json.JSONArray().apply {
                for (d in devices) {
                    put(JSONObject().apply {
                        put("raw_address", d.rawAddress)
                        put("rssi", d.rssi)
                        put("device_type", d.deviceType)
                        d.manufacturerCompanyId?.let { put("manufacturer_company_id", it) }
                        d.appleContinuitySubtype?.let { put("apple_continuity_subtype", it) }
                        d.stableManufacturerPayloadHex?.let { put("stable_manufacturer_payload_hex", it) }
                        d.serviceUuids?.let { put("service_uuids", org.json.JSONArray(it)) }
                        d.txPowerDbm?.let { put("tx_power_dbm", it) }
                        d.ibeaconUuid?.let { put("ibeacon_uuid", it) }
                        d.ibeaconMajor?.let { put("ibeacon_major", it) }
                        d.ibeaconMinor?.let { put("ibeacon_minor", it) }
                        // Venue-insights PR 7 (E.2/E.3): address type + paired
                        // state. Mirror of agent-core fork.
                        d.addrType?.let { put("addr_type", it) }
                        d.isPaired?.let { put("is_paired", it) }
                    })
                }
            })
        }
        payload.bleDeviceCount?.let { put("ble_device_count", it) }
        payload.bleGattDevices?.let { gattDevices ->
            put("ble_gatt_devices", org.json.JSONArray().apply {
                for (g in gattDevices) {
                    put(JSONObject().apply {
                        put("raw_address", g.rawAddress)
                        g.manufacturer?.let { put("manufacturer", it) }
                        g.modelNumber?.let { put("model_number", it) }
                        g.hardwareRevision?.let { put("hardware_revision", it) }
                        g.firmwareRevision?.let { put("firmware_revision", it) }
                    })
                }
            })
        }
        payload.discoveredNetworkDevices?.let { devices ->
            put("discovered_network_devices", org.json.JSONArray().apply {
                for (d in devices) {
                    put(JSONObject().apply {
                        put("service_type", d.serviceType)
                        put("instance_name", d.instanceName)
                        d.host?.let { put("host", it) }
                        d.port?.let { put("port", it) }
                        d.mdnsModel?.let { put("mdns_model", it) }
                        d.mdnsVendor?.let { put("mdns_vendor", it) }
                        d.mdnsSoftwareVersion?.let { put("mdns_software_version", it) }
                    })
                }
            })
        }
        payload.networkDeviceCount?.let { put("network_device_count", it) }
        // Phase 2 of the multi-protocol discovery rewrite — SSDP / UPnP M-SEARCH.
        // Captures TVs / NAS / routers / smart-home hubs that don't broadcast
        // on mDNS. One JSONObject per unique LOCATION with snake_case keys
        // matching the existing wire-format convention. Server-side
        // `SignalIngestService.normalize()` HMACs friendlyName / manufacturer /
        // modelName / UDN under the daily pepper. Wire-additive — older agents
        // that don't include `ssdp_devices` keep the existing row contract.
        payload.ssdpDevices?.let { devices ->
            put("ssdp_devices", org.json.JSONArray().apply {
                for (d in devices) {
                    put(JSONObject().apply {
                        put("location", d.location)
                        d.server?.let { put("server", it) }
                        d.friendlyName?.let { put("friendly_name", it) }
                        d.manufacturer?.let { put("manufacturer", it) }
                        d.modelName?.let { put("model_name", it) }
                        d.udn?.let { put("udn", it) }
                        put("st", d.st)
                    })
                }
            })
        }

        // ── Phase 3 — ARP cache rows ──
        // Each entry is a Map<String, Any?> from the Rust ArpEntry binding
        // (`ip`, `mac`, `iface`, `ouiVendor`). Re-emit snake_case at the
        // wire layer to match the server's canonical contract.
        payload.arpDevices?.let { entries ->
            put("arp_devices", org.json.JSONArray().apply {
                for (e in entries) {
                    put(JSONObject().apply {
                        e["ip"]?.let { put("ip", it.toString()) }
                        e["mac"]?.let { put("mac", it.toString()) }
                        e["iface"]?.let { put("iface", it.toString()) }
                        val vendor = e["oui_vendor"] ?: e["ouiVendor"]
                        vendor?.let { put("oui_vendor", it.toString()) }
                    })
                }
            })
        }

        // ── Phase 4 — HTTP probe rows ──
        payload.httpProbes?.let { probes ->
            put("http_probes", org.json.JSONArray().apply {
                for (p in probes) {
                    put(JSONObject().apply {
                        p["host"]?.let { put("host", it.toString()) }
                        p["port"]?.let { put("port", (it as? Number)?.toInt() ?: it.toString().toInt()) }
                        p["server"]?.let { put("server", it.toString()) }
                    })
                }
            })
        }

        payload.ambientLightLux?.let { put("ambient_light_lux", it.toDouble()) }
        payload.barometerPressure?.let { put("barometer_pressure", it.toDouble()) }
        payload.latitude?.let { put("latitude", it) }
        payload.longitude?.let { put("longitude", it) }
        payload.accuracyMeters?.let { put("accuracy_meters", it.toDouble()) }
        payload.locationSource?.let { put("location_source", it) }
        payload.mdmCompliance?.let { compliance ->
            put("mdm_compliance", JSONObject().apply {
                compliance.forEach { (key, value) -> if (value != null) put(key, value) }
            })
        }
        put("mdm_enrolled", payload.mdmEnrolled)
        payload.mdmOrgId?.let { put("mdm_org_id", it) }
        payload.csiSnapshot?.let { csi ->
            put("csi_snapshot", JSONObject().apply {
                put("node_id", csi.nodeId)
                put("occupant_count", csi.occupantCount)
                put("motion_score", csi.motionScore.toDouble())
                put("signal_quality", csi.signalQuality.toDouble())
                put("subcarrier_count", csi.subcarrierCount)
                put("capture_rate_hz", csi.captureRateHz.toDouble())
                put("avg_rssi_dbm", csi.avgRssiDbm)
                put("frames_processed", csi.framesProcessed)
                put("frames_dropped", csi.framesDropped)
                put("window_start_ms", csi.windowStartMs)
                put("window_end_ms", csi.windowEndMs)
                put("hardware_type", csi.hardwareType)
            })
        }
        payload.csiNodeCount?.let { put("csi_node_count", it) }
        payload.nativeSensorSnapshot?.let { sensor ->
            // Wire contract (post 2026-05-01 redesign — CH migration 059):
            // ambient_light_lux + barometer_pressure_hpa only. The four
            // dropped fields (magnetic_flux_events_per_min,
            // proximity_events_per_min, footstep_count, gait_signature_hash)
            // were retired with their CH columns — agent stops sampling
            // accelerometer / gyroscope / magnetometer / proximity entirely.
            // Server-side `SignalIngestService.normalize()` hoists these
            // two values onto every emitted BLE/WiFi/mDNS row instead of
            // creating a dedicated source='native_sensor' row.
            put("native_sensor_snapshot", JSONObject().apply {
                sensor.ambientLightLux?.let { put("ambient_light_lux", it.toDouble()) }
                sensor.barometerPressureHpa?.let { put("barometer_pressure_hpa", it.toDouble()) }
            })
        }

        // Phase 0 of redis-stream-scale-fix — structured skip-reason channel.
        // Per-source skip-reason counts emitted as `skip_reason_counts` on
        // the wire. Server-side `SignalIngestService.normalize()` accepts
        // the snake_case key and emits per-(source,reason) CW counters.
        payload.skipReasonCounts?.takeIf { it.isNotEmpty() }?.let { perSource ->
            put("skip_reason_counts", JSONObject().apply {
                for ((source, reasons) in perSource) {
                    put(source, JSONObject().apply {
                        for ((reason, count) in reasons) {
                            put(reason, count)
                        }
                    })
                }
            })
        }
    }

    suspend fun sendCommandAck(
        commandId: String,
        status: String,
        result: JSONObject = JSONObject(),
        screenId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (commandId.isBlank()) return@withContext false

        runCatching {
            val json = JSONObject().apply {
                put("command_id", commandId)
                put("status", status)
                put("result", result)
                screenId?.takeIf { it.isNotBlank() }?.let { put("screen_id", it) }
            }
            val request = authRequestBuilder("${config.apiBaseUrl}/openrtb/v1/command-ack")
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "sendCommandAck failed: ${response.code}")
                }
                response.isSuccessful
            }
        }.onFailure { Log.w(TAG, "sendCommandAck failed", it) }.getOrDefault(false)
    }

    private fun capabilitiesJson(capabilities: DeviceCapabilityPayload): JSONObject = JSONObject().apply {
        put("powerControl", JSONObject().apply {
            put("cec", capabilities.powerControl.cec)
            put("wol", capabilities.powerControl.wol)
            put("softBlackout", capabilities.powerControl.softBlackout)
        })
        put("inputControl", JSONObject().apply {
            put("hdmi", capabilities.inputControl.hdmi)
            put("appSwitch", capabilities.inputControl.appSwitch)
        })
        put("audioControl", JSONObject().apply {
            put("volume", capabilities.audioControl.volume)
            put("mute", capabilities.audioControl.mute)
        })
        put("environmentSensors", JSONObject().apply {
            put("ambientLight", capabilities.environmentSensors.ambientLight)
            put("temperature", capabilities.environmentSensors.temperature)
        })
        put("audienceSensing", JSONObject().apply {
            put("cameraAvailable", capabilities.audienceSensing.cameraAvailable)
            put("cameraType", capabilities.audienceSensing.cameraType)
            put("cameraCount", capabilities.audienceSensing.cameraCount)
            put("microphoneAvailable", capabilities.audienceSensing.microphoneAvailable)
            put("sensingMode", capabilities.audienceSensing.sensingMode)
        })
        // MDM Management Capabilities
        put("managementCapabilities", JSONObject().apply {
            put("restart", capabilities.managementCapabilities.restart)
            put("reboot", capabilities.managementCapabilities.reboot)
            put("screenshot", capabilities.managementCapabilities.screenshot)
            put("clearCache", capabilities.managementCapabilities.clearCache)
            put("kioskMode", capabilities.managementCapabilities.kioskMode)
            put("installApk", capabilities.managementCapabilities.installApk)
            put("wipe", capabilities.managementCapabilities.wipe)
        })
        // ML hardware capabilities from HardwareManifestLite.
        //
        // Phase 2 prereq PR 2 (audit 2026-05-03): emit the cpuAbi / osApiLevel /
        // screen geometry fields on lite payloads too — without these, the server
        // typed columns stay NULL for the entire lite cohort and the fleet
        // inventory completeness target is missed. Field names match the full
        // agent's `ApiClient.capabilitiesJson` so the server reads the same
        // shape regardless of agent flavor.
        put("mlCapabilities", JSONObject().apply {
            put("chipsetVendor", capabilities.mlCapabilities.chipsetVendor)
            put("chipsetName", capabilities.mlCapabilities.chipsetName)
            put("totalRamMb", capabilities.mlCapabilities.totalRamMb)
            put("availableRamMb", capabilities.mlCapabilities.availableRamMb)
            capabilities.mlCapabilities.gpuName?.let { put("gpuName", it) } ?: put("gpuName", JSONObject.NULL)
            put("hasNpu", capabilities.mlCapabilities.hasNpu)
            capabilities.mlCapabilities.npuName?.let { put("npuName", it) } ?: put("npuName", JSONObject.NULL)
            put("gpuDelegateSupported", capabilities.mlCapabilities.gpuDelegateSupported)
            put("nnapiSupported", capabilities.mlCapabilities.nnapiSupported)
            put("recommendedModelTier", capabilities.mlCapabilities.recommendedModelTier)
            put("maxVlmSizeMb", capabilities.mlCapabilities.maxVlmSizeMb)
            // Phase 2 prereq PR 2 — emit the 5 typed fields. Server-side
            // `services/deviceCommandSupport.js:buildCapabilityUpdateDoc` reads
            // these defensively and only writes the typed column when the wire
            // payload carries a non-null/non-zero value, so emitting 0 from a
            // default-constructed MLCapabilities() (e.g. between service start
            // and first HardwareManifestLite.detect()) does not overwrite a
            // previously-real value via COALESCE.
            //
            // cpuAbi: emit JSONObject.NULL on the empty-array edge case;
            // otherwise emit the string directly. Server tolerates both keys.
            capabilities.mlCapabilities.cpuAbi?.let { put("cpuAbi", it) } ?: put("cpuAbi", JSONObject.NULL)
            put("osApiLevel", capabilities.mlCapabilities.osApiLevel)
            put("screenWidthPx", capabilities.mlCapabilities.screenWidthPx)
            put("screenHeightPx", capabilities.mlCapabilities.screenHeightPx)
            put("densityDpi", capabilities.mlCapabilities.densityDpi)
        })
        // WiFi CSI sensing hardware capabilities
        put("wifiCsiSensing", JSONObject().apply {
            put("csiAvailable", capabilities.wifiCsiSensing.csiAvailable)
            put("csiNodeCount", capabilities.wifiCsiSensing.csiNodeCount)
            put("csiHardwareType", capabilities.wifiCsiSensing.csiHardwareType)
        })
        put("isDeviceOwner", capabilities.isDeviceOwner)
        put("agentType", capabilities.agentType)
        put("agentVersion", capabilities.agentVersion)
        put("capabilitiesVersion", capabilities.capabilitiesVersion)
    }

    /**
     * Response from screenshot upload URL endpoint
     */
    data class ScreenshotUploadUrlResponse(
        val uploadUrl: String,
        val cdnUrl: String,
        val expiresIn: Int
    )

    /**
     * Get a presigned URL for uploading a screenshot to S3.
     * Called when processing device.screenshot MDM command.
     *
     * @param fingerprint Device fingerprint for authentication
     * @return ScreenshotUploadUrlResponse with upload and CDN URLs, or null on failure
     */
    suspend fun getScreenshotUploadUrl(fingerprint: String): ScreenshotUploadUrlResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject().apply {
                    put("fingerprint", fingerprint)
                }
                val request = authRequestBuilder("${config.apiBaseUrl}/v2/earner/screenshot-upload-url")
                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "getScreenshotUploadUrl failed: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string() ?: return@use null
                    val data = JSONObject(body)
                    val uploadUrl = data.optString("uploadUrl", "").takeIf { it.isNotBlank() } ?: return@use null
                    val cdnUrl = data.optString("cdnUrl", "").takeIf { it.isNotBlank() } ?: return@use null
                    ScreenshotUploadUrlResponse(
                        uploadUrl = uploadUrl,
                        cdnUrl = cdnUrl,
                        expiresIn = data.optInt("expiresIn", 300)
                    )
                }
            }.onFailure { Log.w(TAG, "getScreenshotUploadUrl failed", it) }.getOrNull()
        }

    /**
     * APK manifest entry for OTA updates
     */
    data class ApkManifestEntry(
        val version: String,
        val versionCode: Int,
        val url: String,
        val minSdkVersion: Int,
        val changelog: String
    )

    /**
     * Fetch APK manifest to check for available updates.
     * Called when processing device.checkUpdate MDM command.
     *
     * @param agentType Agent type (fire-tv-agent, android-tv-agent, tablet-agent, tablet-agent-lite)
     * @return ApkManifestEntry with version info and download URL, or null on failure
     */
    suspend fun fetchApkManifest(agentType: String): ApkManifestEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authRequestBuilder("${config.apiBaseUrl}/v2/earner/apk-manifest?agent=$agentType")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "fetchApkManifest failed: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string() ?: return@use null
                    val data = JSONObject(body)
                    ApkManifestEntry(
                        version = data.optString("version", ""),
                        versionCode = data.optInt("versionCode", 0),
                        url = data.optString("url", ""),
                        minSdkVersion = data.optInt("minSdkVersion", 21),
                        changelog = data.optString("changelog", "")
                    )
                }
            }.onFailure { Log.w(TAG, "fetchApkManifest failed", it) }.getOrNull()
        }

    suspend fun enrollDevice(orgId: String, enrollmentCode: String, screenMongoId: String, fingerprint: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject().apply {
                    put("enrollmentCode", enrollmentCode)
                    put("screenMongoId", screenMongoId)
                    put("deviceFingerprint", fingerprint)
                    put("capabilities", JSONObject())
                }
                val request = authRequestBuilder("${config.apiBaseUrl}/v2/mdm/enrollment/device")
                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "enrollDevice failed: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string() ?: return@use null
                    JSONObject(body)
                }
            }.onFailure { Log.w(TAG, "enrollDevice failed", it) }.getOrNull()
        }

    suspend fun fetchDevicePolicies(screenMongoId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authRequestBuilder("${config.apiBaseUrl}/v2/mdm/device/$screenMongoId/policies")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    JSONObject(body)
                }
            }.onFailure { Log.w(TAG, "fetchDevicePolicies failed", it) }.getOrNull()
        }

    companion object {
        private const val TAG = "AgentCoreLiteApiClient"
    }
}
