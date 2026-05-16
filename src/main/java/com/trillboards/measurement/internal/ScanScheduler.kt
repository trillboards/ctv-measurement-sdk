package com.trillboards.measurement.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic scan scheduler modeled on `TelemetryScheduler` from agent-core-lite.
 *
 * Launches a coroutine loop that invokes the provided [onScan] callback at
 * a fixed interval. The scheduler is idempotent: calling [start] while already
 * running is a no-op.
 *
 * Lifecycle is tied to the provided [CoroutineScope]. If the scope is cancelled
 * (e.g. during SDK shutdown), the scheduler stops automatically.
 */
internal class ScanScheduler {

    private var job: Job? = null

    /**
     * Whether the scheduler is currently running.
     */
    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Start the periodic scan loop.
     *
     * If the scheduler is already running, this call is a no-op.
     *
     * @param scope Coroutine scope to launch the loop in.
     * @param intervalMs Delay between scan invocations in milliseconds.
     * @param onScan Suspend function invoked each cycle. Exceptions are caught
     *   internally so a single scan failure does not kill the loop.
     */
    fun start(scope: CoroutineScope, intervalMs: Long, onScan: suspend () -> Unit) {
        if (job?.isActive == true) return

        job = scope.launch {
            while (isActive) {
                try {
                    onScan()
                } catch (e: Exception) {
                    Logger.w(TAG, "Scheduled scan failed: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * Stop the periodic scan loop.
     *
     * Safe to call even if the scheduler is not running.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "ScanScheduler"
    }
}
