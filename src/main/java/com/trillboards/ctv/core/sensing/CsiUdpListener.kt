package com.trillboards.ctv.core.sensing

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Parsed CSI frame from an ESP32 node, following the canonical ADR-018
 * binary wire format defined by the Rust parser at
 * `trillboard-ctv/csi-processor/csi-parser/src/lib.rs`.
 *
 * Wire format (big-endian, variable-length):
 * ```
 * Offset     Size     Field
 * 0          4        magic (0xC5110001, big-endian u32)
 * 4          2        payload_len (big-endian u16; bytes after this field)
 * 6          1        node_id_len (u8, N bytes; 1..=32)
 * 7          N        node_id (UTF-8 string of length N)
 * 7+N        1        n_subcarriers (u8, S)
 * 8+N        2        freq_mhz (big-endian u16)
 * 10+N       2        sequence (big-endian u16)
 * 12+N       1        rssi (signed i8, dBm)
 * 13+N       1        noise_floor (signed i8, dBm)
 * 14+N       2*S      I/Q pairs (each pair: I as i8, Q as i8)
 * ```
 *
 * The previous Kotlin parser used a different layout (little-endian, 1-byte
 * numeric node_id, 2-byte subcarrier count, an antenna count field). That
 * layout was incompatible with the Rust canonical format and has been
 * replaced.
 *
 * `nodeId` is a UTF-8 string (matches Rust `CsiFrame.node_id`). The field
 * `nAntennas` is no longer part of the wire format — there is only a single
 * `n_subcarriers` byte now. It is retained on the data class as a legacy
 * field defaulting to 1 so existing `CsiAggregator` / callers continue to
 * compile, but the parser always emits `nAntennas = 1`.
 */
data class CsiFrame(
    val nodeId: String,
    val nAntennas: Int,
    val nSubcarriers: Int,
    val freqMhz: Int,
    val sequence: Int,
    val rssi: Int,
    val noiseFloor: Int,
    val amplitudes: FloatArray,
    val phases: FloatArray,
    val receivedAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CsiFrame) return false
        return nodeId == other.nodeId && sequence == other.sequence
    }

    override fun hashCode(): Int = 31 * nodeId.hashCode() + sequence
}

/**
 * Callback interface for receiving parsed CSI frames.
 */
fun interface CsiFrameCallback {
    fun onFrame(frame: CsiFrame)
}

/**
 * UDP listener for ADR-018 CSI frames from ESP32 sensing nodes.
 *
 * Binds to a UDP port (default 5005) and continuously receives datagrams.
 * Each datagram is parsed according to the ADR-018 binary header format.
 * Valid frames are delivered to registered [CsiFrameCallback] listeners
 * and also stored in a bounded internal buffer for [CsiAggregator] to consume.
 *
 * Lifecycle: call [start] to begin listening, [stop] to shut down.
 * Uses Kotlin coroutines on [Dispatchers.IO] for non-blocking socket reads.
 *
 * Thread safety: frame buffer is a [CopyOnWriteArrayList], callbacks are
 * invoked on the IO dispatcher thread.
 */
class CsiUdpListener(
    private val port: Int = DEFAULT_PORT,
    private val maxBufferSize: Int = MAX_BUFFER_FRAMES
) {

    companion object {
        private const val TAG = "CsiUdpListener"
        const val DEFAULT_PORT = 5005
        const val MAGIC = 0xC5110001L

        /**
         * Minimum valid frame length.
         *
         * Layout (canonical ADR-018, see [CsiFrame] doc):
         *   magic(4) + payload_len(2) + node_id_len(1) + node_id(>=1) +
         *   n_subcarriers(1) + freq_mhz(2) + sequence(2) + rssi(1) +
         *   noise_floor(1) = 15 bytes for a zero-subcarrier frame with a
         *   single-byte node_id.
         */
        const val MIN_FRAME_SIZE = 15

        /** Legacy alias — older callers expect `HEADER_SIZE` even though the
         *  canonical ADR-018 header is variable-length. Kept for source
         *  compat only; the parser does not use a fixed header size. */
        const val HEADER_SIZE = MIN_FRAME_SIZE

        const val MAX_DATAGRAM_SIZE = 2048
        const val MAX_BUFFER_FRAMES = 6000  // ~60s at 100Hz
        const val MAX_NODE_ID_LEN = 32

        /**
         * Parse a canonical ADR-018 CSI frame from a raw UDP datagram buffer.
         *
         * Returns `null` if the buffer is too short, the magic doesn't match,
         * `node_id_len` is zero or exceeds [MAX_NODE_ID_LEN], `node_id` is not
         * valid UTF-8, or the I/Q payload is truncated.
         *
         * Wire format matches the Rust parser exactly (big-endian multi-byte
         * fields, variable-length string node_id, 1-byte subcarrier count, I/Q
         * pairs as signed i8 values).
         *
         * @param buf Raw datagram bytes
         * @param length Number of valid bytes in the buffer
         * @return Parsed [CsiFrame] or null if the datagram is invalid
         */
        fun parseFrame(buf: ByteArray, length: Int): CsiFrame? {
            if (length < MIN_FRAME_SIZE) return null

            // ByteBuffer reads are big-endian by default, but we access bytes
            // directly below; wrap for the multi-byte u16 reads.
            val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.BIG_ENDIAN)

            // Validate magic number (offset 0, big-endian u32).
            val magic = bb.getInt(0).toLong() and 0xFFFFFFFFL
            if (magic != MAGIC) return null

            // Payload length (offset 4, big-endian u16). The payload covers
            // everything from offset 6 onward; total frame length must be
            // >= 6 + payload_len.
            val payloadLen = bb.getShort(4).toInt() and 0xFFFF
            if (length < 6 + payloadLen) return null

            // node_id_len (offset 6, u8). Must be in 1..=MAX_NODE_ID_LEN.
            val nodeIdLen = buf[6].toInt() and 0xFF
            if (nodeIdLen == 0 || nodeIdLen > MAX_NODE_ID_LEN) return null
            if (7 + nodeIdLen + 7 > length) return null

            // node_id (offset 7, N bytes UTF-8).
            val nodeId = try {
                String(buf, 7, nodeIdLen, Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }

            val base = 7 + nodeIdLen

            // Fixed fields after node_id: n_subcarriers(1) + freq(2) + seq(2) +
            //                             rssi(1) + noise(1) = 7 bytes.
            if (base + 7 > length) return null

            val nSubcarriers = buf[base].toInt() and 0xFF
            val freqMhz = bb.getShort(base + 1).toInt() and 0xFFFF
            val sequence = bb.getShort(base + 3).toInt() and 0xFFFF
            val rssi = buf[base + 5].toInt()         // signed i8
            val noiseFloor = buf[base + 6].toInt()   // signed i8

            // I/Q pairs follow immediately after the fixed fields.
            val iqStart = base + 7
            val iqBytesNeeded = nSubcarriers * 2
            if (iqStart + iqBytesNeeded > length) return null

            // Phase is intentionally skipped; no downstream consumer uses it,
            // and omitting atan2 materially reduces hot-path CPU on tablets.
            val amplitudes = FloatArray(nSubcarriers)

            for (i in 0 until nSubcarriers) {
                val offset = iqStart + i * 2
                val iVal = buf[offset].toInt()       // signed i8
                val qVal = buf[offset + 1].toInt()   // signed i8

                amplitudes[i] = kotlin.math.sqrt((iVal * iVal + qVal * qVal).toFloat())
            }

            return CsiFrame(
                nodeId = nodeId,
                nAntennas = 1,  // ADR-018 canonical format has no antenna count
                nSubcarriers = nSubcarriers,
                freqMhz = freqMhz,
                sequence = sequence,
                rssi = rssi,
                noiseFloor = noiseFloor,
                amplitudes = amplitudes,
                phases = FloatArray(0),  // Phases unused — empty to avoid wasting memory
                receivedAtMs = System.currentTimeMillis()
            )
        }
    }

    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private val callbacks = CopyOnWriteArrayList<CsiFrameCallback>()

    // Internal ring buffer of recent frames for aggregation
    private val frameBuffer = CopyOnWriteArrayList<CsiFrame>()

    @Volatile
    private var running = false

    @Volatile
    private var totalFramesReceived = 0L

    @Volatile
    private var totalFramesDropped = 0L

    /**
     * Start listening for CSI frames on the configured UDP port.
     *
     * @param scope CoroutineScope for the listener coroutine
     * @return true if started successfully, false if already running
     */
    fun start(scope: CoroutineScope): Boolean {
        if (running) {
            Log.d(TAG, "UDP listener already running on port $port")
            return false
        }

        running = true
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val dgSocket = DatagramSocket(null)
                dgSocket.reuseAddress = true
                dgSocket.bind(InetSocketAddress(port))
                dgSocket.soTimeout = 2000  // 2s timeout for graceful shutdown checks
                socket = dgSocket
                Log.d(TAG, "CSI UDP listener started on port $port")

                val buf = ByteArray(MAX_DATAGRAM_SIZE)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive && running) {
                    try {
                        dgSocket.receive(packet)
                        val frame = parseFrame(buf, packet.length)
                        if (frame != null) {
                            totalFramesReceived++
                            addToBuffer(frame)
                            for (cb in callbacks) {
                                try {
                                    cb.onFrame(frame)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Callback error: ${e.message}")
                                }
                            }
                        } else {
                            totalFramesDropped++
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal — allows loop to check running flag
                    } catch (e: Exception) {
                        if (running) {
                            Log.w(TAG, "UDP receive error: ${e.message}")
                            totalFramesDropped++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start UDP listener: ${e.message}")
            } finally {
                socket?.close()
                socket = null
                Log.d(TAG, "CSI UDP listener stopped")
            }
        }

        return true
    }

    /**
     * Stop the UDP listener and release resources.
     */
    fun stop() {
        running = false
        socket?.close()
        listenJob?.cancel()
        listenJob = null
        Log.d(TAG, "CSI UDP listener stop requested")
    }

    /**
     * Register a callback for receiving parsed CSI frames.
     */
    fun addCallback(callback: CsiFrameCallback) {
        callbacks.add(callback)
    }

    /**
     * Remove a previously registered callback.
     */
    fun removeCallback(callback: CsiFrameCallback) {
        callbacks.remove(callback)
    }

    /**
     * Get recent frames from the internal buffer.
     *
     * @param sinceMs Only return frames received after this epoch timestamp
     * @return List of frames matching the time filter
     */
    fun getRecentFrames(sinceMs: Long = 0): List<CsiFrame> {
        return if (sinceMs <= 0) {
            frameBuffer.toList()
        } else {
            frameBuffer.filter { it.receivedAtMs >= sinceMs }
        }
    }

    /**
     * Clear the internal frame buffer.
     */
    fun clearBuffer() {
        frameBuffer.clear()
    }

    /**
     * Total frames successfully parsed since listener start.
     */
    fun getTotalFramesReceived(): Long = totalFramesReceived

    /**
     * Total frames dropped (parse failure or receive error) since listener start.
     */
    fun getTotalFramesDropped(): Long = totalFramesDropped

    /**
     * Whether the listener is currently running.
     */
    fun isRunning(): Boolean = running

    /**
     * Add a frame to the ring buffer, evicting oldest entries if over capacity.
     */
    private fun addToBuffer(frame: CsiFrame) {
        frameBuffer.add(frame)
        // Trim oldest frames when buffer exceeds max size
        while (frameBuffer.size > maxBufferSize) {
            frameBuffer.removeAt(0)
        }
    }
}
