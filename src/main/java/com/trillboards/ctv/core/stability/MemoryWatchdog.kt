package com.trillboards.ctv.core.stability

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight memory watchdog for agent-core-lite.
 * Checks system memory every 15 minutes and broadcasts a cache-clear intent
 * when available memory drops below 15%.
 *
 * Has a 10-minute cooldown between cache-clear broadcasts to prevent thrashing.
 * Does NOT call System.gc() — the Android runtime manages GC more efficiently.
 *
 * The full agent-core has ResourceMonitor with more granular pressure levels;
 * this is the minimal equivalent for lite devices (API 24+).
 */
class MemoryWatchdog(
    private val context: Context,
    private val clearCacheAction: String,
    private val checkIntervalMs: Long = 15 * 60 * 1000L // 15 minutes
) {
    companion object {
        private const val TAG = "MemoryWatchdog"
        private const val LOW_MEMORY_THRESHOLD = 0.15f // 15% available = trigger
        private const val COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes between cache clears
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val broadcastManager = LocalBroadcastManager.getInstance(context)
    private var watchdogJob: Job? = null
    private var lastCacheClearMs = 0L

    fun start(scope: CoroutineScope) {
        if (watchdogJob?.isActive == true) return
        Log.i(TAG, "Starting memory watchdog (interval: ${checkIntervalMs / 1000}s, threshold: ${(LOW_MEMORY_THRESHOLD * 100).toInt()}%, cooldown: ${COOLDOWN_MS / 1000}s)")

        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(checkIntervalMs)
                checkMemory()
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        Log.d(TAG, "Memory watchdog stopped")
    }

    private fun checkMemory() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo) ?: return

        val availableRatio = memInfo.availMem.toFloat() / memInfo.totalMem
        val availableMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)

        if (memInfo.lowMemory || availableRatio < LOW_MEMORY_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastCacheClearMs >= COOLDOWN_MS) {
                Log.w(TAG, "Low memory detected: ${availableMb}MB/${totalMb}MB (${(availableRatio * 100).toInt()}%) - broadcasting cache clear")
                broadcastManager.sendBroadcast(Intent(clearCacheAction))
                lastCacheClearMs = now
            } else {
                Log.w(TAG, "Low memory detected: ${availableMb}MB/${totalMb}MB (${(availableRatio * 100).toInt()}%) - cooldown active, ${(COOLDOWN_MS - (now - lastCacheClearMs)) / 1000}s remaining")
            }
        } else {
            Log.d(TAG, "Memory OK: ${availableMb}MB/${totalMb}MB (${(availableRatio * 100).toInt()}%)")
        }
    }
}
