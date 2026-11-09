package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LinovelibRecentHtmlTest {
    @Test
    fun overlappingLoadsShareTheResponse() = runBlocking {
        withTimeout(2_000) {
            val cache = LinovelibRecentHtml(accepts = { true })
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load {
                    release.await()
                    "shared page"
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load { error("The first load is still pending") }
            }

            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals("shared page", first.await())
            assertEquals("shared page", second.await())
            assertEquals("shared page", cache.load { error("The page is cached") })
        }
    }

    @Test
    fun cacheExpiresFifteenSecondsAfterCompletionWithoutSliding() = runBlocking {
        var now = 1_000L
        val cache = LinovelibRecentHtml(accepts = { true }, nowMillis = { now })
        assertEquals("first page", cache.load {
            now = 2_000L
            "first page"
        })

        now = 16_999L
        assertEquals("first page", cache.load { error("The page is still fresh") })
        now = 17_000L
        assertEquals("updated page", cache.load { "updated page" })
    }

    @Test
    fun forcedLoadSkipsTheStoredPageAndSharesPendingFetch() = runBlocking {
        withTimeout(2_000) {
            val cache = LinovelibRecentHtml(accepts = { true })
            cache.load { "stored page" }
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load(allowCached = false) {
                    release.await()
                    "fresh page"
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load(allowCached = false) { error("The fresh load is already pending") }
            }

            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals("fresh page", first.await())
            assertEquals("fresh page", second.await())
            assertEquals("fresh page", cache.load { error("The latest page is cached") })
        }
    }

    @Test
    fun invalidHtmlReachesWaitersWithoutBeingCached() = runBlocking {
        withTimeout(2_000) {
            val cache = LinovelibRecentHtml(accepts = { it == "recognized page" })
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load {
                    release.await()
                    "challenge page"
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load { error("The first load is still pending") }
            }

            release.complete(Unit)
            assertEquals("challenge page", first.await())
            assertEquals("challenge page", second.await())
            assertEquals("recognized page", cache.load { "recognized page" })
        }
    }

    @Test
    fun fetchFailureReachesWaitersAndAllowsLaterRetry() = runBlocking {
        withTimeout(2_000) {
            val cache = LinovelibRecentHtml(accepts = { true })
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    cache.load {
                        release.await()
                        throw IOException("unavailable")
                    }
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { cache.load { error("The first load is still pending") } }
            }

            release.complete(Unit)
            listOf(first, second).forEach {
                val failure = it.await().exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("unavailable", failure?.message)
            }
            assertEquals("recovered page", cache.load { "recovered page" })
        }
    }

    @Test
    fun cancellingTheOwnerLetsActiveWaitersShareOneReplacement() = runBlocking {
        withTimeout(2_000) {
            val cache = LinovelibRecentHtml(accepts = { true })
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load { awaitCancellation() }
            }
            var replacements = 0
            val waiters = List(2) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    cache.load {
                        replacements++
                        "replacement page"
                    }
                }
            }

            assertEquals(0, replacements)
            owner.cancelAndJoin()
            assertEquals(listOf("replacement page", "replacement page"), waiters.map { it.await() })
            assertEquals(1, replacements)
        }
    }

    @Test
    fun cancellingAWaiterDoesNotCancelTheOwner() = runBlocking {
        withTimeout(2_000) {
            val cache = LinovelibRecentHtml(accepts = { true })
            val release = CompletableDeferred<Unit>()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load {
                    release.await()
                    "owner page"
                }
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                cache.load { error("The owner load is still pending") }
            }

            waiter.cancelAndJoin()
            assertTrue(owner.isActive)
            release.complete(Unit)
            assertEquals("owner page", owner.await())
        }
    }
}
