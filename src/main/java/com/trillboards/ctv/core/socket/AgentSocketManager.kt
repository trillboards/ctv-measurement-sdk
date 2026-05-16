package com.trillboards.ctv.core.socket

import android.util.Log
import com.trillboards.ctv.core.AgentConfig
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URLEncoder

class AgentSocketManager(private val config: AgentConfig) {

    companion object {
        private const val TAG = "AgentSocketManager"
    }

    private var socket: Socket? = null

    fun connect(fingerprint: String, listener: Listener, deviceToken: String? = null) {
        Log.i(TAG, "Connecting to ${config.socketUrl} with fingerprint=$fingerprint")

        val options = IO.Options().apply {
            val tokenQuery = deviceToken
                ?.takeIf { it.isNotBlank() }
                ?.let { "&device_token=${URLEncoder.encode(it, "UTF-8")}" }
                ?: ""
            query = "fingerprint=${URLEncoder.encode(fingerprint, "UTF-8")}$tokenQuery"
            // Force WebSocket transport — skip HTTP long-polling handshake entirely.
            // The ALB (`chat.trillboards.com`) has sticky sessions for polling, but the
            // Java Socket.IO client doesn't persist AWSALB cookies between requests.
            // Without cookie persistence, the second polling POST routes to a different
            // ECS Fargate task which has no record of the session → "xhr post error".
            // WebSocket is a single persistent TCP connection pinned to one target after
            // the HTTP upgrade, so sticky sessions aren't needed.
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 3_000L
            reconnectionDelayMax = 10_000L
        }

        socket = IO.socket(config.socketUrl, options).apply {
            on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "Socket connected! ID=${id()}")
                listener.onConnect(id())
            }
            on(Socket.EVENT_DISCONNECT) {
                Log.w(TAG, "Socket disconnected")
                listener.onDisconnect()
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Socket connect error: ${args.joinToString()}")
                listener.onError(args)
            }
            on(config.privateMessageEvent) { args ->
                Log.d(TAG, "Received privateMessage event: ${args.firstOrNull()}")
                parsePayload(args)?.let(listener::onPrivateMessage)
            }
            on(config.deviceCommandEvent) { args ->
                Log.i(TAG, ">>> RECEIVED deviceCommand event: ${args.firstOrNull()}")
                parsePayload(args)?.let(listener::onDeviceCommand)
            }
            on("policy.push") { args ->
                Log.i(TAG, "Received policy.push event")
                parsePayload(args)?.let(listener::onPolicyPush)
            }
            on("remote.start") { args ->
                Log.i(TAG, "Received remote.start event")
                parsePayload(args)?.let(listener::onRemoteStart)
            }
            on("remote.end") { args ->
                Log.i(TAG, "Received remote.end event")
                parsePayload(args)?.let(listener::onRemoteEnd)
            }
            on("remote.signal") { args ->
                Log.d(TAG, "Received remote.signal event")
                parsePayload(args)?.let(listener::onRemoteSignal)
            }
            on("remote.input") { args ->
                Log.d(TAG, "Received remote.input event")
                parsePayload(args)?.let(listener::onRemoteInput)
            }
            on("screen_binding") { args ->
                Log.i(TAG, ">>> RECEIVED screen_binding event: ${args.firstOrNull()}")
                parsePayload(args)?.let(listener::onScreenBinding)
            }
            // Log ALL events for debugging
            onAnyIncoming { args ->
                Log.d(TAG, "ANY incoming event: ${args.joinToString()}")
            }
            connect()
        }
        Log.i(TAG, "Socket connect() called")
    }

    fun emitCommandAck(payload: JSONObject) {
        socket?.emit(config.deviceCommandAckEvent, payload)
    }

    fun emitStatus(payload: JSONObject) {
        socket?.emit(config.deviceCommandStatusEvent, payload)
    }

    /**
     * Emit a generic event with a JSON payload.
     * Used for device capabilities, status updates, etc.
     */
    fun emit(event: String, payload: JSONObject) {
        Log.d(TAG, "Emitting $event: $payload")
        socket?.emit(event, payload)
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
    }

    private fun parsePayload(args: Array<Any?>): JSONObject? {
        val payload = args.firstOrNull() ?: return null
        return when (payload) {
            is JSONObject -> payload
            is String -> runCatching { JSONObject(payload) }.getOrNull()
            else -> null
        }
    }

    interface Listener {
        fun onConnect(socketId: String?)
        fun onDisconnect()
        fun onError(args: Array<Any?>)
        fun onPrivateMessage(payload: JSONObject)
        fun onDeviceCommand(payload: JSONObject)
        fun onPolicyPush(payload: JSONObject)
        fun onRemoteStart(payload: JSONObject)
        fun onRemoteEnd(payload: JSONObject)
        fun onRemoteSignal(payload: JSONObject)
        fun onRemoteInput(payload: JSONObject)
        /** Called when server pushes a screen binding change (paired/deleted). Default no-op. */
        fun onScreenBinding(payload: JSONObject) {}
    }
}
