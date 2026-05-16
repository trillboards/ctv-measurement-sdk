package com.trillboards.measurement.internal

import com.trillboards.ctv.core.models.HeartbeatPayload
import com.trillboards.ctv.core.net.ApiClient
import kotlinx.coroutines.delay

/**
 * Public-SDK heartbeat uploader.
 *
 * Wraps [ApiClient.sendHeartbeatWithResult] with Retry-After-aware backoff:
 *   - On 429 with a Retry-After header, sleeps for the indicated duration
 *     (bounded between [MIN_BACKOFF_MS] and [MAX_BACKOFF_MS]) and retries
 *     up to [MAX_RETRIES] times.
 *   - On transport failures (IOException, SSL handshake), retries with
 *     exponential backoff (base 1 s, doubling, capped at [MAX_BACKOFF_MS]).
 *   - On permanent failures (4xx ≠ 429, 5xx after retry exhausted), logs
 *     and returns — caller's next scheduled scan will retry on its own.
 *
 * The 30-second scheduled-scan cadence is the outer retry — partners who
 * configure `scanIntervalMs = 30_000` get one upload attempt per heartbeat
 * with up to [MAX_RETRIES] in-tick retries. That matches the tablet-agent
 * fleet's empirical 429 recovery profile (server-side rate limits typically
 * release within 1-2 ticks).
 */
internal class HeartbeatUploader(
    private val apiClient: ApiClient,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private companion object {
        const val TAG = "HeartbeatUploader"
        const val MAX_RETRIES = 3
        const val MIN_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 30_000L
    }

    /**
     * Upload a heartbeat. Returns true on 2xx success, false on any
     * permanent or retry-exhausted failure.
     *
     * The 30-second outer scan loop retries on a fresh scan, so callers
     * generally don't need to inspect the boolean — they just observe
     * `signal_observations` rows landing server-side over time.
     */
    suspend fun upload(payload: HeartbeatPayload): Boolean {
        var attempt = 0
        var backoff = MIN_BACKOFF_MS
        while (attempt <= MAX_RETRIES) {
            when (val result = apiClient.sendHeartbeatWithResult(payload)) {
                is ApiClient.HeartbeatResult.Success -> {
                    Logger.d(TAG, "Heartbeat uploaded (attempt=$attempt)")
                    return true
                }
                is ApiClient.HeartbeatResult.RateLimited -> {
                    val delayMs = result.retryAfterSeconds
                        ?.let { (it * 1000L).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS) }
                        ?: backoff
                    if (attempt >= MAX_RETRIES) {
                        Logger.w(TAG, "Heartbeat upload: rate-limited, retries exhausted")
                        return false
                    }
                    Logger.w(TAG, "Heartbeat upload: 429, sleeping ${delayMs}ms (attempt=$attempt)")
                    sleeper(delayMs)
                    // Exponential ceiling for the next non-Retry-After retry
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    attempt++
                }
                is ApiClient.HeartbeatResult.TransportFailure -> {
                    if (attempt >= MAX_RETRIES) {
                        Logger.w(TAG, "Heartbeat upload: transport failure, retries exhausted: ${result.cause.message}")
                        return false
                    }
                    Logger.w(TAG, "Heartbeat upload: transport failure, sleeping ${backoff}ms (attempt=$attempt)")
                    sleeper(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    attempt++
                }
                is ApiClient.HeartbeatResult.PermanentFailure -> {
                    Logger.w(TAG, "Heartbeat upload: permanent failure HTTP ${result.httpStatus}")
                    return false
                }
            }
        }
        return false
    }
}
