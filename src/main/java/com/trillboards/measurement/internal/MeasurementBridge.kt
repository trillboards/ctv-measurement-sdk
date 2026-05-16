package com.trillboards.measurement.internal

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.trillboards.measurement.MeasurementSnapshot
import java.lang.ref.WeakReference
import org.json.JSONObject

/**
 * WebView JavaScript bridge that exposes measurement snapshots to ad creatives.
 *
 * Attached to a WebView via [attach], this bridge is accessible from JavaScript
 * as `window.TrillboardsMeasurement`. Creatives can:
 * - Call `getSnapshot()` to pull the latest scan data as JSON
 * - Call `getVersion()` to check the bridge protocol version
 *
 * The bridge also supports push-based delivery: the SDK can call [pushSnapshot]
 * to dispatch a `trillboards-measurement` CustomEvent into the WebView's
 * document, carrying the snapshot as event detail.
 *
 * The WebView is held via [WeakReference] to prevent Activity/Fragment leaks.
 *
 * Port of the bridge pattern from `com.trillboards.ctv.core.bridge.NativeDeviceBridge`.
 */
internal class MeasurementBridge private constructor(
    private val webViewRef: WeakReference<WebView>,
    private val snapshotProvider: () -> MeasurementSnapshot?
) {

    companion object {
        const val JS_INTERFACE_NAME = "TrillboardsMeasurement"
        private const val TAG = "MeasurementBridge"

        /**
         * Attach the measurement bridge to a WebView.
         *
         * Must be called before [WebView.loadUrl]. The WebView is stored
         * as a [WeakReference]. The [addJavascriptInterface] call is posted
         * to the WebView's handler to ensure it runs on the correct thread.
         *
         * @param webView The WebView to attach to.
         * @param snapshotProvider Lambda that returns the current cached snapshot.
         * @return The created [MeasurementBridge] so callers can invoke
         *   [pushSnapshot] when new scans complete.
         */
        fun attach(webView: WebView, snapshotProvider: () -> MeasurementSnapshot?): MeasurementBridge {
            val bridge = MeasurementBridge(WeakReference(webView), snapshotProvider)
            webView.post {
                try {
                    webView.addJavascriptInterface(bridge, JS_INTERFACE_NAME)
                    Logger.d(TAG, "Measurement bridge attached to WebView")
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to attach measurement bridge: ${e.message}")
                }
            }
            return bridge
        }
    }

    /**
     * Called from JavaScript: `window.TrillboardsMeasurement.getSnapshot()`
     *
     * @return JSON string of the latest [MeasurementSnapshot], or an empty
     *   JSON object `"{}"` if no snapshot is available.
     */
    @JavascriptInterface
    fun getSnapshot(): String {
        return try {
            val snapshot = snapshotProvider()
            if (snapshot != null) {
                BridgePayloadSerializer.toJson(snapshot)
            } else {
                "{}"
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error serializing snapshot: ${e.message}")
            "{}"
        }
    }

    /**
     * Called from JavaScript: `window.TrillboardsMeasurement.getVersion()`
     *
     * @return Bridge protocol version. Creatives can use this for
     *   forward-compatibility checks.
     */
    @JavascriptInterface
    fun getVersion(): Int = 1

    /**
     * Push a snapshot to the WebView via a CustomEvent dispatch.
     *
     * The event is dispatched on `window` as:
     * ```javascript
     * window.dispatchEvent(new CustomEvent('trillboards:measurement', {
     *   detail: { ... snapshot JSON ... }
     * }));
     * ```
     *
     * The event name `trillboards:measurement` and the `window` target MUST
     * match the @trillboards/ctv-measurement JS SDK's `bridge.ts:BRIDGE_EVENT`
     * exactly — otherwise push mode is silently broken end-to-end.
     *
     * Runs on the main thread via `WebView.post {}` to satisfy the
     * WebView threading contract.
     *
     * @param snapshot The snapshot to push.
     */
    fun pushSnapshot(snapshot: MeasurementSnapshot) {
        val webView = webViewRef.get()
        if (webView == null) {
            Logger.d(TAG, "WebView reference cleared -- skipping push")
            return
        }

        val json = try {
            BridgePayloadSerializer.toJson(snapshot)
        } catch (e: Exception) {
            Logger.w(TAG, "Error serializing snapshot for push: ${e.message}")
            return
        }

        // JSONObject.quote() produces a properly double-quoted and escaped JS string literal.
        // e.g. {"key":"val"} → '{"key":"val"}' with all special chars escaped.
        val safeJsLiteral = JSONObject.quote(json)

        // The event target (`window`) and name (`trillboards:measurement`) MUST match
        // the JS SDK's bridge.ts BRIDGE_EVENT constant exactly.
        val script = """
            (function() {
                try {
                    var data = JSON.parse($safeJsLiteral);
                    window.dispatchEvent(new CustomEvent('trillboards:measurement', { detail: data }));
                } catch(e) {}
            })();
        """.trimIndent()

        // evaluateJavascript must be called on the main thread
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to push snapshot to WebView: ${e.message}")
            }
        }
    }
}
