package io.nightfish.lightnovelreader.plugin.linovelib.source

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

    private fun book(id: String, title: String = "Book $id") =
        """<a class="book-layout" href="/novel/$id.html"><span class="book-title">$title</span></a>"""
}
