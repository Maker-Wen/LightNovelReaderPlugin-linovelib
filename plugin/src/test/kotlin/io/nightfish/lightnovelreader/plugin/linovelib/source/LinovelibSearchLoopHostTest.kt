package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibSearchLoopHostTest {
    private val host = LinovelibUrls.HOST
    private val first = LinovelibUrls.search(host, "Book")
    private val second = "$host/search/Book_2.html"

    @Test
    fun fragmentLinkBackToTheSamePageDoesNotSendAnotherRequest() = runBlocking {
        val requested = mutableListOf<String>()
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url ->
                requested += url
                assertEquals(first, url)
                LinovelibHtmlResponse(url, searchPage(listOf("1"), "#results"))
            }
        )

        val results = provider.search(provider.searchTypes.single(), "Book").toList()

        assertCardsAndEnd(listOf("1"), results)
        assertEquals(listOf(first), requested)
    }

    @Test
    fun cycleKeepsEarlierPagesAndRepeatedSearchStartsAgain() = runBlocking {
        val requested = mutableListOf<String>()
        val pages = mapOf(
            first to searchPage(listOf("1"), second),
            second to searchPage(listOf("2"), first)
        )
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url ->
                requested += url
                check(requested.size <= 4) { "Unexpected repeated request: $url" }
                LinovelibHtmlResponse(url, pages.getValue(url))
            }
        )

        repeat(2) {
            val results = provider.search(provider.searchTypes.single(), "Book").toList()
            assertCardsAndEnd(listOf("1", "2"), results)
        }
        assertEquals(listOf(first, second, first, second), requested)
    }

    @Test
    fun redirectBackToAnAlreadyReadPageDoesNotEmitItsBooksAgain() = runBlocking {
        val firstAlias = "$host/search/Book_1.html"
        val requested = mutableListOf<String>()
        val pages = mapOf(
            first to LinovelibHtmlResponse(firstAlias, searchPage(listOf("1"), second)),
            second to LinovelibHtmlResponse(firstAlias, searchPage(listOf("1"), "#"))
        )
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url -> requested += url; pages.getValue(url) }
        )

        val results = provider.search(provider.searchTypes.single(), "Book").toList()

        assertCardsAndEnd(listOf("1"), results)
        assertEquals(listOf(first, second), requested)
    }

    @Test
    fun relativeNextLinksResolveAgainstTheFinalPageAndReachTheEnd() = runBlocking {
        val firstAlias = "$host/search/Book/1.html"
        val relativeSecond = "$host/search/Book/2.html"
        val relativeThird = "$host/search/Book/3.html"
        val requested = mutableListOf<String>()
        val pages = mapOf(
            first to LinovelibHtmlResponse(firstAlias, searchPage(listOf("1"), "2.html")),
            relativeSecond to LinovelibHtmlResponse(relativeSecond, searchPage(listOf("1", "2"), "3.html")),
            relativeThird to LinovelibHtmlResponse(relativeThird, searchPage(listOf("3"), "#"))
        )
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not fetch details") },
            searchHtmlLoader = { url -> requested += url; pages.getValue(url) }
        )

        repeat(2) {
            val results = provider.search(provider.searchTypes.single(), "Book").toList()
            assertCardsAndEnd(listOf("1", "1", "2", "3"), results)
        }
        assertEquals(listOf(first, relativeSecond, relativeThird), requested)
    }

    private fun assertCardsAndEnd(ids: List<String>, results: List<SearchResult>) {
        assertEquals(ids.size + 1, results.size)
        assertEquals(ids, results.dropLast(1).map { (it as SearchResult.MultipleBook).bookInformation.id })
        assertTrue(results.last() is SearchResult.End)
    }
}
