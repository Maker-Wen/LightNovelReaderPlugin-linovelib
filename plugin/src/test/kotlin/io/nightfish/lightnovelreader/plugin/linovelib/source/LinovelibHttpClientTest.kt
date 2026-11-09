package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinovelibHttpClientTest {
    @Test
    fun `startup shares the homepage but explicit offline checks refresh it`() = runBlocking {
        val homepage = javaClass.getResource("/html/home-modules-20261027.html")!!.readText()
        val status = AtomicInteger(200)
        LinovelibHttpTestSite { exchange -> exchange.respond(status.get(), homepage) }.use { site ->
            val client = site.client()
            assertFalse(client.checkStartupOffline())
            assertEquals(homepage, client.loadHtml(site.host))
            assertEquals(1, site.requests.size)

            status.set(404)
            assertTrue(client.checkOffline())
            assertTrue(client.isOffLineFlow.value)
            assertEquals(2, site.requests.size)

            status.set(200)
            assertEquals(homepage, client.loadHtml(site.host))
            assertFalse(client.isOffLineFlow.value)
            assertEquals(3, site.requests.size)
        }
    }

    @Test
    fun `an older failed probe cannot overwrite a later successful request`() = runBlocking {
        withTimeout(5_000) {
            val probeLogged = CompletableDeferred<Unit>()
            val releaseProbe = CountDownLatch(1)
            LinovelibHttpTestSite { exchange -> exchange.respond(if (exchange.requestURI.path == "/") 404 else 200) }.use { site ->
                val client = site.client(LinovelibDiagnostics { _, _, message, _ ->
                    if (message.startsWith("HTTP_ERROR")) {
                        probeLogged.complete(Unit)
                        check(releaseProbe.await(5, TimeUnit.SECONDS))
                    }
                })
                val probe = async(Dispatchers.Default) { client.checkOffline() }
                try {
                    probeLogged.await()
                    assertTrue(client.isOffLineFlow.value)
                    client.loadHtml("${site.host}/success")
                    assertFalse(client.isOffLineFlow.value)
                    releaseProbe.countDown()
                    assertTrue(probe.await())
                    assertFalse(client.isOffLineFlow.value)
                } finally {
                    releaseProbe.countDown()
                    probe.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `an older successful request cannot overwrite a later failed probe`() = runBlocking {
        withTimeout(5_000) {
            val successLogged = CompletableDeferred<Unit>()
            val releaseSuccess = CountDownLatch(1)
            LinovelibHttpTestSite { exchange -> exchange.respond(if (exchange.requestURI.path == "/") 404 else 200) }.use { site ->
                val client = site.client(LinovelibDiagnostics { _, _, message, _ ->
                    if (message.startsWith("HTTP_OK")) {
                        successLogged.complete(Unit)
                        check(releaseSuccess.await(5, TimeUnit.SECONDS))
                    }
                })
                val request = async(Dispatchers.Default) { client.loadHtml("${site.host}/success") }
                try {
                    successLogged.await()
                    assertTrue(client.checkOffline())
                    assertTrue(client.isOffLineFlow.value)
                    releaseSuccess.countDown()
                    request.await()
                    assertTrue(client.isOffLineFlow.value)
                } finally {
                    releaseSuccess.countDown()
                    request.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `cancelling an active probe propagates and leaves availability unchanged`() = runBlocking {
        withTimeout(5_000) {
            val requested = CompletableDeferred<Unit>()
            val releaseResponse = CountDownLatch(1)
            LinovelibHttpTestSite { exchange ->
                requested.complete(Unit)
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                if (exchange.requestURI.path == "/") exchange.responseHeaders.add("Set-Cookie", "cancelled=ignored; Path=/")
                exchange.respond(200)
            }.use { site ->
                val client = site.client()
                val probe = async(Dispatchers.Default) { client.checkOffline() }
                try {
                    requested.await()
                    probe.cancel()
                    releaseResponse.countDown()
                    probe.join()
                    assertTrue(probe.isCancelled)
                    assertFalse(client.isOffLineFlow.value)
                    client.loadHtml("${site.host}/after")
                    assertTrue(site.requests.last().cookies.isEmpty())
                } finally {
                    releaseResponse.countDown()
                    probe.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `a missing resource fails its request without marking the source offline`() = runBlocking {
        LinovelibHttpTestSite { exchange ->
            val missing = exchange.requestURI.path == "/missing"
            exchange.respond(if (missing) 404 else 200, if (missing) "Missing" else "Available")
        }.use { site ->
            val client = LinovelibHttpClient(
                host = site.host,
                diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
                requestCoordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
            )
            assertEquals("Available", client.loadHtml(site.host))

            val failure = runCatching { client.loadHtml("${site.host}/missing") }.exceptionOrNull()

            assertTrue(failure is LinovelibHttpStatusException)
            assertEquals("HTTP 404 for ${site.host}/missing", failure?.message)
            assertFalse(client.isOffLineFlow.value)
        }
    }

}
