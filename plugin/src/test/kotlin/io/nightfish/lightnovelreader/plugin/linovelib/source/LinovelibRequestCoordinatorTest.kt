package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LinovelibRequestCoordinatorTest {
    @Test
    fun `serializes request blocks`() = runBlocking {
        val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        val jobs = List(4) {
            launch {
                coordinator.execute {
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { current -> maxOf(current, count) }
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals(1, maximum.get())
    }

    @Test
    fun `cancels while waiting for request interval`() = runBlocking {
        val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 10_000)
        coordinator.execute { Unit }
        var entered = false
        val waiting = launch {
            coordinator.execute { entered = true }
        }

        delay(30)
        assertFalse(entered)
        withTimeout(500) { waiting.cancelAndJoin() }

        assertTrue(waiting.isCancelled)
        assertFalse(entered)
    }

    @Test
    fun `result cooldown delays an already queued request`() = runBlocking {
        withTimeout(2_000) {
            val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
            val releaseFirst = CompletableDeferred<Unit>()
            val cooldownStartedAt = CompletableDeferred<Long>()
            val enteredAt = CompletableDeferred<Long>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.execute(cooldownAfter = {
                    cooldownStartedAt.complete(System.nanoTime())
                    200L
                }) { releaseFirst.await() }
            }
            // UNDISTPATCHED reaches the ticket wait before the first block is released.
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.execute { enteredAt.complete(System.nanoTime()) }
            }

            try {
                assertFalse(enteredAt.isCompleted)
                releaseFirst.complete(Unit)
                val elapsed = (enteredAt.await() - cooldownStartedAt.await()) / 1_000_000
                first.join()
                waiting.join()

                assertTrue("Queued request entered after only ${elapsed}ms", elapsed >= 190)
            } finally {
                first.cancelAndJoin()
                waiting.cancelAndJoin()
            }
        }
    }

    @Test
    fun `result cooldown delays a subsequent request`() = runBlocking {
        val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
        coordinator.execute(cooldownAfter = { 120L }) { Unit }
        val startedAt = System.currentTimeMillis()

        coordinator.execute { Unit }

        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("Next request entered after only ${elapsed}ms", elapsed >= 100)
    }

    @Test
    fun `serves queued requests in arrival order`() = runBlocking {
        val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch { coordinator.execute { releaseFirst.await() } }
        delay(30)
        val order = mutableListOf<Int>()
        val waiting = (1..5).map { index ->
            launch { coordinator.execute { order += index } }.also { yield() }
        }

        releaseFirst.complete(Unit)
        first.join()
        waiting.forEach { it.join() }

        assertEquals((1..5).toList(), order)
    }

    @Test
    fun `releases request slot when block is cancelled`() = runBlocking {
        val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
        val running = launch { coordinator.execute { awaitCancellation() } }
        delay(30)
        running.cancelAndJoin()

        var entered = false
        withTimeout(500) { coordinator.execute { entered = true } }

        assertTrue(entered)
    }

    @Test
    fun queuedCancellationsDoNotReleaseActiveSlotAndRemainingRequestsStayOrdered() = runBlocking {
        withTimeout(2_000) {
            val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
            val active = AtomicInteger(0)
            val maximum = AtomicInteger(0)
            val order = mutableListOf<Int>()
            val running = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.execute {
                    active.incrementAndGet()
                    try {
                        awaitCancellation()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
            val waiting = (1..4).map { index ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute {
                        val count = active.incrementAndGet()
                        maximum.updateAndGet { current -> maxOf(current, count) }
                        try {
                            order += index
                            yield()
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }

            waiting[0].cancelAndJoin()
            waiting[2].cancelAndJoin()
            assertEquals(1, active.get())
            assertTrue(order.isEmpty())
            running.cancelAndJoin()
            waiting.forEach { it.join() }
            coordinator.execute { order += 5 }

            assertEquals(listOf(2, 4, 5), order)
            assertEquals(1, maximum.get())
            assertEquals(0, active.get())
        }
    }

    @Test
    fun `releases request slot when block throws`() = runBlocking {
        val coordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
        runCatching { coordinator.execute<Unit> { error("expected") } }

        var entered = false
        withTimeout(500) { coordinator.execute { entered = true } }

        assertTrue(entered)
    }
}
