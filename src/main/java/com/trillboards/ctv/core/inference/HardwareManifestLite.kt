package com.trillboards.ctv.core.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import com.trillboards.ctv.core.models.DeviceCapabilityPayload
import org.json.JSONObject

/**
 * Minimal hardware manifest for `agent-core-lite`.
 *
 * Lite agents have NO ML inference dependencies (no MediaPipe, TFLite, NNAPI usage),
 * so the only field the fleet planner cares about is **available RAM** — that's what
 * decides whether a device is FLOOR / STANDARD / PREMIUM tier and what (if any) future
 * VLM model size it could host.
 *
 * Why the parallel data class?
 * --------------------------
 * `agent-core-lite` cannot depend on `agent-core` (the full module pulls in
 * MediaPipe, TFLite, MLKit, CameraX — incompatible with the lite SDK floor of 24).
 * So we mirror the **subset** of `HardwareManifest` we actually need, with the SAME
 * companion-mapping into `DeviceCapabilityPayload.MLCapabilities` so the server
 * parser is identical for full and lite payloads.
 *
 * Detection scope (lite):
 * - chipset vendor / name: from `Build.SOC_MANUFACTURER` (API 31+) or
 *   `Build.HARDWARE` / `Build.BOARD` heuristics.
 * - totalRamMb / availableRamMb: from `ActivityManager.MemoryInfo`.
 * - recommendedModelTier / maxVlmSizeMb: derived from RAM only.
 * - cpuAbi / osApiLevel / display geometry: lightweight Build / DisplayMetrics
 *   probes — no ML dependency, but the server now relies on these typed columns
 *   for the cross-flavor fleet inventory (see Phase 2 prereq, audit
 *   `audit-2026-05-03-device-telemetry-deep-inventory.md`).
 * - GPU / NPU / DSP / NNAPI / GPU delegate: NOT probed — left at safe defaults.
 *
 * Lite agents do not run inference, so reporting GPU/NPU here would be
 * misleading. The MLCapabilities row this maps into uses default `false` /
 * `null` for those fields.
 *
 * Server contract:
 * `toMlCapabilities()` produces the SAME `DeviceCapabilityPayload.MLCapabilities`
 * shape the full agent emits, so the server-side parser does not have to
 * branch on `agentType`. This is what fixes the 52/57 lite-row misclassification
 * in the Phase 2 device-telemetry audit — lite rows now arrive with real RAM
 * and tier instead of all-zero defaults.
 */
data class HardwareManifestLite(
    val chipsetVendor: ChipsetVendor,
    val chipsetName: String,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val recommendedModelTier: ModelTier,
    val maxVlmSizeMb: Int,
    // Phase 2 prereq PR 2 — additive only, default-safe. Mirrors the
    // agent-core HardwareManifest field names so the server-side parser
    // does NOT have to fork between full and lite payloads.
    val cpuAbi: String? = null,
    val osApiLevel: Int = 0,
    val screenWidthPx: Int = 0,
    val screenHeightPx: Int = 0,
    val densityDpi: Int = 0
) {
    /**
     * Chipset vendor (parallel to agent-core's `DeviceProfile.ChipsetVendor`).
     * Same enum values so server parsers don't fork.
     */
    enum class ChipsetVendor {
        QUALCOMM,
        MEDIATEK,
        EXYNOS,
        ROCKCHIP,
        AMLOGIC,
        UNKNOWN
    }

    /**
     * Model tier classification based on total RAM.
     * Same thresholds and names as agent-core's HardwareManifest.ModelTier.
     */
    enum class ModelTier {
        FLOOR,      // <4GB RAM: Moondream 0.5B, SmolVLM-256M
        STANDARD,   // 4-8GB RAM: Gemma 3n E2B
        PREMIUM     // >8GB RAM: Gemma 3n E2B, Qwen3-VL 2B
    }

    /**
     * Map to the canonical `DeviceCapabilityPayload.MLCapabilities` so heartbeat
     * payload assembly is identical for full and lite agents.
     *
     * Lite leaves GPU / NPU / DSP / delegate / NNAPI fields at their defaults
     * (false / null) — these are accurate because lite doesn't run inference.
     *
     * The Phase 2 prereq fields (cpuAbi / osApiLevel / display geometry) DO
     * carry through — they're not ML probes, just lightweight Build /
     * DisplayMetrics reads, and the server typed columns expect them on
     * every flavor.
     */
    fun toMlCapabilities(): DeviceCapabilityPayload.MLCapabilities {
        return DeviceCapabilityPayload.MLCapabilities(
            chipsetVendor = chipsetVendor.name.lowercase(),
            chipsetName = chipsetName,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            gpuName = null,
            hasNpu = false,
            npuName = null,
            gpuDelegateSupported = false,
            nnapiSupported = false,
            recommendedModelTier = recommendedModelTier.name,
            maxVlmSizeMb = maxVlmSizeMb,
            cpuAbi = cpuAbi,
            osApiLevel = osApiLevel,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            densityDpi = densityDpi
        )
    }

    /**
     * JSON serialization — symmetric with agent-core's HardwareManifest.toJson()
     * for any debug log / file dump that wants the same shape on both flavors.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("chipsetVendor", chipsetVendor.name)
            put("chipsetName", chipsetName)
            put("totalRamMb", totalRamMb)
            put("availableRamMb", availableRamMb)
            put("hasGpu", false)
            put("gpuName", JSONObject.NULL)
            put("hasNpu", false)
            put("npuName", JSONObject.NULL)
            put("hasDsp", false)
            put("gpuDelegateSupported", false)
            put("nnapiSupported", false)
            put("recommendedModelTier", recommendedModelTier.name)
            put("maxVlmSizeMb", maxVlmSizeMb)
            put("cpuAbi", cpuAbi ?: JSONObject.NULL)
            put("osApiLevel", osApiLevel)
            put("screenWidthPx", screenWidthPx)
            put("screenHeightPx", screenHeightPx)
            put("densityDpi", densityDpi)
        }
    }

    companion object {
        private const val TAG = "HardwareManifestLite"

        // Match agent-core HardwareManifest's RAM tier thresholds exactly.
        // Hardcoded here (rather than reading SensingConfig) because lite does
        // not depend on agent-core's SensingConfig module.
        internal const val FLOOR_TIER_MAX_RAM_MB = 4096
        internal const val STANDARD_TIER_MAX_RAM_MB = 8192
        internal const val OS_RESERVATION_MB = 1500
        internal const val VLM_MEMORY_FRACTION = 0.5

        /**
         * Auto-detect a lite hardware manifest from the device.
         *
         * Returns a manifest with safe defaults if hardware queries throw —
         * this method NEVER throws to caller. Lite heartbeat path is fire-and-
         * forget; we'd rather emit a defaults row than skip the heartbeat.
         */
        fun detect(context: Context): HardwareManifestLite {
            val (totalRamMb, availableRamMb) = readMemoryInfo(context)
            val hardware = runCatching { Build.HARDWARE.lowercase() }.getOrDefault("")
            val board = runCatching { Build.BOARD.lowercase() }.getOrDefault("")
            val manufacturer = runCatching { Build.MANUFACTURER.lowercase() }.getOrDefault("")
            val vendor = detectVendor(hardware, board, manufacturer)
            val chipsetName = resolveChipsetName(hardware, board)
            val tier = classifyModelTier(totalRamMb)
            val maxVlmSizeMb = calculateMaxVlmSize(totalRamMb)

            // Phase 2 prereq PR 2 — ABI / OS API / display-geometry probes.
            // Each probe is wrapped in `runCatching` so a single sensor / Build
            // anomaly cannot brick the whole heartbeat (lite is fire-and-forget;
            // a defaults row beats a missing row).
            val cpuAbi = probeCpuAbi()
            val osApiLevel = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)
            val displayMetrics = probeDisplayMetrics(context)

            val manifest = HardwareManifestLite(
                chipsetVendor = vendor,
                chipsetName = chipsetName,
                totalRamMb = totalRamMb,
                availableRamMb = availableRamMb,
                recommendedModelTier = tier,
                maxVlmSizeMb = maxVlmSizeMb,
                cpuAbi = cpuAbi,
                osApiLevel = osApiLevel,
                screenWidthPx = displayMetrics.widthPixels,
                screenHeightPx = displayMetrics.heightPixels,
                densityDpi = displayMetrics.densityDpi
            )

            Log.i(
                TAG,
                "Lite hardware manifest detected: vendor=$vendor, chipset=$chipsetName, " +
                    "ram=${totalRamMb}MB available=${availableRamMb}MB, " +
                    "tier=$tier, maxVlm=${maxVlmSizeMb}MB, " +
                    "abi=${cpuAbi ?: "unknown"}, api=$osApiLevel, " +
                    "display=${displayMetrics.widthPixels}x${displayMetrics.heightPixels}@${displayMetrics.densityDpi}dpi"
            )
            return manifest
        }

        /**
         * Read total / available RAM via ActivityManager.MemoryInfo.
         *
         * Failure-tolerant: returns (0, 0) if ActivityManager is unavailable
         * (e.g. unit tests with default mock values). Caller still gets a valid
         * manifest with FLOOR tier and zero VLM headroom — the safe fallback.
         */
        private fun readMemoryInfo(context: Context): Pair<Int, Int> {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am == null) {
                    Log.w(TAG, "ActivityManager unavailable, returning zero memory")
                    return Pair(0, 0)
                }
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                val totalMb = (info.totalMem / (1024L * 1024L)).toInt()
                val availMb = (info.availMem / (1024L * 1024L)).toInt()
                Pair(totalMb, availMb)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read MemoryInfo: ${e.message}")
                Pair(0, 0)
            }
        }

        /**
         * Detect chipset vendor from Build properties.
         * Mirrors agent-core's HardwareManifest.detectVendor for parity.
         */
        internal fun detectVendor(
            hardware: String,
            board: String,
            manufacturer: String
        ): ChipsetVendor {
            // SOC_MANUFACTURER on API 31+ is the most reliable source.
            //
            // P1.1 fix (audit 2026-05-03): wrap the read in `runCatching`. Some
            // signage builds on API 31+ (Rockchip, Allwinner, generic-ROM TVs)
            // return null from `Build.SOC_MANUFACTURER` despite the API claim of
            // non-null; calling `.lowercase()` directly throws NPE here, which is
            // swallowed by the caller's try/catch and silently regresses lite
            // agents to defaults. Mirrors the full agent's protection at
            // `HardwareManifest.kt:335`.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val socManufacturer = runCatching { Build.SOC_MANUFACTURER.lowercase() }.getOrDefault("")
                when {
                    socManufacturer.contains("qualcomm") || socManufacturer.contains("qcom") ->
                        return ChipsetVendor.QUALCOMM
                    socManufacturer.contains("mediatek") || socManufacturer.contains("mtk") ->
                        return ChipsetVendor.MEDIATEK
                    socManufacturer.contains("samsung") &&
                        (hardware.contains("exynos") || socManufacturer.contains("slsi")) ->
                        return ChipsetVendor.EXYNOS
                    socManufacturer.contains("rockchip") -> return ChipsetVendor.ROCKCHIP
                    socManufacturer.contains("amlogic") -> return ChipsetVendor.AMLOGIC
                }
            }

            // Fallback: hardware/board substring heuristics (same logic as agent-core).
            return when {
                hardware.contains("mt") || hardware.contains("mtk") ||
                    board.contains("mt") || board.contains("mtk") ->
                    ChipsetVendor.MEDIATEK
                hardware.contains("qcom") || hardware.contains("sdm") ||
                    hardware.contains("sm") || board.contains("msm") ||
                    board.contains("sdm") || board.contains("qcom") ->
                    ChipsetVendor.QUALCOMM
                hardware.contains("exynos") || board.contains("exynos") ||
                    (manufacturer.contains("samsung") && hardware.contains("samsungexynos")) ->
                    ChipsetVendor.EXYNOS
                hardware.contains("rk") || board.contains("rk3") -> ChipsetVendor.ROCKCHIP
                hardware.contains("amlogic") || board.contains("s905") ||
                    board.contains("s912") || board.contains("s922") ->
                    ChipsetVendor.AMLOGIC
                else -> ChipsetVendor.UNKNOWN
            }
        }

        /**
         * Resolve a human-readable chipset name. Falls through Build.SOC_MODEL
         * (API 31+) → Build.HARDWARE → Build.BOARD → "unknown".
         *
         * P1.1 fix (audit 2026-05-03): wrap the SOC_MODEL read in `runCatching`
         * for the same reason as `detectVendor` — observed null on signage builds
         * despite the @NonNull annotation, and the swallowing call site silently
         * regressed lite agents to default capabilities.
         */
        internal fun resolveChipsetName(hardware: String, board: String): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val socModel = runCatching { Build.SOC_MODEL }.getOrNull()
                if (!socModel.isNullOrBlank() && socModel != "unknown") {
                    return socModel
                }
            }
            return when {
                hardware.isNotBlank() -> hardware
                board.isNotBlank() -> board
                else -> "unknown"
            }
        }

        /**
         * Classify model tier based on total RAM.
         * Same thresholds as agent-core HardwareManifest.classifyModelTier.
         */
        internal fun classifyModelTier(totalRamMb: Int): ModelTier {
            return when {
                totalRamMb < FLOOR_TIER_MAX_RAM_MB -> ModelTier.FLOOR
                totalRamMb < STANDARD_TIER_MAX_RAM_MB -> ModelTier.STANDARD
                else -> ModelTier.PREMIUM
            }
        }

        /**
         * Maximum VLM model size this device could realistically host.
         * Formula: (totalRam - 1500MB OS reserve) * 0.5; clamped to 0.
         * Same formula as agent-core for cross-flavor parity.
         */
        internal fun calculateMaxVlmSize(totalRamMb: Int): Int {
            val available = totalRamMb - OS_RESERVATION_MB
            if (available <= 0) return 0
            return (available * VLM_MEMORY_FRACTION).toInt()
        }

        /**
         * Probe the primary CPU ABI from `Build.SUPPORTED_ABIS[0]`.
         *
         * Returns null in the impossible-but-defended edge case of an empty
         * array (e.g. an emulator built with no native bridge) or any throw.
         * Server-side `cpu_abi` column tolerates NULL and skips the COALESCE
         * write in that case. Mirrors the full agent's `probeCpuAbi()`.
         */
        internal fun probeCpuAbi(): String? {
            return runCatching {
                Build.SUPPORTED_ABIS?.firstOrNull()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /**
         * Snapshot the device's primary display metrics.
         *
         * Uses `Resources.getDisplayMetrics()` from the supplied Context.
         * On most CTV deployments this is an Application context, which gives
         * the configuration-aware metrics — matches what the kiosk WebView /
         * native renderer sees. Multi-display devices (foldables, external
         * HDMI) report the default display, which is the display the agent
         * is bound to.
         *
         * Failure-tolerant: returns a default `DisplayMetrics()` (all-zero
         * fields) when `getResources()` throws — the heartbeat path stays
         * fire-and-forget.
         */
        internal fun probeDisplayMetrics(context: Context): DisplayMetrics {
            return runCatching {
                context.resources.displayMetrics
            }.getOrDefault(DisplayMetrics())
        }
    }
}
