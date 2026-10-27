package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinovelibSearchPaginationHostTest {
    @Test
    fun pagesAppendEveryEntryAndRefreshAllDisplayedOccurrencesAfterPagination() = runBlocking {
        val events = mutableListOf<String>()
        val displayed = mutableListOf<MutableBookInformation>()
        var pages = 0
        val provider = testSearchProvider(
            htmlLoader = { url ->
                assertEquals(3, displayed.size)
                val id = url.substringAfterLast('/').substringBefore('.')
                events += "detail $id"
                "<h1 class=book-title>Fresh $id</h1>"
            },
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
                    val book = result.bookInformation as MutableBookInformation
                    assertEquals(if (pages == 2 && book.id == "1") "Page 2 Book 1" else "Book ${book.id}", book.title)
                    displayed += book
                    events += "show ${book.id}"
                }
                is SearchResult.End -> events += "end"
                else -> fail("Unexpected result: $result")
            }
        }

        assertEquals(listOf("page 1", "show 1", "page 2", "show 1", "show 2", "detail 1", "detail 1", "detail 2", "end"), events)
        assertEquals(listOf("1", "1", "2"), displayed.map { it.id })
        assertNotSame(displayed[0], displayed[1])
        assertEquals(listOf("Fresh 1", "Fresh 1", "Fresh 2"), displayed.map { it.title })
    }

    @Test
    fun cachedDetailsAndCurrentSummariesStayVisibleWhenTheirDetailRefreshFails() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val parser = LinovelibHtmlParser()
        metadata.rememberBook(parser.parseBookInformation("1", "<h1 class=book-title>Known detail</h1>"))
        metadata.rememberBooks(listOf(ParsedExploreBook("2", "Old summary", "", "")))
        val displayed = mutableListOf<MutableBookInformation>()
        val requested = mutableListOf<String>()
        val provider = testSearchProvider(
            htmlLoader = { url ->
                assertEquals(2, displayed.size)
                requested += url
                if (url.endsWith("1.html")) "<h1>Verification required</h1>" else error("Detail failed")
            },
            searchHtmlLoader = { url -> LinovelibHtmlResponse(url, searchPage(listOf("1", "2"))) },
            metadata = metadata
        )

        provider.search(provider.searchTypes.single(), "Book").collect { result ->
            if (result is SearchResult.MultipleBook) {
                assertTrue(requested.isEmpty())
                displayed += result.bookInformation as MutableBookInformation
            }
        }

        assertEquals(listOf("Known detail", "Book 2"), displayed.map { it.title })
        assertEquals(2, requested.size)
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
    fun cancellationDuringEnrichmentStopsFurtherRequestsAndDoesNotEmitEnd() = runBlocking {
        val cancelled = CancellationException("Search cancelled")
        val results = mutableListOf<SearchResult>()
        var detailRequests = 0
        val provider = testSearchProvider(
            htmlLoader = { detailRequests++; throw cancelled },
            searchHtmlLoader = { url -> LinovelibHtmlResponse(url, searchPage(listOf("1", "2"))) }
        )
        val failure = runCatching {
            provider.search(provider.searchTypes.single(), "Book").collect { results += it }
        }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertEquals(1, detailRequests)
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
