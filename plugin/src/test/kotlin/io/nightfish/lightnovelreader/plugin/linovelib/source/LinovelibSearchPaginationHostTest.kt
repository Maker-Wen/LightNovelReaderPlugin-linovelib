package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinovelibSearchPaginationHostTest {
    @Test
    fun pagesAppendEveryEntryWithoutFetchingDetails() = runBlocking {
        val events = mutableListOf<String>()
        val displayed = mutableListOf<BookInformation>()
        var pages = 0
        val provider = testSearchProvider(
            htmlLoader = { url -> fail("Unexpected detail request: $url"); "" },
            searchHtmlLoader = { url ->
                pages++
                events += "page $pages"
                LinovelibHtmlResponse(url, if (pages == 1) {
                    searchPage(listOf("1"), "/search/Book_2.html")
                } else searchPage(listOf("1", "2"), "#").replace("Book 1", "Page 2 Book 1"))
            }
        )

        provider.search(provider.searchTypes.single(), "Book").collect { result ->
            when (result) {
                is SearchResult.MultipleBook -> {
                    val book = result.bookInformation
                    assertEquals(if (pages == 2 && book.id == "1") "Page 2 Book 1" else "Book ${book.id}", book.title)
                    displayed += book
                    events += "show ${book.id}"
                }
                is SearchResult.End -> events += "end"
                else -> fail("Unexpected result: $result")
            }
        }

        assertEquals(listOf("page 1", "show 1", "page 2", "show 1", "show 2", "end"), events)
        assertEquals(listOf("1", "1", "2"), displayed.map { it.id })
        assertNotSame(displayed[0], displayed[1])
        assertEquals(listOf("Book 1", "Page 2 Book 1", "Book 2"), displayed.map { it.title })
    }

    @Test
    fun currentPageFieldsTakePrecedenceOverCachedDetailsAndSummaries() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val parser = LinovelibHtmlParser()
        metadata.rememberBook(parser.parseBookInformation("1", "<h1 class=book-title>Known detail</h1>"))
        metadata.rememberBooks(listOf(ParsedExploreBook("2", "Old summary", "", "")))
        val displayed = mutableListOf<BookInformation>()
        val provider = testSearchProvider(
            htmlLoader = { url -> fail("Unexpected detail request: $url"); "" },
            searchHtmlLoader = { url -> LinovelibHtmlResponse(url, searchPage(listOf("1", "2"))) },
            metadata = metadata
        )

        provider.search(provider.searchTypes.single(), "Book").collect { result ->
            if (result is SearchResult.MultipleBook) {
                displayed += result.bookInformation
            }
        }

        assertEquals(listOf("Book 1", "Book 2"), displayed.map { it.title })
        assertEquals("Known detail", metadata.bookInformation("1")?.title)
        assertEquals("Book 2", metadata.fallbackBookInformation("2")?.title)
    }

    @Test
    fun laterPageFailureRetainsTheDisplayedSummaryAndEndsWithoutDetailRequests() = runBlocking {
        var requests = 0
        val results = mutableListOf<SearchResult>()
        val provider = testSearchProvider(
            htmlLoader = { error("A failed page must end the search before detail requests") },
            searchHtmlLoader = { url ->
                requests++
                LinovelibHtmlResponse(url, if (requests == 1) searchPage(listOf("1"), "/search/Book_2.html") else "")
            }
        )
        provider.search(provider.searchTypes.single(), "Book").collect { results += it }

        assertEquals(2, requests)
        assertEquals(3, results.size)
        assertEquals("Book 1", (results[0] as SearchResult.MultipleBook).bookInformation.title)
        assertTrue(results[1] is SearchResult.Error)
        assertTrue(results[2] is SearchResult.End)
    }

    @Test
    fun cancellationDuringNextPageStopsFurtherRequestsAndDoesNotEmitEnd() = runBlocking {
        val cancelled = CancellationException("Search cancelled")
        val results = mutableListOf<SearchResult>()
        var pageRequests = 0
        val provider = testSearchProvider(
            htmlLoader = { error("Cancelled search must not fetch details") },
            searchHtmlLoader = { url ->
                if (++pageRequests == 2) throw cancelled
                LinovelibHtmlResponse(url, searchPage(listOf("1", "2"), "/search/Book_2.html"))
            }
        )
        val failure = runCatching {
            provider.search(provider.searchTypes.single(), "Book").collect { results += it }
        }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertEquals(2, pageRequests)
        assertEquals(2, results.size)
        assertTrue(results.all { it is SearchResult.MultipleBook })
    }

    @Test
    fun collectorFailureStopsBeforeTheNextPageOrDetails() = runBlocking {
        val failure = IllegalStateException("Collector failed")
        var requests = 0
        val provider = testSearchProvider(
            htmlLoader = { error("Collector failure must stop detail requests") },
            searchHtmlLoader = { url ->
                requests++
                LinovelibHtmlResponse(url, searchPage(listOf("1"), "/search/Book_2.html"))
            }
        )
        assertSame(failure, runCatching {
            provider.search(provider.searchTypes.single(), "Book").collect { throw failure }
        }.exceptionOrNull())
        assertEquals(1, requests)
    }
}
