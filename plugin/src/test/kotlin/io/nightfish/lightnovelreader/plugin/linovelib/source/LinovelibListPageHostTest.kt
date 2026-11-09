package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibListPageHostTest {
    private val parser = LinovelibHtmlParser()

    @Test
    fun searchAndExpandedPagesAgreeOnEmptyUnrecognizedAndValidLists() = runBlocking {
        val url = "${LinovelibUrls.HOST}/tag/1.html"
        listOf(
            """<ol class="book-ol"></ol>""" to SearchResult.Empty::class.java,
            "<html>Access denied</html>" to SearchResult.Error::class.java,
            """<ol class="book-ol"><li class="book-li">Invalid book</li></ol>""" to SearchResult.Error::class.java,
            """<ol class="book-ol"><a href="/novel/1.html">Unsupported layout</a></ol>""" to SearchResult.Error::class.java,
            """<ol class="book-ol"></ol>${book("1")}""" to SearchResult.MultipleBook::class.java
        ).forEach { (html, expected) ->
            val metadata = LinovelibMetadataStore()
            val search = testSearchProvider(
                htmlLoader = { error("List search must not fetch details") },
                searchHtmlLoader = { LinovelibHtmlResponse("${LinovelibUrls.HOST}/search.html", html) },
                parser = parser,
                diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
                metadata = metadata
            )
            val expanded = LinovelibLinkedExpandedPageDataSource("Books", url, { html }, parser,
                metadata = LinovelibMetadataStore())
            listOf(search.search(search.searchTypes.single(), "query").toList(), expanded.getResultFlow().toList()).forEach { results ->
                assertEquals(expected, results.first().javaClass)
                assertEquals(2, results.size)
                assertTrue(results.last() is SearchResult.End)
            }
            assertEquals(if (expected == SearchResult.MultipleBook::class.java) "Book 1" else null,
                metadata.fallbackBookInformation("1")?.title)
        }
    }

    private fun book(id: String, title: String = "Book $id") =
        """<a class="book-layout" href="/novel/$id.html"><span class="book-title">$title</span></a>"""
}
