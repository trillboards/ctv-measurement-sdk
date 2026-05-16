package com.trillboards.measurement.internal

import android.util.Log

/**
 * Simple logging wrapper that can be toggled via [MeasurementConfig.debugLogging].
 *
 * When [enabled] is false (the default), all log calls are no-ops.
 * Uses [android.util.Log] under the hood — no external dependencies.
 */
internal object Logger {

    @Volatile
    var enabled: Boolean = false

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (enabled) Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, throwable: Throwable) {
        if (enabled) Log.w(tag, msg, throwable)
    }

    fun e(tag: String, msg: String) {
        if (enabled) Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable) {
        if (enabled) Log.e(tag, msg, throwable)
    }
}
