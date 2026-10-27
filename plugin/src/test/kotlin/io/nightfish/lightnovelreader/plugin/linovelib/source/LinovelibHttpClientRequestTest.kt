package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.toList
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
import java.util.concurrent.atomic.AtomicLong

class LinovelibHttpClientRequestTest {
    @Test
    fun `each search page and retried result request redeem a new one use ticket`() = runBlocking {
        val guards = AtomicInteger()
        val pageTwoAttempts = AtomicInteger()
        val usedTickets = mutableSetOf<String>()
        LinovelibHttpTestSite { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            when {
                query == "search_guard=js" -> {
                    val guard = guards.incrementAndGet()
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
                else -> {
                    val ticket = exchange.requestHeaders.getFirst("Cookie").orEmpty()
                    when {
                        !usedTickets.add(ticket) -> exchange.respond(200, "")
                        query.startsWith("searchkey=") -> exchange.respond(200, searchPage(listOf("1"), "/search/Book_2.html"))
                        pageTwoAttempts.incrementAndGet() == 1 -> exchange.respond(500)
                        else -> exchange.respond(200, searchPage(listOf("2"), "#"))
                    }
                }
            }
        }.use { site ->
            val client = site.client()
            val provider = testSearchProvider(
                htmlLoader = { error("ID search must not fetch details") },
                searchHtmlLoader = client::search,
                parser = LinovelibHtmlParser(site.host), host = site.host
            )

            val results = provider.searchBookIds("Book").toList()

            assertEquals(listOf("1", "2"), results.filterIsInstance<SearchResult.SingleBook>().map { it.bookId })
            assertTrue(results.none { it is SearchResult.Error })
            assertTrue(results.last() is SearchResult.End)
            assertEquals(3, guards.get())
            val resultRequests = site.requests.filter { it.query?.startsWith("search_guard=") != true }
            assertEquals(listOf("ticket-1", "ticket-2", "ticket-3"), resultRequests.map { it.cookies["jieqiSearchTicket"] })
            assertEquals(listOf("/search.html", "/search/Book_2.html", "/search/Book_2.html"), resultRequests.map { it.path })
        }
    }

    @Test
    fun successfulResponsesIgnoreRetryAfterWithoutDelayingTheNextRequest() = runBlocking {
        for (status in listOf(200, 302)) {
            LinovelibHttpTestSite { exchange ->
                exchange.responseHeaders.add("Retry-After", "60")
                exchange.respond(status, "Page")
            }.use { site ->
                val client = LinovelibHttpClient(
                    host = site.host, diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
                    requestCoordinator = LinovelibRequestCoordinator(0) { 1_000_000L }
                )
                withTimeout(3_000) {
                    assertEquals("Page", client.loadHtml("${site.host}/first"))
                    assertEquals("Page", client.loadHtml("${site.host}/next"))
                }
                assertEquals(listOf("/first", "/next"), site.requests.map { it.path })
                assertFalse(client.isOffLineFlow.value)
            }
        }
    }

    @Test
    fun `the final rate limit still delays the next resource without adding retries`() = runBlocking {
        val attempts = AtomicInteger()
        val finalRateLimitAt = AtomicLong()
        val nextRequestAt = AtomicLong()
        LinovelibHttpTestSite { exchange ->
            if (exchange.requestURI.path == "/limited") {
                val attempt = attempts.incrementAndGet()
                if (attempt == 4) finalRateLimitAt.set(System.nanoTime())
                exchange.responseHeaders.add("Retry-After", if (attempt == 4) "1" else "0")
                exchange.respond(429)
            } else {
                nextRequestAt.set(System.nanoTime())
                exchange.respond(200, "Next resource")
            }
        }.use { site ->
            val client = LinovelibHttpClient(
                host = site.host, diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
                requestCoordinator = LinovelibRequestCoordinator(minimumRequestIntervalMillis = 0)
            )
            withTimeout(5_000) {
                val failure = runCatching { client.loadHtml("${site.host}/limited") }.exceptionOrNull()
                assertTrue(failure is LinovelibHttpStatusException)
                assertEquals("HTTP 429 for ${site.host}/limited", failure?.message)
                assertEquals(4, attempts.get())
                assertEquals("Next resource", client.loadHtml("${site.host}/next"))
            }
            val elapsedMillis = (nextRequestAt.get() - finalRateLimitAt.get()) / 1_000_000L
            assertTrue("Next resource started after only $elapsedMillis ms", elapsedMillis >= 950L)
            assertEquals(listOf("/limited", "/limited", "/limited", "/limited", "/next"), site.requests.map { it.path })
            assertFalse(client.isOffLineFlow.value)
        }
    }

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
                val result = client.search(LinovelibUrls.search(site.host, "川原 礫&+"))
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
                assertEquals("HTTP $status for ${site.host}/resource", failure?.message)
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
                exchange.respond(200, if (stage == 1 && stage < missingStage) "jieqiSearchJs=js" else "Missing guard")
            }.use { site ->
                val client = site.client()
                assertTrue(runCatching { client.search(LinovelibUrls.search(site.host, "Book")) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(missingStage, site.requests.size)
                assertFalse(client.isOffLineFlow.value)
            }
        }
    }
}
