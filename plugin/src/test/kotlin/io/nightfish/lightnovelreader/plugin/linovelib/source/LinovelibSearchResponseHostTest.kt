package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibSearchResponseHostTest {
    private val parser = LinovelibHtmlParser()

    @Test
    fun completedSearchReplaysAllPagesInOrderWithFreshBookObjects() = runBlocking {
        val requested = mutableListOf<String>()
        val first = LinovelibUrls.search(LinovelibUrls.HOST, "Book")
        val second = "${LinovelibUrls.HOST}/search/Book_2.html"
        val third = "${LinovelibUrls.HOST}/search/Book_3.html"
        val pages = mapOf(
            first to searchPage(listOf("1"), second),
            second to searchPage(listOf("1"), third),
            third to searchPage(listOf("2"))
        )
        val metadata = LinovelibMetadataStore()
        var loaderCount = 0
        val provider = LinovelibSearchProvider(
            newSearchLoader = {
                loaderCount++
                { url ->
                    requested += url
                    LinovelibHtmlResponse(url, pages.getValue(url))
                }
            },
            parser = parser,
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
            metadata = metadata,
            details = LinovelibBookDetails({ error("List search must not fetch details") }, parser, metadata = metadata)
        )
        val initial = provider.search(provider.searchTypes.single(), "Book").toList()
        val repeated = provider.search(provider.searchTypes.single(), "\u3000 Book \u2003").toList()
        for (results in listOf(initial, repeated)) {
            assertEquals(listOf("1", "1", "2"), results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
            assertEquals(4, results.size)
            assertTrue(results.last() is SearchResult.End)
        }
        initial.filterIsInstance<SearchResult.MultipleBook>()
            .zip(repeated.filterIsInstance<SearchResult.MultipleBook>())
            .forEach { (before, after) -> assertNotSame(before.bookInformation, after.bookInformation) }
        assertEquals(1, loaderCount)
        assertEquals(listOf(first, second, third), requested)
    }

    @Test
    fun laterRequestOrPageFailuresRetainEmittedBooksWithoutCachingThem() = runBlocking {
        val first = LinovelibUrls.search(LinovelibUrls.HOST, "Book")
        for (failedHtml in listOf(null, "", "<h1>请完成验证</h1>")) {
            var requests = 0
            val provider = testSearchProvider(
                htmlLoader = { error("List search must not fetch details") },
                searchHtmlLoader = { url ->
                    requests++
                    LinovelibHtmlResponse(url, if (url == first) {
                        searchPage(listOf("1"), "/search/Book_2.html")
                    } else failedHtml ?: error("Request failed"))
                }
            )
            repeat(2) { attempt ->
                val results = provider.search(provider.searchTypes.single(), "Book").toList()
                assertEquals((attempt + 1) * 2, requests)
                assertEquals(3, results.size)
                assertEquals("1", (results[0] as SearchResult.MultipleBook).bookInformation.id)
                assertTrue(results[1] is SearchResult.Error)
                assertTrue(results[2] is SearchResult.End)
            }
        }
    }

    @Test
    fun cancelledSearchesDoNotCachePartialResults() = runBlocking {
        val first = LinovelibUrls.search(LinovelibUrls.HOST, "Book")
        val second = "${LinovelibUrls.HOST}/search/Book_2.html"
        val cancelled = CancellationException("Search cancelled")
        val results = mutableListOf<SearchResult>()
        val events = mutableListOf<String>()
        val requested = mutableListOf<String>()
        var cancelSecondPage = true
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url ->
                requested += url
                if (url == second && cancelSecondPage) throw cancelled
                LinovelibHtmlResponse(url, if (url == first) searchPage(listOf("1"), second) else searchPage(listOf("2")))
            }, diagnostics = LinovelibDiagnostics { _, _, message, _ -> events += message }
        )
        assertSame(cancelled, runCatching { provider.search(provider.searchTypes.single(), "Book").collect { results += it } }.exceptionOrNull())
        assertEquals(listOf(first, second), requested)
        assertEquals(1, results.size)
        assertTrue(results.single() is SearchResult.MultipleBook)
        assertTrue(events.none { it.startsWith("SEARCH_REQUEST_ERROR") || it.startsWith("SEARCH_DONE") })

        cancelSecondPage = false
        requested.clear()
        provider.search(provider.searchTypes.single(), "Book").take(1).toList()
        assertEquals(listOf(first), requested)

        val completed = provider.search(provider.searchTypes.single(), "Book").toList()
        assertEquals(listOf(first, first, second), requested)
        assertEquals(listOf("1", "2"), completed.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
        assertTrue(completed.last() is SearchResult.End)
    }

    @Test
    fun completeEmptyListIsCachedButInvalidResponsesAreNot() = runBlocking {
        for ((html, cacheable) in listOf(searchPage(emptyList()) to true, "" to false, "<h1>请完成验证</h1>" to false)) {
            var requests = 0
            val provider = testSearchProvider(
                htmlLoader = { error("Empty search must not fetch details") },
                searchHtmlLoader = { url -> requests++; LinovelibHtmlResponse(url, html) }
            )
            repeat(2) {
                val results = provider.search(provider.searchTypes.single(), "Book").toList()
                assertEquals(2, results.size)
                assertTrue(if (cacheable) results[0] is SearchResult.Empty else results[0] is SearchResult.Error)
                assertTrue(results[1] is SearchResult.End)
            }
            assertEquals(if (cacheable) 1 else 2, requests)
        }
    }

    @Test
    fun missingNextLinkDoesNotCacheAListThatAdvertisesMorePages() = runBlocking {
        var requests = 0
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url ->
                requests++
                LinovelibHtmlResponse(url, searchPage(listOf("1")) + "<div id=pagelink>第1/2页</div>")
            }
        )
        repeat(2) {
            val results = provider.search(provider.searchTypes.single(), "Book").toList()
            assertEquals("1", (results[0] as SearchResult.MultipleBook).bookInformation.id)
            assertTrue(results.last() is SearchResult.End)
        }
        assertEquals(2, requests)
    }

    @Test
    fun relatedKeywordSharesTheCompleteListAndRestoresSummaryMetadataOnHit() = runBlocking {
        var requests = 0
        val metadata = LinovelibMetadataStore()
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url -> requests++; LinovelibHtmlResponse(url, searchPage(listOf("1"))) },
            metadata = metadata
        )

        val initial = provider.searchRelated("Book").toList()
        assertTrue(initial.first() is SearchResult.MultipleBook)
        metadata.rememberBooks((2..257).map { ParsedExploreBook(it.toString(), "Book $it", "", "") })
        assertNull(metadata.fallbackBookInformation("1"))

        val repeated = provider.search(provider.searchTypes.single(), "Book").toList()
        assertEquals("1", (repeated.first() as SearchResult.MultipleBook).bookInformation.id)
        assertTrue(repeated.last() is SearchResult.End)
        assertEquals("1", metadata.fallbackBookInformation("1")?.id)
        assertEquals(1, requests)
    }

    @Test
    fun searchPreservesCrossPageEntriesAndKeepsTheExistingSinglePageParsing() = runBlocking {
        val requested = mutableListOf<String>()
        val nextUrl = "${LinovelibUrls.HOST}/search/Book_2.html"
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url ->
                requested += url
                LinovelibHtmlResponse(url, if (requested.size == 1) {
                    searchPage(listOf("1", "2", "1"), "/search/Book_2.html")
                } else {
                    searchPage(listOf("2", "3", "2"), "#")
                })
            }
        )

        val results = provider.search(provider.searchTypes.single(), "Book").toList()

        assertEquals(listOf("1", "2", "2", "3"), results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
        assertEquals(listOf("${LinovelibUrls.HOST}/search.html?searchkey=Book", nextUrl), requested)
        assertTrue(results.last() is SearchResult.End)
        assertEquals(1, results.count { it is SearchResult.End })
    }

    @Test
    fun searchPageMetadataDoesNotReplaceTheReturnedSearchResults() = runBlocking {
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
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

        val results = provider.search(provider.searchTypes.single(), "example").toList()

        assertEquals(2, results.size)
        assertEquals("1667", (results[0] as SearchResult.MultipleBook).bookInformation.id)
        assertTrue(results[1] is SearchResult.End)
    }

    @Test
    fun redirectedSearchEmitsDirectBookAndEndWithoutASecondRequest() = runBlocking {
        val detailRequests = mutableListOf<String>()
        var searchRequests = 0
        val provider = testSearchProvider(
            htmlLoader = { url -> detailRequests += url; "" },
            searchHtmlLoader = {
                searchRequests++
                LinovelibHtmlResponse(
                    finalUrl = "https://www.bilinovel.com/novel/3768.html",
                    html = "<html><body></body></html>"
                )
            },
            parser = parser,
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )

        repeat(2) {
            val results = provider.search(provider.searchTypes.single(), "男性禁入").toList()
            assertEquals(2, results.size)
            assertTrue(results[0] is SearchResult.SingleBook)
            assertEquals("3768", (results[0] as SearchResult.SingleBook).bookId)
            assertTrue(results[1] is SearchResult.End)
        }
        assertEquals(2, searchRequests)
        assertTrue(detailRequests.isEmpty())
    }

    @Test
    fun providerNormalizesUnicodeWhitespaceInUserQueries() = runBlocking {
        val requested = mutableListOf<String>()
        val provider = testSearchProvider(
            htmlLoader = { error("ID-only search must not fetch details") },
            searchHtmlLoader = { query ->
                requested += query
                LinovelibHtmlResponse("${LinovelibUrls.HOST}/novel/3768.html", "")
            }
        )

        val direct = provider.search(provider.searchTypes.single(), "\u3000 3247 \u2003").toList()
        assertEquals("3247", (direct.first() as SearchResult.SingleBook).bookId)
        val empty = provider.search(provider.searchTypes.single(), " \u3000\u2003 ").toList()
        assertTrue(empty.first() is SearchResult.Empty)
        assertTrue(requested.isEmpty())

        val redirected = provider.search(provider.searchTypes.single(), "\u3000 SAO \u2003").toList()
        assertEquals(listOf(LinovelibUrls.search(LinovelibUrls.HOST, "SAO")), requested)
        assertEquals("3768", (redirected.first() as SearchResult.SingleBook).bookId)
    }

    @Test
    fun failedSearchRequestsEmitErrorAndEnd() = runBlocking {
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
