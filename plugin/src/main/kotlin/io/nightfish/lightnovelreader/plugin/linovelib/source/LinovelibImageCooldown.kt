package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class LinovelibImageCooldown(
    private val nowMillis: () -> Long = { (System.nanoTime() - startedAtNanos) / 1_000_000 }
) {
    private val deadlines = mutableMapOf<String, Long>()

    @Synchronized
    fun postpone(host: String, delayMillis: Long) {
        if (delayMillis <= 0) return
        val now = nowMillis()
        deadlines.entries.removeAll { it.value <= now }
        val key = hostKey(host)
        val deadline = now + delayMillis.coerceAtMost(Long.MAX_VALUE - now)
        deadlines[key] = maxOf(deadlines[key] ?: now, deadline)
    }

    @Synchronized
    fun remainingMillis(host: String): Long {
        val key = hostKey(host)
        val deadline = deadlines[key] ?: return 0
        val remaining = deadline - nowMillis()
        if (remaining > 0) return remaining
        deadlines.remove(key)
        return 0
    }

    suspend fun await(host: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = remainingMillis(host)
            if (remaining == 0L) return
            delay(remaining)
        }
    }

    private fun hostKey(host: String): String = host.lowercase().removeSuffix(".")

    private companion object {
        val startedAtNanos = System.nanoTime()
    }
}
