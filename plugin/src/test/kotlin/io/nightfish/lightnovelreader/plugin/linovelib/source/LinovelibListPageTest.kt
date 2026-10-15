package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibListPageTest {
    private val parser = LinovelibHtmlParser()

    @Test
    fun distinguishesEmptyListsFromPagesWithUnusableBookCandidates() {
        val empty = requireNotNull(parser.parseListPage("""<ol class="book-ol"></ol>"""))
        assertTrue(empty.books.isEmpty())
        assertEquals(1, empty.lastPage)

        listOf(
            "",
            "<html>Access denied</html>",
            """<div id="pagelink">第1/3页</div>""",
            """<ol class="book-ol"><li class="book-li">Invalid book</li></ol>""",
            """<ol class="book-ol"><a href="/novel/1.html">Unsupported book layout</a></ol>""",
            """<ol class="book-ol">${book("1", " ")}</ol>"""
        ).forEach { html -> assertNull(html, parser.parseListPage(html)) }
    }

    @Test
    fun recognizesValidBooksOutsideTheListEvenWhenAnEmptyListIsPresent() {
        listOf(book("1"), """<ol class="book-ol"></ol>${book("1")}""").forEach { html ->
            val page = requireNotNull(parser.parseListPage(html))
            assertEquals(listOf("1"), page.books.map { it.id })
        }
    }

    @Test
    fun returnsOrderedDistinctBooksAndPaginationFromTheSamePage() {
        val html = """
            <ol class="book-ol">
              ${book("2", "First title")}
              <li class="book-li">Invalid book</li>
              ${book("1")}
              ${book("2", "Duplicate title")}
              ${book("3", " ")}
            </ol>
            <div id="pagelink">第1/9页<a class="last">7</a></div>
        """.trimIndent()

        val page = requireNotNull(parser.parseListPage(html))

        assertEquals(listOf("2", "1"), page.books.map { it.id })
        assertEquals("First title", page.books.first().title)
        assertEquals(7, page.lastPage)
    }

    @Test
    fun usesTheLastPageLinkThenPageTextThenSinglePageDefault() {
        listOf(
            """<div id="pagelink">第1/9页<a class="last">7</a></div>""" to 7,
            """<div id="pagelink">第2/9页<a class="last">末页</a></div>""" to 9,
            """<div id="pagelink">第2/4页</div>""" to 4,
            """<div id="pagelink">Unknown pagination</div>""" to 1,
            "" to 1
        ).forEach { (pagination, expectedLastPage) ->
            // Pagination is still available for a recognized empty page.
            val page = requireNotNull(parser.parseListPage("""$pagination<ol class="book-ol"></ol>"""))
            assertTrue(page.books.isEmpty())
            assertEquals(expectedLastPage, page.lastPage)
        }
    }

    @Test
    fun searchAndExpandedPagesAgreeOnEmptyUnrecognizedAndValidLists() = runBlocking {
        val url = "${LinovelibUrls.HOST}/tag/1.html"
        listOf(
            """<ol class="book-ol"></ol>""" to SearchResult.Empty::class.java,
            "<html>Access denied</html>" to SearchResult.Error::class.java,
            """<ol class="book-ol"><li class="book-li">Invalid book</li></ol>""" to SearchResult.Error::class.java,
            """<ol class="book-ol"><a href="/novel/1.html">Unsupported layout</a></ol>""" to SearchResult.Error::class.java,
            """<ol class="book-ol"></ol>${book("1")}""" to SearchResult.SingleBook::class.java
        ).forEach { (html, expected) ->
            val metadata = LinovelibMetadataStore()
            val search = LinovelibSearchProvider(
                htmlLoader = { error("ID-only search must not fetch details") },
                searchHtmlLoader = { LinovelibSearchResponse("${LinovelibUrls.HOST}/search.html", html) },
                parser = parser,
                diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
                metadata = metadata
            )
            val expanded = LinovelibLinkedExpandedPageDataSource("Books", url, { html }, parser)
            listOf(search.searchBookIds("query").toList(), expanded.getResultFlow().toList()).forEach { results ->
                assertEquals(expected, results.first().javaClass)
                assertEquals(2, results.size)
                assertTrue(results.last() is SearchResult.End)
            }
            assertEquals(if (expected == SearchResult.SingleBook::class.java) "Book 1" else null,
                metadata.fallbackBookInformation("1")?.title)
        }
    }

    private fun book(id: String, title: String = "Book $id") =
        """<a class="book-layout" href="/novel/$id.html"><span class="book-title">$title</span></a>"""
}
