package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibImageCooldownTest {
    @Test
    fun normalizesHostnamesAndIsolatesDistinctHosts() {
        var now = 10_000L
        val cooldown = LinovelibImageCooldown { now }

        cooldown.postpone("IMG.ReadPai.COM.", 3_000)
        now += 250

        assertEquals(2_750L, cooldown.remainingMillis("img.readpai.com"))
        assertEquals(0L, cooldown.remainingMillis("other.readpai.com"))
    }

    @Test
    fun extendsOnlyWhenTheNewDeadlineIsLaterAndExpiresAtTheDeadline() {
        var now = 10_000L
        val cooldown = LinovelibImageCooldown { now }
        val host = "img.readpai.com"

        cooldown.postpone(host, 3_000)
        now += 1_000
        cooldown.postpone(host, 500)
        cooldown.postpone(host, 0)
        assertEquals(2_000L, cooldown.remainingMillis(host))

        cooldown.postpone(host, 4_000)
        now += 3_999
        assertEquals(1L, cooldown.remainingMillis(host))
        now++
        assertEquals(0L, cooldown.remainingMillis(host))
    }

    @Test
    fun waitIsCancellableAndTheNextCallerCanProceedAfterExpiry() = runBlocking {
        var now = 10_000L
        val cooldown = LinovelibImageCooldown { now }
        val host = "img.readpai.com"
        cooldown.postpone(host, 30_000)

        var proceeded = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            cooldown.await(host)
            proceeded = true
        }
        assertTrue(waiter.isActive)
        assertFalse(proceeded)
        withTimeout(1_000) { waiter.cancelAndJoin() }
        assertFalse(proceeded)
        assertEquals(30_000L, cooldown.remainingMillis(host))

        now += 30_000
        withTimeout(1_000) { cooldown.await(host) }
    }
}
