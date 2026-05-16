package com.trillboards.ctv.core.models

data class DeviceCapabilityPayload(
    val powerControl: PowerControl = PowerControl(),
    val inputControl: InputControl = InputControl(),
    val audioControl: AudioControl = AudioControl(),
    val environmentSensors: EnvironmentSensors = EnvironmentSensors(),
    val audienceSensing: AudienceSensing = AudienceSensing(),
    // MDM capabilities
    val managementCapabilities: ManagementCapabilities = ManagementCapabilities(),
    // ML hardware capabilities from HardwareManifest
    val mlCapabilities: MLCapabilities = MLCapabilities(),
    // WiFi CSI sensing hardware capabilities
    val wifiCsiSensing: WifiCsiSensing = WifiCsiSensing(),
    val isDeviceOwner: Boolean = false,
    val agentType: String = "unknown",  // "fire-tv", "android-tv", "tablet", "tablet-lite"
    val agentVersion: String = "",
    val capabilitiesVersion: Int = 1
) {
    data class PowerControl(
        val cec: Boolean = false,
        val wol: Boolean = false,
        val softBlackout: Boolean = true
    )

    data class InputControl(
        val hdmi: Boolean = false,
        val appSwitch: Boolean = false
    )

    data class AudioControl(
        val volume: Boolean = true,
        val mute: Boolean = true
    )

    data class EnvironmentSensors(
        val ambientLight: Boolean = false,
        val temperature: Boolean = false
    )

    /**
     * Audience sensing capabilities.
     * For tablet-agent-lite, all sensing is disabled (no ML dependencies).
     */
    data class AudienceSensing(
        val cameraAvailable: Boolean = false,
        val cameraType: String = "none",  // "usb", "internal", "none"
        val cameraCount: Int = 0,
        val microphoneAvailable: Boolean = false,
        val sensingMode: String = "NONE"  // Always "NONE" for lite - no ML
    )

    /**
     * MDM/Device Management capabilities.
     * Reports what management commands the device supports.
     */
    data class ManagementCapabilities(
        val restart: Boolean = true,        // All agents support app restart
        val reboot: Boolean = false,        // Device Owner only
        val screenshot: Boolean = true,     // All agents
        val clearCache: Boolean = true,     // All agents
        val kioskMode: Boolean = false,     // Device Owner or soft kiosk (Fire TV)
        val installApk: Boolean = false,    // Device Owner only for silent install
        val wipe: Boolean = false           // Device Owner only
    )

    /**
     * ML hardware capabilities from HardwareManifestLite.
     * Reports GPU/NPU/chipset info for fleet-wide model deployment planning.
     *
     * Phase 2 prereq PR 2 (audit `audit-2026-05-03-device-telemetry-deep-inventory.md`):
     * The cpuAbi / osApiLevel / screenWidthPx / screenHeightPx / densityDpi
     * fields are the new wire additions sourced from
     * `HardwareManifestLite.detect()`. Mirrors the full agent's
     * `DeviceCapabilityPayload.MLCapabilities` field-for-field so the server
     * parser does NOT have to fork between full and lite — the typed columns
     * landed in migration 20260503082254 read snake_case OR camelCase from a
     * single payload shape. All five fields default to 0 / null so a
     * default-ctor `MLCapabilities()` (e.g. before `HardwareManifestLite.detect()`
     * has finished or in test fixtures) stays compatible without surprising
     * column writes.
     */
    data class MLCapabilities(
        val chipsetVendor: String = "unknown",
        val chipsetName: String = "",
        val totalRamMb: Int = 0,
        val availableRamMb: Int = 0,
        val gpuName: String? = null,
        val hasNpu: Boolean = false,
        val npuName: String? = null,
        val gpuDelegateSupported: Boolean = false,
        val nnapiSupported: Boolean = false,
        val recommendedModelTier: String = "STANDARD",
        val maxVlmSizeMb: Int = 0,
        // Phase 2 prereq PR 2 — additive only, default-safe
        val cpuAbi: String? = null,
        val osApiLevel: Int = 0,
        val screenWidthPx: Int = 0,
        val screenHeightPx: Int = 0,
        val densityDpi: Int = 0
    )

    /**
     * WiFi CSI sensing hardware capabilities.
     * Reports whether ESP32-S3 CSI nodes are available on the local network.
     */
    data class WifiCsiSensing(
        val csiAvailable: Boolean = false,
        val csiNodeCount: Int = 0,
        val csiHardwareType: String = "none"  // "esp32-s3", "none"
    )
}
