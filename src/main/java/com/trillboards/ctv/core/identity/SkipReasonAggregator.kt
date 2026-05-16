package com.trillboards.ctv.core.identity

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-source skip-reason aggregator (mirror of agent-core sibling).
 *
 * Phase 0 of the `redis-stream-scale-fix` plan extends the structured
 * skip-reason channel introduced for BLE in PR #4480 to mDNS, SSDP, and
 * HTTP probe. The heartbeat builder calls every adapter once per
 * heartbeat; each call may bail with a non-null `skipReason`. Without
 * this aggregator, the wire payload would carry only the LAST cycle's
 * skip reason — useless for spotting periodic flaps. With it, we
 * accumulate counts since the previous heartbeat and ship deltas, not
 * running totals.
 *
 * See `agent-core/.../SkipReasonAggregator.kt` for the full contract.
 * The two forks ship identical implementations because the heartbeat
 * builder lives independently in each fork.
 */
class SkipReasonAggregator {

    private val counters: ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> =
        ConcurrentHashMap()

    fun record(source: String, reason: String?) {
        if (reason == null) return
        val perSource = counters.computeIfAbsent(source) { ConcurrentHashMap() }
        val counter = perSource.computeIfAbsent(reason) { AtomicInteger(0) }
        counter.incrementAndGet()
    }

    fun snapshotAndReset(): Map<String, Map<String, Int>>? {
        if (counters.isEmpty()) return null

        val out = mutableMapOf<String, Map<String, Int>>()
        for (source in counters.keys.toList()) {
            val perSource = counters.remove(source) ?: continue
            val rendered = mutableMapOf<String, Int>()
            for ((reason, counter) in perSource) {
                val n = counter.get()
                if (n > 0) rendered[reason] = n
            }
            if (rendered.isNotEmpty()) {
                out[source] = rendered
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    fun isEmpty(): Boolean = counters.isEmpty() ||
        counters.values.all { perSource -> perSource.values.all { it.get() == 0 } }

    companion object {
        const val SOURCE_BLE: String = "ble"
        const val SOURCE_MDNS: String = "mdns"
        const val SOURCE_SSDP: String = "ssdp"
        const val SOURCE_HTTP_PROBE: String = "http_probe"
    }
}
