package io.nightfish.lightnovelreader.plugin.linovelib.source

import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LinovelibHttpClientTest {
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
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val missing = exchange.requestURI.path == "/missing"
            val body = if (missing) "Missing" else "Available"
            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (missing) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val host = "http://127.0.0.1:${server.address.port}"
            val client = LinovelibHttpClient(
                host = host,
                diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
                requestCoordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
            )
            assertEquals("Available", client.loadHtml(host))

            val failure = runCatching { client.loadHtml("$host/missing") }.exceptionOrNull()

            assertTrue(failure is LinovelibHttpStatusException)
            assertEquals(404, (failure as LinovelibHttpStatusException).statusCode)
            assertEquals("$host/missing", failure.url)
            assertFalse(client.isOffLineFlow.value)
        } finally {
            server.stop(0)
        }
    }

}
