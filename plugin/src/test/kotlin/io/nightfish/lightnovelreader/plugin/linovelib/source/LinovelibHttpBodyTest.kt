package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LinovelibHttpBodyTest {
    @Test
    fun allPageEntryPointsRetryBrokenBodyWithoutPublishingCookies() = runBlocking {
        for (entry in Entry.entries) {
            val events = CopyOnWriteArrayList<String>()
            RawSite { request, output ->
                if (request.number == 1) brokenBody(output, mapOf("bodyBad" to "ignored"))
                else completeBody(output, "Complete page", cookies = mapOf("session" to "complete"))
            }.use { site ->
                val client = site.client(events)
                assertComplete(entry, client, site.host)

                assertEquals("$entry request count", 2, site.requests.size)
                assertEquals("$entry retry count", 1, retryCount(events))
                assertFalse(client.isOffLineFlow.value)
                assertFalse(site.requests[1].cookies.containsKey("bodyBad"))
                assertEquals(site.requests[0].target, site.requests[1].target)
                client.loadHtml("${site.host}/after")
                assertEquals(mapOf("session" to "complete"), site.requests.last().cookies)
            }
        }
    }

    @Test
    fun allPageEntryPointsExhaustFourBrokenBodiesAndOnlyProbesMarkOffline() = runBlocking {
        for (entry in Entry.entries) {
            val events = CopyOnWriteArrayList<String>()
            RawSite { _, output -> brokenBody(output) }.use { site ->
                val client = site.client(events)
                if (entry == Entry.PROBE) {
                    assertTrue(client.checkOffline())
                } else {
                    val failure = runCatching { invokePage(entry, client, site.host) }.exceptionOrNull()
                    assertTrue("$entry must propagate IOException, got $failure", failure is IOException)
                }

                assertEquals("$entry request count", 4, site.requests.size)
                assertEquals("$entry retry count", 3, retryCount(events))
                assertEquals(entry == Entry.PROBE, client.isOffLineFlow.value)
                assertTrue(events.none { it.startsWith("HTTP_OK") })
            }
        }
    }

    @Test
    fun previouslyOfflineSourceRecoversOnlyAfterCompleteSuccessfulBody() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        RawSite { request, output ->
            when (request.target) {
                "/" -> completeBody(output, "Unavailable", status = 404)
                "/broken" -> brokenBody(output, mapOf("bodyBad" to "ignored"))
                else -> completeBody(output, "Complete page")
            }
        }.use { site ->
            val client = site.client(events)
            assertTrue(client.checkOffline())
            assertTrue(client.isOffLineFlow.value)
            val failure = runCatching { client.loadHtml("${site.host}/broken") }.exceptionOrNull()
            assertTrue("Broken body must propagate IOException, got $failure", failure is IOException)
            assertTrue(client.isOffLineFlow.value)
            assertEquals(5, site.requests.size)
            assertEquals(3, retryCount(events))

            assertEquals("Complete page", client.loadHtml("${site.host}/complete"))
            assertFalse(client.isOffLineFlow.value)
            assertTrue(site.requests.last().cookies.isEmpty())
        }
    }

    @Test
    fun searchRetriesOnlyBrokenStageBeforeUsingCompleteGuardCookies() = runBlocking {
        for (brokenStage in listOf("js", "css", "redeem", "results")) {
            val events = CopyOnWriteArrayList<String>()
            val failures = AtomicInteger()
            RawSite { request, output ->
                val stage = stage(request.target)
                if (stage == brokenStage && failures.getAndIncrement() == 0) {
                    brokenBody(output, mapOf("bodyBad" to "ignored", "jieqiSearchCss" to "bad-css", "jieqiSearchTicket" to "bad-ticket"))
                } else {
                    when (stage) {
                        "js" -> completeBody(output, "document.cookie=\"jieqiSearchJs=js-good; path=/\";", contentType = "application/javascript")
                        "css" -> completeBody(output, "body{}", cookies = mapOf("jieqiSearchCss" to "css-good"), contentType = "text/css")
                        "redeem" -> completeBody(output, "Redeemed", cookies = mapOf("jieqiSearchTicket" to "ticket-good"))
                        "results" -> completeBody(output, "Complete results")
                        else -> completeBody(output, "Complete page")
                    }
                }
            }.use { site ->
                val client = site.client(events)
                val response = client.search("Book")
                assertEquals("Complete results", response.html)
                assertEquals("${site.host}/search.html?searchkey=Book", response.finalUrl)
                val expectedStages = listOf("js", "css", "redeem", "results").flatMap {
                    if (it == brokenStage) listOf(it, it) else listOf(it)
                }
                assertEquals(expectedStages, site.requests.map { stage(it.target) })
                assertEquals(1, retryCount(events))
                assertFalse(client.isOffLineFlow.value)
                site.requests.forEach { request ->
                    val expectedCookies = when (stage(request.target)) {
                        "redeem" -> mapOf("night" to "0", "jieqiSearchJs" to "js-good", "jieqiSearchCss" to "css-good")
                        "results" -> mapOf("jieqiSearchTicket" to "ticket-good")
                        else -> emptyMap()
                    }
                    assertEquals("$brokenStage: ${request.target}", expectedCookies, request.cookies)
                }
                val repeated = site.requests.filter { stage(it.target) == brokenStage }
                assertEquals(repeated[0].target, repeated[1].target)
                client.loadHtml("${site.host}/after")
                assertTrue(site.requests.last().cookies.isEmpty())
            }
        }
    }

    @Test
    fun cancellingProbeWithUnfinishedBodyPreservesStateAndFreesNextRequest() = runBlocking {
        withTimeout(5_000) {
            val partialBodySent = CountDownLatch(1)
            val finishBody = CountDownLatch(1)
            val events = CopyOnWriteArrayList<String>()
            RawSite { request, output ->
                when (request.number) {
                    1 -> completeBody(output, "Unavailable", status = 404)
                    2 -> {
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                            "Transfer-Encoding: chunked\r\nConnection: close\r\n" +
                            "Set-Cookie: cancelled=ignored; Path=/\r\n\r\n10\r\npartial").toByteArray(Charsets.US_ASCII))
                        output.flush()
                        partialBodySent.countDown()
                        check(finishBody.await(5, TimeUnit.SECONDS)) { "Cancelled response was not released" }
                        output.write(" body end\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                    else -> completeBody(output, "Complete page")
                }
            }.use { site ->
                val client = site.client(events)
                assertTrue(client.checkOffline())
                assertTrue(client.isOffLineFlow.value)
                val probe = async(Dispatchers.Default) { client.checkOffline() }
                try {
                    assertTrue("Probe must receive headers and a partial body", partialBodySent.await(5, TimeUnit.SECONDS))
                    probe.cancel()
                    finishBody.countDown()
                    probe.join()
                    assertTrue(probe.isCancelled)
                    assertTrue(client.isOffLineFlow.value)
                    assertEquals(2, site.requests.size)
                    assertEquals(0, retryCount(events))
                    assertTrue(events.none { it.startsWith("HTTP_OK") })

                    assertEquals("Complete page", client.loadHtml("${site.host}/after"))
                    assertEquals(3, site.requests.size)
                    assertTrue(site.requests.last().cookies.isEmpty())
                    assertFalse(client.isOffLineFlow.value)
                } finally {
                    finishBody.countDown()
                    probe.cancelAndJoin()
                }
            }
        }
    }

    private enum class Entry { HTML, CHAPTER, PROBE }

    private suspend fun invokePage(entry: Entry, client: LinovelibHttpClient, host: String): String = when (entry) {
        Entry.HTML -> client.loadHtml("$host/page")
        Entry.CHAPTER -> client.loadChapterPage("$host/page").also { assertEquals("$host/page", it.finalUrl) }.html
        Entry.PROBE -> error("Probe has its own Boolean result")
    }

    private suspend fun assertComplete(entry: Entry, client: LinovelibHttpClient, host: String) {
        if (entry == Entry.PROBE) assertFalse(client.checkOffline())
        else assertEquals("Complete page", invokePage(entry, client, host))
    }

    private fun retryCount(events: List<String>): Int = events.count { it.startsWith("HTTP_RETRY") }

    private fun stage(target: String): String = when {
        target.contains("search_guard=js") -> "js"
        target.contains("search_guard=css") -> "css"
        target.contains("search_guard=redeem") -> "redeem"
        target.contains("searchkey=") -> "results"
        else -> "other"
    }

    private fun brokenBody(output: OutputStream, cookies: Map<String, String> = emptyMap()) {
        val headers = cookies.entries.joinToString("") { (name, value) -> "Set-Cookie: $name=$value; Path=/\r\n" }
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
            "Transfer-Encoding: chunked\r\nConnection: close\r\n$headers\r\n3\r\nabc\r\nnot-hex\r\n").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun completeBody(
        output: OutputStream,
        body: String,
        status: Int = 200,
        cookies: Map<String, String> = emptyMap(),
        contentType: String = "text/html; charset=UTF-8"
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = cookies.entries.joinToString("") { (name, value) -> "Set-Cookie: $name=$value; Path=/\r\n" }
        output.write(("HTTP/1.1 $status Response\r\nContent-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n$headers\r\n").toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private data class RecordedRequest(val number: Int, val target: String, val headers: Map<String, String>) {
        val cookies: Map<String, String> get() = headers["cookie"].orEmpty().split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
    }

    private class RawSite(private val handler: (RecordedRequest, OutputStream) -> Unit) : Closeable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val connections = CopyOnWriteArrayList<Socket>()
        val host = "http://127.0.0.1:${server.localPort}"
        private val worker = Thread({
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        connections += socket
                        try {
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val line = reader.readLine() ?: error("Missing HTTP request line")
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = reader.readLine() ?: error("Incomplete HTTP request headers")
                                if (header.isEmpty()) break
                                val split = header.indexOf(':')
                                check(split > 0) { "Invalid HTTP request header" }
                                headers[header.substring(0, split).lowercase()] = header.substring(split + 1).trim()
                            }
                            val request = RecordedRequest(requests.size + 1, line.split(' ')[1], headers)
                            requests += request
                            handler(request, socket.getOutputStream())
                        } finally {
                            connections -= socket
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (!(server.isClosed && failure is SocketException)) failures += failure
            }
        }, "LinovelibHttpBodyTest-server").apply { isDaemon = true; start() }

        fun client(events: MutableList<String>): LinovelibHttpClient {
            val clock = AtomicLong(1_000_000)
            return LinovelibHttpClient(
                host = host,
                diagnostics = LinovelibDiagnostics { _, _, message, _ -> events += message },
                requestCoordinator = LinovelibRequestCoordinator(0) { clock.addAndGet(60_001) }
            )
        }

        override fun close() {
            server.close()
            connections.forEach { it.close() }
            worker.join(1_000)
            assertFalse("HTTP test server did not stop", worker.isAlive)
            failures.firstOrNull()?.let { throw AssertionError("HTTP test server failed", it) }
        }
    }
}
