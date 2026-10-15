package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LinovelibHttpClientRequestTest {
    @Test
    fun `ordinary and chapter requests preserve their headers and merge response cookies`() = runBlocking {
        LinovelibHttpTestSite { exchange ->
            when (exchange.requestURI.path) {
                "/seed" -> {
                    exchange.responseHeaders.add("Set-Cookie", "session=normal; Path=/")
                    exchange.responseHeaders.add("Set-Cookie", "night=1; Path=/")
                }
                "/chapter" -> exchange.responseHeaders.add("Set-Cookie", "chapter=read; Path=/")
            }
            exchange.respond(200, "Page")
        }.use { site ->
            val client = site.client()
            assertEquals("Page", client.loadHtml("${site.host}/seed"))
            val chapter = client.loadChapterPage("${site.host}/chapter")
            client.loadHtml("${site.host}/after")

            assertEquals("${site.host}/chapter", chapter.finalUrl)
            assertEquals("Page", chapter.html)
            val normal = site.requests[0]
            assertTrue(normal.headers.getValue("user-agent").contains("Chrome/125 Mobile"))
            assertEquals(site.host, normal.headers["referer"])
            assertEquals("zh-CN,zh;q=0.9,en;q=0.7", normal.headers["accept-language"])
            assertEquals("*/*", normal.headers["accept"])
            assertFalse(normal.headers.containsKey("cache-control"))
            val content = site.requests[1]
            assertTrue(content.headers.getValue("user-agent").contains("Chrome/135.0.0.0"))
            assertEquals("${site.host}/", content.headers["referer"])
            assertEquals("no-cache", content.headers["cache-control"])
            assertEquals(mapOf("session" to "normal", "night" to "0"), content.cookies)
            assertEquals(mapOf("session" to "normal", "night" to "1", "chapter" to "read"), site.requests[2].cookies)
        }
    }

    @Test
    fun `search obtains fresh guards and tickets and isolates cookies through redirects`() = runBlocking {
        val guards = AtomicInteger()
        LinovelibHttpTestSite { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            when {
                exchange.requestURI.path == "/seed" -> {
                    exchange.responseHeaders.add("Set-Cookie", "session=normal; Path=/")
                    exchange.responseHeaders.add("Set-Cookie", "night=1; Path=/")
                    exchange.respond(200)
                }
                query == "search_guard=js" -> {
                    val guard = guards.incrementAndGet()
                    exchange.responseHeaders.add("Set-Cookie", "guardLeak=ignored; Path=/")
                    exchange.respond(200, "document.cookie=\"jieqiSearchJs=js-$guard; path=/\";", "application/javascript")
                }
                query == "search_guard=css" -> {
                    exchange.responseHeaders.add("Set-Cookie", "jieqiSearchCss=css-${guards.get()}; Path=/")
                    exchange.respond(200, "body{}", "text/css")
                }
                query.startsWith("search_guard=redeem&r=") -> {
                    exchange.responseHeaders.add("Set-Cookie", "jieqiSearchTicket=ticket-${guards.get()}; Path=/")
                    exchange.respond(200)
                }
                query.startsWith("searchkey=") -> {
                    exchange.responseHeaders.add("Location", "/novel/100.html")
                    exchange.respond(302)
                }
                else -> exchange.respond(200, "<h1 class=book-title>Book</h1>")
            }
        }.use { site ->
            val client = site.client()
            client.loadHtml("${site.host}/seed")
            repeat(2) { attempt ->
                val result = client.search("川原 礫&+")
                assertEquals("${site.host}/novel/100.html", result.finalUrl)
                assertEquals("<h1 class=book-title>Book</h1>", result.html)
                val requests = site.requests.drop(1 + attempt * 5).take(5)
                assertEquals(listOf("/search.html", "/search.html", "/search.html", "/search.html", "/novel/100.html"), requests.map { it.path })
                assertEquals("search_guard=js", requests[0].query)
                assertEquals("search_guard=css", requests[1].query)
                assertTrue(requests[2].query.orEmpty().startsWith("search_guard=redeem&r="))
                assertEquals("searchkey=川原 礫&+", URLDecoder.decode(requests[3].query, "UTF-8"))
                requests.forEach {
                    assertTrue(it.headers.getValue("user-agent").contains("Chrome/135.0.0.0"))
                    assertEquals("${site.host}/", it.headers["referer"])
                    assertEquals("no-cache", it.headers["cache-control"])
                }
                assertTrue(requests[0].cookies.isEmpty())
                assertTrue(requests[1].cookies.isEmpty())
                val guard = attempt + 1
                assertEquals(mapOf("night" to "0", "jieqiSearchJs" to "js-$guard", "jieqiSearchCss" to "css-$guard"), requests[2].cookies)
                assertEquals(mapOf("jieqiSearchTicket" to "ticket-$guard"), requests[3].cookies)
                assertEquals(requests[3].cookies, requests[4].cookies)
            }
            client.loadHtml("${site.host}/after")
            assertEquals(mapOf("session" to "normal", "night" to "1"), site.requests.last().cookies)
            assertEquals(2, guards.get())
        }
    }

    @Test
    fun `HTTP failures retain status and preserve retry counts without changing availability`() = runBlocking {
        for ((status, attempts) in listOf(403 to 1, 404 to 1, 429 to 4, 500 to 4)) {
            val events = CopyOnWriteArrayList<String>()
            LinovelibHttpTestSite { exchange ->
                if (status == 429) exchange.responseHeaders.add("Retry-After", "0")
                exchange.respond(status)
            }.use { site ->
                val client = site.client(LinovelibDiagnostics { _, _, message, _ -> events += message })
                val failure = runCatching { client.loadHtml("${site.host}/resource") }.exceptionOrNull()
                assertTrue(failure is LinovelibHttpStatusException)
                assertEquals(status, (failure as LinovelibHttpStatusException).statusCode)
                assertEquals("${site.host}/resource", failure.url)
                assertEquals(attempts, site.requests.size)
                val retries = events.filter { it.startsWith("HTTP_RETRY") }
                assertEquals(attempts - 1, retries.size)
                if (status == 500) {
                    listOf(1_000, 2_000, 4_000).forEachIndexed { index, delay ->
                        assertTrue(retries[index].contains("delayMs=$delay"))
                    }
                }
                assertFalse(client.isOffLineFlow.value)
            }
        }
    }

    @Test
    fun `only the final failed probe publishes offline and a successful request recovers it`() = runBlocking {
        for (status in listOf(404, 429, 500)) {
            val rootStatus = AtomicInteger(status)
            val observedStates = CopyOnWriteArrayList<Boolean>()
            lateinit var client: LinovelibHttpClient
            LinovelibHttpTestSite { exchange ->
                observedStates += client.isOffLineFlow.value
                if (exchange.requestURI.path == "/") {
                    exchange.responseHeaders.add("Retry-After", "0")
                    exchange.respond(rootStatus.get())
                } else {
                    exchange.respond(200)
                }
            }.use { site ->
                client = site.client()
                assertFalse(client.isOffLineFlow.value)
                assertTrue(client.checkOffline())
                assertTrue(client.isOffLineFlow.value)
                assertEquals(if (status == 404) 1 else 4, site.requests.size)
                assertTrue(observedStates.none { it })
                client.loadHtml("${site.host}/success")
                assertFalse(client.isOffLineFlow.value)
                rootStatus.set(200)
                assertFalse(client.checkOffline())
                assertFalse(client.isOffLineFlow.value)
            }
        }
    }

    @Test
    fun `exhausted transport failures propagate and only an active probe marks offline`() = runBlocking {
        withTimeout(5_000) {
            val events = CopyOnWriteArrayList<String>()
            LinovelibHttpTestSite { it.close() }.use { site ->
                val client = site.client(LinovelibDiagnostics { _, _, message, _ -> events += message })
                assertTrue(runCatching { client.loadHtml("${site.host}/resource") }.exceptionOrNull() is IOException)
                assertEquals(3, events.count { it.startsWith("HTTP_RETRY") })
                assertFalse(client.isOffLineFlow.value)
                events.clear()
                assertTrue(client.checkOffline())
                assertEquals(3, events.count { it.startsWith("HTTP_RETRY") })
                assertTrue(client.isOffLineFlow.value)
            }
        }
    }

    @Test
    fun `programming failures are propagated without retries or offline publication`() = runBlocking {
        val events = mutableListOf<String>()
        val client = LinovelibHttpClient(
            host = "not-a-url", diagnostics = LinovelibDiagnostics { _, _, message, _ -> events += message },
            requestCoordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
        )
        assertTrue(runCatching { client.checkOffline() }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(client.isOffLineFlow.value)
        assertTrue(events.none { it.startsWith("HTTP_RETRY") })
    }

    @Test
    fun `source instances do not share session cookies`() = runBlocking {
        LinovelibHttpTestSite { exchange ->
            if (exchange.requestURI.path == "/seed") exchange.responseHeaders.add("Set-Cookie", "owner=first; Path=/")
            exchange.respond(200)
        }.use { site ->
            val first = site.client()
            val second = site.client()
            first.loadHtml("${site.host}/seed")
            second.loadHtml("${site.host}/plain")
            first.loadHtml("${site.host}/plain")
            assertTrue(site.requests[1].cookies.isEmpty())
            assertEquals(mapOf("owner" to "first"), site.requests[2].cookies)
        }
    }

    @Test
    fun `missing guards stop search at the failed stage without marking offline`() = runBlocking {
        for (missingStage in 1..3) {
            val stages = AtomicInteger()
            LinovelibHttpTestSite { exchange ->
                val stage = stages.incrementAndGet()
                if (stage < missingStage && stage == 2) exchange.responseHeaders.add("Set-Cookie", "jieqiSearchCss=css; Path=/")
                if (stage < missingStage && stage == 3) exchange.responseHeaders.add("Set-Cookie", "jieqiSearchTicket=ticket; Path=/")
                exchange.respond(200, if (stage == 1 && stage < missingStage) "jieqiSearchJs=js" else "Missing guard")
            }.use { site ->
                val client = site.client()
                assertTrue(runCatching { client.search("Book") }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(missingStage, site.requests.size)
                assertFalse(client.isOffLineFlow.value)
            }
        }
    }
}
