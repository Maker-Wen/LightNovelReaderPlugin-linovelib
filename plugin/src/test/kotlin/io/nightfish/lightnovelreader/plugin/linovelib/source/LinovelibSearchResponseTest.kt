package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibSearchResponseTest {
    private val parser = LinovelibHtmlParser()

    @Test
    fun `each collection appends all pages even when the next page repeats a book`() = runBlocking {
        val requested = mutableListOf<String>()
        val first = LinovelibUrls.search(LinovelibUrls.HOST, "Book")
        val second = "${LinovelibUrls.HOST}/search/Book_2.html"
        val third = "${LinovelibUrls.HOST}/search/Book_3.html"
        val pages = mapOf(
            first to searchPage(listOf("1"), second),
            second to searchPage(listOf("1"), third),
            third to searchPage(listOf("2"))
        )
        val provider = testSearchProvider(
            htmlLoader = { error("ID search must not fetch details") },
            searchHtmlLoader = { url -> requested += url; LinovelibHtmlResponse(url, pages.getValue(url)) }
        )
        val flow = provider.searchBookIds("Book")

        repeat(2) {
            val results = flow.toList()
            assertEquals(listOf("1", "1", "2"), results.filterIsInstance<SearchResult.SingleBook>().map { it.bookId })
            assertEquals(4, results.size)
            assertTrue(results.last() is SearchResult.End)
        }
        assertEquals(listOf(first, second, third, first, second, third), requested)
    }

    @Test
    fun `later request or page failures retain emitted books and end with an error`() = runBlocking {
        for (failedHtml in listOf(null, "", "<h1>请完成验证</h1>")) {
            var requests = 0
            val provider = testSearchProvider(
                htmlLoader = { error("ID search must not fetch details") },
                searchHtmlLoader = { url ->
                    requests++
                    LinovelibHtmlResponse(url, if (requests == 1) {
                        searchPage(listOf("1"), "/search/Book_2.html")
                    } else failedHtml ?: error("Request failed"))
                }
            )
            val results = provider.searchBookIds("Book").toList()
            assertEquals(2, requests)
            assertEquals(3, results.size)
            assertEquals("1", (results[0] as SearchResult.SingleBook).bookId)
            assertTrue(results[1] is SearchResult.Error)
            assertTrue(results[2] is SearchResult.End)
        }
    }

    @Test
    fun `cancellation during pagination preserves prior emissions without error or end`() = runBlocking {
        val cancelled = CancellationException("Search cancelled")
        val results = mutableListOf<SearchResult>()
        val events = mutableListOf<String>()
        var requests = 0
        val provider = testSearchProvider(
            htmlLoader = { error("ID search must not fetch details") },
            searchHtmlLoader = { url ->
                requests++
                if (requests == 2) throw cancelled
                LinovelibHtmlResponse(url, searchPage(listOf("1"), "/search/Book_2.html"))
            }, diagnostics = LinovelibDiagnostics { _, _, message, _ -> events += message }
        )
        assertSame(cancelled, runCatching { provider.searchBookIds("Book").collect { results += it } }.exceptionOrNull())
        assertEquals(2, requests)
        assertEquals(1, results.size)
        assertTrue(results.single() is SearchResult.SingleBook)
        assertTrue(events.none { it.startsWith("SEARCH_REQUEST_ERROR") || it.startsWith("SEARCH_DONE") })

        requests = 0
        provider.searchBookIds("Book").take(1).toList()
        assertEquals(1, requests)
    }

    @Test
    fun `search preserves cross page entries and keeps the existing single page parsing`() = runBlocking {
        val requested = mutableListOf<String>()
        val nextUrl = "${LinovelibUrls.HOST}/search/Book_2.html"
        val provider = testSearchProvider(
            htmlLoader = { error("ID search must not fetch details") },
            searchHtmlLoader = { url ->
                requested += url
                LinovelibHtmlResponse(url, if (requested.size == 1) {
                    searchPage(listOf("1", "2", "1"), "/search/Book_2.html")
                } else {
                    searchPage(listOf("2", "3", "2"), "#")
                })
            }
        )

        val results = provider.searchBookIds("Book").toList()

        assertEquals(listOf("1", "2", "2", "3"), results.filterIsInstance<SearchResult.SingleBook>().map { it.bookId })
        assertEquals(listOf("${LinovelibUrls.HOST}/search.html?searchkey=Book", nextUrl), requested)
        assertTrue(results.last() is SearchResult.End)
        assertEquals(1, results.count { it is SearchResult.End })
    }

    @Test
    fun `search page metadata does not replace the returned search results`() = runBlocking {
        val provider = testSearchProvider(
            htmlLoader = { error("ID search must not fetch details") },
            searchHtmlLoader = {
                LinovelibHtmlResponse(
                    finalUrl = "https://www.bilinovel.com/search.html?searchkey=example",
                    html = """
                        <html><head>
                          <meta property="og:url" content="https://www.bilinovel.com/novel/3768.html">
                        </head><body><ol class="book-ol"><li class="book-li">
                          <a class="book-layout" href="/novel/1667.html"><h4 class="book-title">SAO</h4></a>
                        </li></ol></body></html>
                    """.trimIndent()
                )
            },
            parser = parser,
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )

        val results = provider.searchBookIds("example").toList()

        assertEquals(2, results.size)
        assertEquals("1667", (results[0] as SearchResult.SingleBook).bookId)
        assertTrue(results[1] is SearchResult.End)
    }

    @Test
    fun `redirected search emits direct book and end without a second request`() = runBlocking {
        val detailRequests = mutableListOf<String>()
        val provider = testSearchProvider(
            htmlLoader = { url -> detailRequests += url; "" },
            searchHtmlLoader = {
                LinovelibHtmlResponse(
                    finalUrl = "https://www.bilinovel.com/novel/3768.html",
                    html = "<html><body></body></html>"
                )
            },
            parser = parser,
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )

        val results = provider.search(provider.searchTypes.single(), "男性禁入").toList()

        assertEquals(2, results.size)
        assertTrue(results[0] is SearchResult.SingleBook)
        assertEquals("3768", (results[0] as SearchResult.SingleBook).bookId)
        assertTrue(results[1] is SearchResult.End)
        assertTrue(detailRequests.isEmpty())
    }

    @Test
    fun `provider normalizes Unicode whitespace in user queries`() = runBlocking {
        val requested = mutableListOf<String>()
        val provider = testSearchProvider(
            htmlLoader = { error("ID-only search must not fetch details") },
            searchHtmlLoader = { query ->
                requested += query
                LinovelibHtmlResponse("${LinovelibUrls.HOST}/novel/3768.html", "")
            }
        )

        val direct = provider.searchBookIds("\u3000 3247 \u2003").toList()
        assertEquals("3247", (direct.first() as SearchResult.SingleBook).bookId)
        val empty = provider.searchBookIds(" \u3000\u2003 ").toList()
        assertTrue(empty.first() is SearchResult.Empty)
        assertTrue(requested.isEmpty())

        val redirected = provider.searchBookIds("\u3000 SAO \u2003").toList()
        assertEquals(listOf(LinovelibUrls.search(LinovelibUrls.HOST, "SAO")), requested)
        assertEquals("3768", (redirected.first() as SearchResult.SingleBook).bookId)
    }

    @Test
    fun `failed search requests emit error and end`() = runBlocking {
        val provider = testSearchProvider(
            htmlLoader = { "" },
            searchHtmlLoader = { error("Search request failed") },
            parser = parser,
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        val results = provider.search(provider.searchTypes.single(), "query").toList()
        assertTrue(results.first() is SearchResult.Error)
        assertTrue(results.last() is SearchResult.End)
    }
}
