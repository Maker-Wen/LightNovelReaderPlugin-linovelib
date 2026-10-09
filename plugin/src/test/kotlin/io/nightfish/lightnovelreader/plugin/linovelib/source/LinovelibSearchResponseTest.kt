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
    fun `finds direct book id from canonical open graph url`() {
        val response = LinovelibSearchResponse(
            finalUrl = "https://www.bilinovel.com/search.html?searchkey=example",
            html = """
                <html><head>
                  <meta property="og:url" content="https://www.bilinovel.com/novel/3768.html">
                </head></html>
            """.trimIndent()
        )

        assertEquals("3768", response.directBookId(parser))
    }

    @Test
    fun `redirected search emits direct book and end`() = runBlocking {
        val provider = LinovelibSearchProvider(
            htmlLoader = { "" },
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
    }

    @Test
    fun `empty result list is distinct from failed or unrecognized search responses`() = runBlocking {
        for ((html, expectedEmpty) in listOf(
            "<ol class=\"book-ol\"></ol>" to true,
            "<html>Access denied</html>" to false,
            "<ol class=\"book-ol\"><li class=\"book-li\">Invalid book</li></ol>" to false
        )) {
            val provider = LinovelibSearchProvider(
                htmlLoader = { error("Empty search must not request details") },
                searchHtmlLoader = { LinovelibSearchResponse("${LinovelibUrls.HOST}/search.html", html) },
                parser = parser,
                diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
            )

            val results = provider.search(provider.searchTypes.single(), "query").toList()

            assertEquals(expectedEmpty, results.first() is SearchResult.Empty)
            assertEquals(!expectedEmpty, results.first() is SearchResult.Error)
            assertTrue(results.last() is SearchResult.End)
        }

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
