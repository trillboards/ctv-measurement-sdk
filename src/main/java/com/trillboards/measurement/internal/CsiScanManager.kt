package com.trillboards.measurement.internal

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.trillboards.measurement.CsiMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Stubbed CSI scan manager for the measurement SDK.
 *
 * DESIGN NOTE — ADR-018 wire format is owned by the Rust parser
 * (`trillboard-ctv/csi-processor/csi-parser`). That parser is the single
 * source of truth for the frame layout (big-endian magic, variable-length
 * string node_id, 1-byte n_subcarriers, I/Q pairs).
 *
 * This class previously held a second, DIFFERENT in-Kotlin parser that was
 * incompatible with the Rust canonical layout. It has been stubbed out so
 * the measurement SDK does not do its own CSI parsing. All real CSI
 * processing is delegated to the Rust binary spawned by the
 * `@trillboards/edge-csi` package.
 *
 * [collect] always returns `null` today. The mDNS discovery + UDP listener
 * scaffolding is preserved so the public API and data classes remain
 * source-compatible with existing callers, but [parseFrame] returns `null`
 * unconditionally, which means [receiveFrames] always produces an empty list
 * and [collect] reports "no valid frames received".
 *
 * Thread safety: [collect] still uses an [AtomicBoolean] guard to prevent
 * concurrent invocations, matching the pattern in [BleBeaconScanner].
 */
internal object CsiScanManager {

    private const val TAG = "CsiScanManager"

    /** UDP port on which ESP32 nodes broadcast CSI frames. */
    private const val UDP_PORT = 5005

    /** mDNS service type for Trillboards CSI hardware. */
    private const val SERVICE_TYPE = "_trillboards-csi._udp."

    /** Duration of the mDNS discovery probe in milliseconds. */
    private const val DISCOVERY_TIMEOUT_MS = 2_000L

    /** Maximum UDP receive buffer size (header + up to 256 subcarriers * 4 bytes each). */
    private const val UDP_BUFFER_SIZE = 2048

    /** Default collection window duration in milliseconds. */
    private const val DEFAULT_WINDOW_MS = 10_000L

    private val collectInProgress = AtomicBoolean(false)

    // Phase 2 promoted the per-collection CsiMeasurement from a member type
    // of this `internal object` to a top-level public data class in the
    // `com.trillboards.measurement` package. That keeps the SDK's
    // -Xexplicit-api=strict public surface (MeasurementSnapshot.csiMeasurement)
    // from leaking an internal type. Use the package-qualified import.

    /**
     * Parsed fields from a single ADR-018 CSI frame header.
     */
    private data class CsiFrame(
        val nodeId: Int,
        val rssi: Int,
        val subcarrierCount: Int,
        val amplitudes: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CsiFrame) return false
            return nodeId == other.nodeId && rssi == other.rssi &&
                subcarrierCount == other.subcarrierCount && amplitudes.contentEquals(other.amplitudes)
        }
        override fun hashCode(): Int {
            var result = nodeId
            result = 31 * result + rssi
            result = 31 * result + subcarrierCount
            result = 31 * result + amplitudes.contentHashCode()
            return result
        }
    }

    /**
     * Collect CSI measurements from ESP32 hardware over a time window.
     *
     * 1. Performs a quick mDNS probe for `_trillboards-csi._udp.` services.
     * 2. If no ESP32 hardware is found, returns null (no hardware present).
     * 3. If found, listens on UDP port [UDP_PORT] for [windowMs] duration.
     * 4. Parses ADR-018 frames, aggregates RSSI, computes amplitude variance.
     * 5. Returns a [CsiMeasurement] with occupancy heuristic from frame data.
     *
     * @param context Application context for accessing NsdManager.
     * @param windowMs Duration to listen for CSI frames. Default 10 seconds.
     * @return [CsiMeasurement] if ESP32 hardware is found and frames are received,
     *   null if no hardware is present or no valid frames arrive.
     */
    suspend fun collect(context: Context, windowMs: Long = DEFAULT_WINDOW_MS): CsiMeasurement? =
        withContext(Dispatchers.IO) {
            if (!collectInProgress.compareAndSet(false, true)) {
                Logger.d(TAG, "CSI collection already in progress -- skipping")
                return@withContext null
            }

            try {
                // Step 1: Quick mDNS probe for ESP32 CSI hardware
                val serviceFound = discoverCsiService(context)
                if (!serviceFound) {
                    Logger.d(TAG, "No _trillboards-csi._udp. service found -- no ESP32 hardware")
                    return@withContext null
                }

                Logger.d(TAG, "ESP32 CSI service discovered, listening for frames on UDP $UDP_PORT")

                // Step 2: Listen for UDP frames over the collection window
                val windowStart = System.currentTimeMillis()
                val frames = receiveFrames(windowMs)
                val windowEnd = System.currentTimeMillis()

                if (frames.isEmpty()) {
                    Logger.d(TAG, "No valid CSI frames received during ${windowMs}ms window")
                    return@withContext null
                }

                // Step 3: Aggregate frame data into a measurement
                val measurement = aggregateFrames(frames, windowStart, windowEnd)
                Logger.d(
                    TAG,
                    "CSI collection complete: ${frames.size} frames, " +
                        "occupants=${measurement.occupantCount}, motion=${measurement.motionScore}"
                )
                measurement
            } catch (e: Exception) {
                Logger.w(TAG, "CSI collection failed: ${e.message}")
                null
            } finally {
                collectInProgress.set(false)
            }
        }

    /**
     * Probe for `_trillboards-csi._udp.` mDNS services on the local network.
     *
     * Returns true if at least one service is found within [DISCOVERY_TIMEOUT_MS],
     * false otherwise. This is a quick presence check, not a full resolution.
     */
    private fun discoverCsiService(context: Context): Boolean {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return false

        val found = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Logger.d(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Logger.d(TAG, "Found CSI service: ${serviceInfo.serviceName}")
                found.set(true)
                latch.countDown()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Service went away during discovery -- not an error
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Logger.d(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.w(TAG, "mDNS discovery start failed: errorCode=$errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.w(TAG, "mDNS discovery stop failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Logger.w(TAG, "mDNS discovery error: ${e.message}")
        } finally {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // stopServiceDiscovery can throw if discovery was never started or already stopped
            }
        }

        return found.get()
    }

    /**
     * Listen on UDP port [UDP_PORT] for ADR-018 CSI frames for the given duration.
     *
     * Parses each received datagram and collects valid frames. Datagrams that
     * fail magic number or header size validation are silently discarded.
     *
     * @param windowMs Duration to listen in milliseconds.
     * @return List of parsed [CsiFrame] objects.
     */
    private fun receiveFrames(windowMs: Long): List<CsiFrame> {
        val frames = mutableListOf<CsiFrame>()
        val deadline = System.currentTimeMillis() + windowMs
        val buffer = ByteArray(UDP_BUFFER_SIZE)

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(UDP_PORT, InetAddress.getByName("0.0.0.0"))
            // Set SO_REUSEADDR before binding would be ideal, but DatagramSocket
            // constructor binds immediately. Short socket timeout prevents blocking.
            socket.soTimeout = 500 // 500ms receive timeout to allow checking deadline

            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val frame = parseFrame(packet.data, packet.length)
                    if (frame != null) {
                        frames.add(frame)
                    }
                } catch (_: SocketTimeoutException) {
                    // Expected -- check deadline and loop
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "UDP receive error: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }

        return frames
    }

    /**
     * Stub ADR-018 CSI frame parser.
     *
     * Always returns `null`. The canonical ADR-018 wire format is owned by the
     * Rust parser at `trillboard-ctv/csi-processor/csi-parser` (big-endian
     * magic, variable-length string node_id, 1-byte n_subcarriers, I/Q pairs).
     * This file previously held a second, DIFFERENT in-Kotlin parser that was
     * incompatible with the canonical Rust layout.
     *
     * The measurement SDK does not do its own CSI frame parsing. All CSI
     * aggregation is delegated to the Rust binary spawned by the
     * `@trillboards/edge-csi` package. This stub is preserved so [receiveFrames]
     * still compiles while always reporting "no frames received".
     *
     * Parameters are ignored; this is a no-op that always returns null.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun parseFrame(data: ByteArray, length: Int): CsiFrame? {
        return null
    }

    /**
     * Aggregate a list of CSI frames into a single [CsiMeasurement].
     *
     * Computes:
     * - Average RSSI across all frames
     * - Signal quality: normalized RSSI mapped from [-100, -30] to [0.0, 1.0]
     * - Motion score: normalized inter-frame amplitude variance.
     *   Higher variance indicates more movement in the environment.
     * - Occupant count: heuristic based on amplitude variance thresholds.
     *   This is a coarse estimate; fine-grained occupancy counting requires
     *   the Rust processor on the ESP32 with MUSIC algorithm.
     * - Capture rate: frames per second during the window.
     *
     * @param frames Non-empty list of parsed CSI frames.
     * @param windowStart Window start timestamp (epoch millis).
     * @param windowEnd Window end timestamp (epoch millis).
     */
    private fun aggregateFrames(
        frames: List<CsiFrame>,
        windowStart: Long,
        windowEnd: Long
    ): CsiMeasurement {
        // Use the most common node_id (in case multiple ESP32s are broadcasting)
        val primaryNodeId = frames.groupingBy { it.nodeId }.eachCount()
            .maxByOrNull { it.value }?.key ?: 0

        // Average RSSI
        val avgRssi = frames.map { it.rssi }.average().toInt()

        // Signal quality: map RSSI from [-100, -30] to [0.0, 1.0]
        val signalQuality = ((avgRssi + 100).toFloat() / 70f).coerceIn(0f, 1f)

        // Subcarrier count from the last frame (all frames from same node should agree)
        val subcarrierCount = frames.lastOrNull()?.subcarrierCount ?: 0

        // Compute inter-frame amplitude variance for motion detection
        val motionScore = computeMotionScore(frames)

        // Occupant count heuristic from amplitude variance
        // Low variance (< 0.1): likely 0 people (static environment)
        // Medium variance (0.1-0.3): 1-2 people
        // High variance (0.3-0.6): 3-5 people
        // Very high variance (> 0.6): 6+ people
        val occupantCount = when {
            motionScore < 0.1f -> 0
            motionScore < 0.3f -> 1 + ((motionScore - 0.1f) / 0.2f * 1f).toInt()
            motionScore < 0.6f -> 3 + ((motionScore - 0.3f) / 0.3f * 2f).toInt()
            else -> 6 + ((motionScore - 0.6f) / 0.4f * 4f).toInt()
        }

        // Capture rate
        val windowDurationSec = (windowEnd - windowStart).toFloat() / 1000f
        val captureRate = if (windowDurationSec > 0f) {
            frames.size.toFloat() / windowDurationSec
        } else {
            0f
        }

        return CsiMeasurement(
            nodeId = primaryNodeId,
            occupantCount = occupantCount,
            motionScore = motionScore,
            signalQuality = signalQuality,
            subcarrierCount = subcarrierCount,
            captureRateHz = captureRate,
            avgRssiDbm = avgRssi,
            framesProcessed = frames.size,
            windowStartMs = windowStart,
            windowEndMs = windowEnd
        )
    }

    /**
     * Compute a motion score from inter-frame amplitude variance.
     *
     * For each consecutive pair of frames, compute the mean absolute difference
     * of their amplitude vectors. The overall motion score is the normalized
     * standard deviation of these differences.
     *
     * @return Normalized score in [0.0, 1.0]. 0 = no motion, 1 = high motion.
     */
    private fun computeMotionScore(frames: List<CsiFrame>): Float {
        if (frames.size < 2) return 0f

        // Filter to frames with amplitudes
        val framesWithAmplitudes = frames.filter { it.amplitudes.isNotEmpty() }
        if (framesWithAmplitudes.size < 2) return 0f

        val diffs = mutableListOf<Float>()
        for (i in 1 until framesWithAmplitudes.size) {
            val prev = framesWithAmplitudes[i - 1].amplitudes
            val curr = framesWithAmplitudes[i].amplitudes
            val minLen = minOf(prev.size, curr.size)
            if (minLen == 0) continue

            var sumDiff = 0f
            for (j in 0 until minLen) {
                sumDiff += kotlin.math.abs(curr[j] - prev[j])
            }
            diffs.add(sumDiff / minLen)
        }

        if (diffs.isEmpty()) return 0f

        // Compute standard deviation of the inter-frame differences
        val mean = diffs.average().toFloat()
        val variance = diffs.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)

        // Normalize: empirically, stdDev > 50 is very high motion for typical CSI amplitudes.
        // Clamp to [0, 1].
        return (stdDev / 50f).coerceIn(0f, 1f)
    }
}
