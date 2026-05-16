package com.trillboards.ctv.core.mdm

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.trillboards.ctv.core.socket.AgentSocketManager
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRTC-based screen streamer for MDM remote view/control sessions.
 *
 * Flow:
 *  1. Server sends `remote.start` event with sessionId and sessionType
 *  2. Agent starts [MediaProjectionService] as a foreground service (Android 14+ requirement)
 *  3. Agent creates a PeerConnection, attaches screen capture as video track
 *  4. Agent creates SDP offer, sends it via `remote.signal` socket event
 *  5. Admin sends SDP answer back via `remote.signal`
 *  6. ICE candidates are exchanged via `remote.signal`
 *  7. Video streams from device to admin
 *  8. If sessionType is "control", admin sends `remote.input` events
 *     which are injected via [Instrumentation] (requires Device Owner)
 *
 * Video settings: H.264, 720p max, adaptive bitrate capped at 2 Mbps.
 *
 * The [ScreenCapturerAndroid] from the WebRTC SDK internally creates its own
 * [MediaProjection] from the result Intent. The [MediaProjectionService] foreground
 * service must already be running to satisfy the Android 14+ requirement that
 * MediaProjection can only be obtained while a foreground service of type
 * `mediaProjection` is active.
 *
 * @param context Application context
 * @param socketManager Socket manager for signaling
 * @param sessionId Remote session UUID
 */
class WebRtcStreamer(
    private val context: Context,
    private val socketManager: AgentSocketManager,
    private val sessionId: String,
    private val iceServersConfig: List<Map<String, String>> = emptyList()
) {
    companion object {
        private const val TAG = "WebRtcStreamer"

        // Video capture settings
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 15
        private const val VIDEO_TRACK_ID = "screen_track_0"
        private const val STREAM_ID = "screen_stream_0"

        // Bitrate cap (bits per second)
        private const val MAX_BITRATE_BPS = 2_000_000
        private const val MIN_BITRATE_BPS = 200_000

        // STUN server
        private const val STUN_URL = "stun:stun.l.google.com:19302"
    }

    // WebRTC internals
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Instrumentation for remote control input injection
    private var instrumentation: Instrumentation? = null

    // Single-thread executor for input injection — avoids leaking one Thread per input event
    private val inputExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "WebRtcInputInjector").apply { isDaemon = true }
        }

    // Screen metrics for coordinate mapping in control mode
    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0

    @Volatile
    private var isActive = false

    /**
     * Start screen capture and WebRTC streaming.
     *
     * Must be called AFTER [MediaProjectionService] is started as a foreground
     * service. The [ScreenCapturerAndroid] internally calls
     * [android.media.projection.MediaProjectionManager.getMediaProjection] using
     * the provided result data Intent, which requires an active foreground service
     * of type `mediaProjection` on Android 14+.
     *
     * @param mediaProjectionResultData The result data Intent from the MediaProjection
     *        permission prompt (Activity.onActivityResult)
     * @param sessionId The remote session UUID (used for signal routing)
     */
    fun start(mediaProjectionResultData: Intent, sessionId: String) {
        if (isActive) {
            Log.w(TAG, "WebRtcStreamer already active for session $sessionId")
            return
        }

        Log.i(TAG, "Starting WebRTC screen stream for session $sessionId")
        isActive = true

        // Cache screen dimensions for coordinate mapping
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        Log.d(TAG, "Screen dimensions: ${screenWidthPx}x${screenHeightPx}")

        // Initialize WebRTC
        initializePeerConnectionFactory()
        createPeerConnection()
        startScreenCapture(mediaProjectionResultData)
        createOfferAndSend()

        // Emit connecting status
        emitSessionStatus("connecting")
    }

    /**
     * Handle incoming signaling data from the server.
     * Processes SDP answers and ICE candidates.
     *
     * @param signal JSONObject containing either an SDP answer or an ICE candidate
     */
    fun handleSignal(signal: JSONObject) {
        if (!isActive) {
            Log.w(TAG, "Ignoring signal -- streamer not active")
            return
        }

        val type = signal.optString("type", "")
        Log.d(TAG, "handleSignal: type=$type")

        when (type) {
            "answer" -> handleSdpAnswer(signal)
            "candidate", "ice-candidate" -> handleIceCandidate(signal)
            else -> Log.w(TAG, "Unknown signal type: $type")
        }
    }

    /**
     * Handle remote input events (click, key) from the admin.
     * Requires Device Owner permission for [Instrumentation] injection.
     *
     * @param inputEvent JSONObject describing the input event
     */
    fun handleRemoteInput(inputEvent: JSONObject) {
        if (!isActive) return

        val action = inputEvent.optString("type", "")
        Log.d(TAG, "handleRemoteInput: action=$action")

        try {
            when (action) {
                "click", "tap" -> handleTapInput(inputEvent)
                "longPress" -> handleLongPressInput(inputEvent)
                "swipe" -> handleSwipeInput(inputEvent)
                "key", "keyEvent" -> handleKeyInput(inputEvent)
                "back" -> injectKeyEvent(KeyEvent.KEYCODE_BACK)
                "home" -> injectKeyEvent(KeyEvent.KEYCODE_HOME)
                "recent" -> injectKeyEvent(KeyEvent.KEYCODE_APP_SWITCH)
                else -> Log.w(TAG, "Unknown input action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject remote input: ${e.message}", e)
        }
    }

    /**
     * Stop the WebRTC stream and clean up all resources.
     * Safe to call multiple times.
     */
    fun stop() {
        if (!isActive && peerConnection == null) {
            Log.d(TAG, "WebRtcStreamer already stopped")
            return
        }

        Log.i(TAG, "Stopping WebRTC screen stream for session $sessionId")
        isActive = false

        // Stop video capture
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capturer: ${e.message}")
        }
        videoCapturer?.dispose()
        videoCapturer = null

        // Dispose video track and source
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null

        // Close peer connection
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        // Clean up surface texture helper
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        // Dispose factory
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        // Release EGL context
        eglBase?.release()
        eglBase = null

        instrumentation = null

        // Shut down input injection executor
        inputExecutor.shutdownNow()

        Log.i(TAG, "WebRTC screen stream stopped for session $sessionId")
    }

    // ── WebRTC Setup ──────────────────────────────────────────────────────────

    private fun initializePeerConnectionFactory() {
        eglBase = EglBase.create()

        // Initialize WebRTC library
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        // Use hardware-accelerated H264 encoder when available
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized with H264 encoder")
    }

    private fun createPeerConnection() {
        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder(STUN_URL).createIceServer()
        )

        // Add server-provided ICE servers (e.g., TURN relay from remote.start payload)
        for (serverConfig in iceServersConfig) {
            val urls = serverConfig["urls"] ?: continue
            if (urls == STUN_URL) continue // already added
            val builder = PeerConnection.IceServer.builder(urls)
            serverConfig["username"]?.let { builder.setUsername(it) }
            serverConfig["credential"]?.let { builder.setPassword(it) }
            iceServers.add(builder.createIceServer())
            Log.d(TAG, "Added ICE server from config: $urls")
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory!!.createPeerConnection(
            rtcConfig,
            peerConnectionObserver
        )

        Log.d(TAG, "PeerConnection created")
    }

    /**
     * PeerConnection observer handles ICE candidate exchange and connection
     * state transitions.
     */
    private val peerConnectionObserver = object : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let { sendIceCandidate(it) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d(TAG, "ICE candidates removed: ${candidates?.size}")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "ICE connection state: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    Log.i(TAG, "WebRTC stream connected for session $sessionId")
                    emitSessionStatus("active")
                    applyBitrateConstraints()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "WebRTC stream disconnected for session $sessionId")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "ICE connection failed for session $sessionId")
                    emitSessionStatus("failed")
                    stop()
                }
                else -> { /* CHECKING, NEW, CLOSED, COMPLETED */ }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "ICE receiving: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state: $state")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onAddStream(stream: MediaStream?) {
            Log.d(TAG, "Remote stream added (unexpected for device side)")
        }

        override fun onRemoveStream(stream: MediaStream?) {
            Log.d(TAG, "Remote stream removed")
        }

        override fun onDataChannel(dc: DataChannel?) {
            Log.d(TAG, "Data channel created (unexpected for device side)")
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            Log.d(TAG, "Track added (unexpected for device side)")
        }
    }

    /**
     * Start screen capture using [ScreenCapturerAndroid].
     *
     * The capturer internally creates a [MediaProjection] from the result data
     * Intent. A foreground service of type `mediaProjection` must already be
     * running for this to succeed on Android 14+.
     */
    private fun startScreenCapture(mediaProjectionResultData: Intent) {
        val mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                stop()
            }
        }

        videoCapturer = ScreenCapturerAndroid(
            mediaProjectionResultData,
            mediaProjectionCallback
        )

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            eglBase!!.eglBaseContext
        )

        videoSource = peerConnectionFactory!!.createVideoSource(
            videoCapturer!!.isScreencast
        )

        videoCapturer!!.initialize(
            surfaceTextureHelper,
            context,
            videoSource!!.capturerObserver
        )

        // Start capturing at 720p, 15fps
        videoCapturer!!.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        videoTrack = peerConnectionFactory!!.createVideoTrack(
            VIDEO_TRACK_ID,
            videoSource
        ).apply {
            setEnabled(true)
        }

        // Add video track to peer connection
        peerConnection!!.addTrack(videoTrack, listOf(STREAM_ID))

        Log.i(TAG, "Screen capture started: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}fps")
    }

    private fun createOfferAndSend() {
        val constraints = MediaConstraints().apply {
            mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
            )
            mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false")
            )
        }

        peerConnection!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    Log.e(TAG, "createOffer returned null SDP")
                    return
                }

                Log.d(TAG, "SDP offer created, setting local description")
                peerConnection?.setLocalDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set, sending offer")
                            sendSdpOffer(sdp)
                        }

                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                            emitSessionStatus("failed")
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    },
                    sdp
                )
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create SDP offer: $error")
                emitSessionStatus("failed")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    // ── Signaling ─────────────────────────────────────────────────────────────

    private fun sendSdpOffer(sdp: SessionDescription) {
        val signal = JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp.description)
        }

        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("signal", signal)
        }

        socketManager.emit("remote.signal", payload)
        Log.i(TAG, "SDP offer sent for session $sessionId")
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val signal = JSONObject().apply {
            put("type", "ice-candidate")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }

        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("signal", signal)
        }

        socketManager.emit("remote.signal", payload)
        Log.d(TAG, "ICE candidate sent: ${candidate.sdpMid}")
    }

    private fun handleSdpAnswer(signal: JSONObject) {
        // Handle both raw string and nested object formats
        val sdpString = signal.optString("sdp", "")
            .ifEmpty { signal.optJSONObject("sdp")?.optString("sdp", "") ?: "" }
        if (sdpString.isEmpty()) {
            Log.e(TAG, "Empty SDP in answer signal")
            return
        }

        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description (answer) set successfully")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                emitSessionStatus("failed")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    private fun handleIceCandidate(signal: JSONObject) {
        val sdpMid = signal.optString("sdpMid", "")
        val sdpMLineIndex = signal.optInt("sdpMLineIndex", -1)
        val candidateSdp = signal.optString("candidate", "")

        if (sdpMid.isEmpty() || sdpMLineIndex < 0 || candidateSdp.isEmpty()) {
            Log.w(TAG, "Invalid ICE candidate signal: $signal")
            return
        }

        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        val added = peerConnection?.addIceCandidate(candidate)
        Log.d(TAG, "ICE candidate added: $added (mid=$sdpMid)")
    }

    // ── Bitrate Management ────────────────────────────────────────────────────

    private fun applyBitrateConstraints() {
        val pc = peerConnection ?: return

        for (sender in pc.senders) {
            val params = sender.parameters
            if (params.encodings.isEmpty()) continue

            for (encoding in params.encodings) {
                encoding.maxBitrateBps = MAX_BITRATE_BPS
                encoding.minBitrateBps = MIN_BITRATE_BPS
            }

            sender.parameters = params
            Log.d(
                TAG,
                "Bitrate constraints applied: min=$MIN_BITRATE_BPS, max=$MAX_BITRATE_BPS"
            )
        }
    }

    // ── Session Status ────────────────────────────────────────────────────────

    private fun emitSessionStatus(status: String) {
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("status", status)
            put("metadata", JSONObject().apply {
                put("resolution", "${VIDEO_WIDTH}x${VIDEO_HEIGHT}")
                put("fps", VIDEO_FPS)
                put("maxBitrate", MAX_BITRATE_BPS)
                put("timestamp", System.currentTimeMillis())
            })
        }
        socketManager.emit("remote.status", payload)
        Log.d(TAG, "Session status emitted: $status")
    }

    // ── Remote Input Injection ────────────────────────────────────────────────

    /**
     * Get or create an [Instrumentation] instance for input injection.
     * Input injection via Instrumentation requires the app to be a Device Owner
     * or to have INJECT_EVENTS permission.
     */
    private fun getInstrumentation(): Instrumentation {
        if (instrumentation == null) {
            instrumentation = Instrumentation()
        }
        return instrumentation!!
    }

    private fun handleTapInput(event: JSONObject) {
        // Coordinates come as normalized [0..1] ratios from the admin UI
        val normX = event.optDouble("x", -1.0)
        val normY = event.optDouble("y", -1.0)
        if (normX < 0 || normY < 0 || normX > 1 || normY > 1) {
            Log.w(TAG, "Invalid tap coordinates: x=$normX, y=$normY")
            return
        }

        val x = (normX * screenWidthPx).toFloat()
        val y = (normY * screenHeightPx).toFloat()

        inputExecutor.execute {
            try {
                val inst = getInstrumentation()
                val downTime = SystemClock.uptimeMillis()

                val downEvent = MotionEvent.obtain(
                    downTime, downTime,
                    MotionEvent.ACTION_DOWN,
                    x, y, 0
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }

                val upEvent = MotionEvent.obtain(
                    downTime, downTime + 50,
                    MotionEvent.ACTION_UP,
                    x, y, 0
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }

                inst.sendPointerSync(downEvent)
                inst.sendPointerSync(upEvent)

                downEvent.recycle()
                upEvent.recycle()

                Log.d(TAG, "Tap injected at ($x, $y)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Tap injection failed (need INJECT_EVENTS permission): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Tap injection failed: ${e.message}", e)
            }
        }
    }

    private fun handleLongPressInput(event: JSONObject) {
        val normX = event.optDouble("x", -1.0)
        val normY = event.optDouble("y", -1.0)
        if (normX < 0 || normY < 0 || normX > 1 || normY > 1) {
            Log.w(TAG, "Invalid long press coordinates: x=$normX, y=$normY")
            return
        }

        val x = (normX * screenWidthPx).toFloat()
        val y = (normY * screenHeightPx).toFloat()
        val durationMs = event.optLong("duration", 800).coerceIn(100, 10_000)

        inputExecutor.execute {
            try {
                val inst = getInstrumentation()
                val downTime = SystemClock.uptimeMillis()

                val downEvent = MotionEvent.obtain(
                    downTime, downTime,
                    MotionEvent.ACTION_DOWN,
                    x, y, 0
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }

                inst.sendPointerSync(downEvent)
                downEvent.recycle()

                // Hold for the specified duration
                Thread.sleep(durationMs)

                val upEvent = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    x, y, 0
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }

                inst.sendPointerSync(upEvent)
                upEvent.recycle()

                Log.d(TAG, "Long press injected at ($x, $y) for ${durationMs}ms")
            } catch (e: SecurityException) {
                Log.e(TAG, "Long press injection failed (need INJECT_EVENTS permission): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Long press injection failed: ${e.message}", e)
            }
        }
    }

    private fun handleSwipeInput(event: JSONObject) {
        val startX = event.optDouble("startX", -1.0)
        val startY = event.optDouble("startY", -1.0)
        val endX = event.optDouble("endX", -1.0)
        val endY = event.optDouble("endY", -1.0)
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            Log.w(TAG, "Invalid swipe coordinates")
            return
        }

        val sx = (startX * screenWidthPx).toFloat()
        val sy = (startY * screenHeightPx).toFloat()
        val ex = (endX * screenWidthPx).toFloat()
        val ey = (endY * screenHeightPx).toFloat()
        val durationMs = event.optLong("duration", 300).coerceIn(50, 10_000)
        val steps = 20

        inputExecutor.execute {
            try {
                val inst = getInstrumentation()
                val downTime = SystemClock.uptimeMillis()

                // DOWN
                val downEvent = MotionEvent.obtain(
                    downTime, downTime,
                    MotionEvent.ACTION_DOWN,
                    sx, sy, 0
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                inst.sendPointerSync(downEvent)
                downEvent.recycle()

                // MOVE in steps
                val stepDelayMs = durationMs / steps
                for (i in 1..steps) {
                    val fraction = i.toFloat() / steps
                    val moveX = sx + (ex - sx) * fraction
                    val moveY = sy + (ey - sy) * fraction

                    val moveEvent = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        moveX, moveY, 0
                    ).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    inst.sendPointerSync(moveEvent)
                    moveEvent.recycle()

                    if (stepDelayMs > 0) Thread.sleep(stepDelayMs)
                }

                // UP
                val upEvent = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    ex, ey, 0
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                inst.sendPointerSync(upEvent)
                upEvent.recycle()

                Log.d(TAG, "Swipe injected ($sx,$sy)->($ex,$ey) over ${durationMs}ms")
            } catch (e: SecurityException) {
                Log.e(TAG, "Swipe injection failed (need INJECT_EVENTS permission): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Swipe injection failed: ${e.message}", e)
            }
        }
    }

    private fun handleKeyInput(event: JSONObject) {
        val keyCode = event.optInt("keyCode", -1)
        if (keyCode < 0) {
            Log.w(TAG, "Invalid keyCode in key event")
            return
        }
        injectKeyEvent(keyCode)
    }

    private fun injectKeyEvent(keyCode: Int) {
        inputExecutor.execute {
            try {
                val inst = getInstrumentation()
                val downTime = SystemClock.uptimeMillis()

                val downEvent = KeyEvent(
                    downTime, downTime,
                    KeyEvent.ACTION_DOWN, keyCode, 0
                )
                val upEvent = KeyEvent(
                    downTime, downTime + 50,
                    KeyEvent.ACTION_UP, keyCode, 0
                )

                inst.sendKeySync(downEvent)
                inst.sendKeySync(upEvent)

                Log.d(TAG, "Key event injected: keyCode=$keyCode")
            } catch (e: SecurityException) {
                Log.e(TAG, "Key injection failed (need INJECT_EVENTS permission): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Key injection failed: ${e.message}", e)
            }
        }
    }
}
