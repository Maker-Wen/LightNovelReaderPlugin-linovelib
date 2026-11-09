package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.ContextWrapper
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class LinovelibListCardRefreshHostTest {
    @Test
    fun openingABookFillsMissingFieldsInExistingAndNewListCards() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val requests = mutableListOf<String>()
        val host = LinovelibUrls.HOST
        val detailUrl = LinovelibUrls.book(host, "1")
        val expandedUrl = LinovelibUrls.wenku(host, "lastupdate", 1)
        val firstSearchUrl = LinovelibUrls.search(host, "Book")
        val secondSearchPath = "/search/Book_2.html"
        val secondSearchUrl = "$host$secondSearchPath"
        val loadHtml: suspend (String) -> String = { url ->
            requests += url
            when (url) {
                expandedUrl -> row("1", "Library title", "16.1万") + row("2", "Library other", "8万")
                detailUrl -> """
                    <h1 class="book-title">Full title</h1>
                    <span class="authorname"><a href="/author/full.html">Full author</a></span>
                    <div id="bookSummary"><content>Full synopsis</content></div>
                    <div class="book-detail-info"><span class="book-meta book-layout-inline">72.1万字</span></div>
                    <div class="book-status">最后更新 2026-10-20</div>
                """.trimIndent()
                else -> error("Unexpected page: $url")
            }
        }
        val provider = testSearchProvider(
            htmlLoader = loadHtml,
            searchHtmlLoader = { url ->
                requests += url
                val html = when (url) {
                    firstSearchUrl -> row("1", "Search title", "14.6万") +
                        """<div id="pagelink"><a class="next" href="$secondSearchPath">下一页</a></div>"""
                    secondSearchUrl -> row("1", "Repeated title") + row("2", "Search other", "7.2万")
                    else -> error("Unexpected search page: $url")
                }
                LinovelibHtmlResponse(url, html)
            },
            metadata = metadata
        )
        val expanded = LinovelibLinkedExpandedPageDataSource(
            "文库", expandedUrl, loadHtml, LinovelibHtmlParser(), metadata = metadata
        )
        val source = LinovelibWebDataSource(
            object : ContextWrapper(null) {
                override fun getFilesDir() = File("/data/local/tmp/linovelib-list-card-refresh-unused")
            }, metadata, LinovelibDiagnostics { _, _, _, _ -> }, loadHtml
        )

        val searchCards = provider.search(provider.searchTypes.single(), "Book").toList().cards()
        val expandedCards = expanded.getResultFlow().toList().cards()
        val cards = searchCards + expandedCards
        val coldRequests = listOf(firstSearchUrl, secondSearchUrl, expandedUrl)
        assertEquals(coldRequests, requests)
        assertEquals(listOf("1", "1", "2", "1", "2"), cards.map { it.id })
        assertNotSame(searchCards[0], searchCards[1])
        assertEquals(
            listOf("Search title", "Repeated title", "Search other", "Library title", "Library other"),
            cards.map { it.title }
        )
        assertEquals(listOf(146_000, 0, 72_000, 161_000, 80_000), cards.map { it.wordCount.count })
        assertEquals(List(cards.size) { LocalDateTime.MIN }, cards.map { it.lastUpdated })
        val originalCardText = cards.map { Triple(it.title, it.author, it.description) }
        assertEquals(
            Triple("Search title", "List author", "List synopsis for Search title"),
            originalCardText.first()
        )

        val detail = source.getBookInformation(searchCards.first().id)
        val updated = "2026-10-20T00:00"
        val unknown = LocalDateTime.MIN.toString()
        assertEquals(coldRequests + detailUrl, requests)
        assertEquals(
            Triple("Full title", "Full author", "Full synopsis"),
            Triple(detail.title, detail.author, detail.description)
        )
        assertEquals(721_000, detail.wordCount.count)
        assertEquals(originalCardText, cards.map { Triple(it.title, it.author, it.description) })
        assertEquals(listOf(146_000, 721_000, 72_000, 161_000, 80_000), cards.map { it.wordCount.count })
        assertEquals(listOf(updated, updated, unknown, updated, unknown), cards.map { it.lastUpdated.toString() })

        val newSearchCards = provider.search(provider.searchTypes.single(), "Book").toList().cards()
        val newExpandedCards = expanded.getResultFlow().toList().cards()
        val newCards = newSearchCards + newExpandedCards
        assertEquals(coldRequests + detailUrl + expandedUrl, requests)
        assertEquals(originalCardText, newCards.map { Triple(it.title, it.author, it.description) })
        assertEquals(cards.map { it.wordCount.count }, newCards.map { it.wordCount.count })
        assertEquals(cards.map { it.lastUpdated }, newCards.map { it.lastUpdated })
    }

    private fun List<SearchResult>.cards(): List<BookInformation> =
        filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation }

    private fun row(id: String, title: String, words: String = "") = """
        <a class="book-layout" href="/novel/$id.html">
            <h4 class="book-title">$title</h4>
            <span class="book-author">List author</span>
            <p class="book-desc">List synopsis for $title</p>
            <div class="book-meta-r"><span class="tag-small-group"><em class="tag-small blue">$words</em></span></div>
        </a>
    """.trimIndent()
}
