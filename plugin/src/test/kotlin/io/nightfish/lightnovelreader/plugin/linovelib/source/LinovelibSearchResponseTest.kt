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
    fun `finds direct book id from redirected response url`() {
        val response = LinovelibSearchResponse(
            finalUrl = "https://www.bilinovel.com/novel/3768.html",
            html = "<html><body></body></html>"
        )

        assertEquals("3768", response.directBookId(parser))
    }

    @Test
    fun `search page metadata does not replace the returned search results`() = runBlocking {
        val provider = LinovelibSearchProvider(
            htmlLoader = { error("ID search must not fetch details") },
            searchHtmlLoader = {
                LinovelibSearchResponse(
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
        val provider = LinovelibSearchProvider(
            htmlLoader = { url -> detailRequests += url; "" },
            searchHtmlLoader = {
                LinovelibSearchResponse(
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
    fun `failed search requests emit error and end`() = runBlocking {
        val provider = LinovelibSearchProvider(
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
