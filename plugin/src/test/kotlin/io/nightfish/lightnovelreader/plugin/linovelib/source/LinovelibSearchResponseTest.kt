package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibSearchResponseTest {
    private val parser = LinovelibHtmlParser()

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
        assertEquals(listOf("SAO"), requested)
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
