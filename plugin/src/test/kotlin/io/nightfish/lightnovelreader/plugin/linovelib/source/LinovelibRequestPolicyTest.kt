package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibRequestPolicyTest {
    @Test
    fun requestDelayKeepsMinimumInterval() {
        assertEquals(800L, LinovelibRequestPolicy.requestDelayMillis(1_000L, 1_400L))
        assertEquals(0L, LinovelibRequestPolicy.requestDelayMillis(1_000L, 2_200L))
    }

    @Test
    fun retryAfterHeaderTakesPriorityForRateLimit() {
        assertEquals(7_000L, LinovelibRequestPolicy.retryDelayMillis(429, "7", 0))
    }

    @Test
    fun retryAfterHeaderKeepsExternalDelayWithinBounds() {
        assertEquals(0L, LinovelibRequestPolicy.retryDelayMillis(429, "-1", 0))
        assertEquals(0L, LinovelibRequestPolicy.retryDelayMillis(429, "0", 0))
        assertEquals(60_000L, LinovelibRequestPolicy.retryDelayMillis(429, "60", 0))
        assertEquals(60_000L, LinovelibRequestPolicy.retryDelayMillis(429, "61", 0))
        assertEquals(0L, LinovelibRequestPolicy.retryDelayMillis(429, Long.MIN_VALUE.toString(), 0))
        assertEquals(60_000L, LinovelibRequestPolicy.retryDelayMillis(429, (Long.MAX_VALUE / 1_000L + 1).toString(), 0))
        assertEquals(60_000L, LinovelibRequestPolicy.retryDelayMillis(429, Long.MAX_VALUE.toString(), 0))
    }

    @Test
    fun networkRetriesUseProgressiveDelays() {
        assertEquals(1_000L, LinovelibRequestPolicy.networkRetryDelayMillis(0))
        assertEquals(2_000L, LinovelibRequestPolicy.networkRetryDelayMillis(1))
        assertEquals(4_000L, LinovelibRequestPolicy.networkRetryDelayMillis(2))
    }

    @Test
    fun rateLimitUsesProgressiveDelayWithoutHeader() {
        assertEquals(3_000L, LinovelibRequestPolicy.retryDelayMillis(429, null, 0))
        assertEquals(6_000L, LinovelibRequestPolicy.retryDelayMillis(429, null, 1))
        assertEquals(12_000L, LinovelibRequestPolicy.retryDelayMillis(429, null, 2))
        assertEquals(24_000L, LinovelibRequestPolicy.retryDelayMillis(429, null, 3))
    }

    @Test
    fun transientServerErrorsCanRetryButClientErrorsCannot() {
        assertEquals(1_000L, LinovelibRequestPolicy.retryDelayMillis(503, null, 0))
        assertEquals(2_000L, LinovelibRequestPolicy.retryDelayMillis(503, null, 1))
        assertEquals(4_000L, LinovelibRequestPolicy.retryDelayMillis(503, null, 2))
        assertNull(LinovelibRequestPolicy.retryDelayMillis(404, null, 0))
    }
}
