package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class LinovelibMetadataFlowHostTest {
    @Test
    fun allExploreTabsRecordReturnedSummariesIncludingBooksPastTheDisplayLimit() = runBlocking {
        val links = (1..13).joinToString("") { row(it) }
        val html = """
            <div class="module"><h2 class="module-title">Recommended</h2>$links</div>
            <div class="category-list"><span class="fl-header-l">Ranking</span><div class="fl-content">$links</div></div>
            <ol class="book-ol">$links</ol>
        """
        listOf("推荐", "文库", "排行", "完结").forEach { title ->
            val metadata = LinovelibMetadataStore()
            val provider = LinovelibExplorePageProvider({ html }, LinovelibHtmlParser(), { flowOf(SearchResult.End()) }, metadata = metadata)
            val tab = provider.exploreTapPageDataSourceMap.values.single { it.title == title }
            val emissions = tab.getRowsFlow().toList()
            assertTrue(emissions.first().isEmpty())
            assertTrue(emissions.last().isNotEmpty())
            val fallback = metadata.fallbackBookInformation("1")!!.toBookInformation()
            assertEquals("Book 1", fallback.title)
            assertEquals(listOf("作者：Writer"), fallback.tags)
            assertEquals(LocalDateTime.MIN, fallback.lastUpdated)
            if (title == "文库" || title == "完结") {
                assertEquals("Book 13", metadata.fallbackBookInformation("13")?.title)
                assertEquals(12, emissions.last().single().bookList.size)
            }
        }
    }

    @Test
    fun searchEmitsEachBookOnceAndUpdatesTheSameDisplayedObjectAfterRefreshing() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val requests = mutableListOf<String>()
        val displayed = mutableListOf<MutableBookInformation>()
        val provider = searchProvider(metadata, rows = row(1) + row(2), load = { url ->
            assertEquals(2, displayed.size)
            requests += url
            if (url.endsWith("2.html")) error("Second detail unavailable")
            detail("Fresh title")
        })
        provider.search(provider.searchTypes.single(), "query").collect { result ->
            if (result is SearchResult.MultipleBook) {
                // API 3 carries the mutable object itself, so callers see later updates.
                val information = result.bookInformation as MutableBookInformation
                assertEquals("Book ${displayed.size + 1}", information.title)
                displayed += information
            }
        }
        assertEquals(2, displayed.size)
        assertEquals(listOf("Fresh title", "Book 2"), displayed.map { it.title })
        assertEquals(2, requests.size)
        assertEquals("Fresh title", metadata.bookInformation("1")?.title)
        assertEquals("Book 2", metadata.fallbackBookInformation("2")?.title)
        assertEquals("${LinovelibUrls.HOST}/author/a.html", metadata.relatedTarget("作者：Author"))
    }

    @Test
    fun directAndRedirectedSearchesRegisterSuccessfulDetailsAndRejectInvalidOnes() = runBlocking {
        listOf(false, true).forEach { redirected ->
            val metadata = LinovelibMetadataStore()
            var html = detail("Known")
            val provider = LinovelibSearchProvider(
                { html }, { LinovelibSearchResponse("${LinovelibUrls.HOST}/novel/1.html", html) },
                LinovelibHtmlParser(), LinovelibDiagnostics { _, _, _, _ -> }, metadata = metadata
            )
            val query = if (redirected) "query" else "1"
            assertTrue(provider.search(provider.searchTypes.single(), query).toList().first() is SearchResult.MultipleBook)
            html = detail("").replace("/author/a.html", "/author/invalid.html")
            assertTrue(provider.search(provider.searchTypes.single(), query).toList().first() is SearchResult.SingleBook)
            assertEquals("Known", metadata.bookInformation("1")?.title)
            assertEquals("${LinovelibUrls.HOST}/author/a.html", metadata.relatedTarget("作者：Author"))
        }
    }

    @Test
    fun idOnlySearchRecordsListSummariesWithoutFetchingDetailsOrParsingRedirects() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val provider = searchProvider(metadata, row(1)) { error("ID-only search must not request details") }
        assertTrue(provider.searchBookIds("query").toList().first() is SearchResult.SingleBook)
        assertEquals("Book 1", metadata.fallbackBookInformation("1")?.title)
        assertNull(metadata.bookInformation("1"))
        assertTrue(provider.searchBookIds("2").toList().first() is SearchResult.SingleBook)
        assertNull(metadata.fallbackBookInformation("2"))
        val redirect = LinovelibSearchProvider(
            { error("Redirect already contains its result") },
            { LinovelibSearchResponse("${LinovelibUrls.HOST}/novel/3.html", detail("Redirect")) },
            LinovelibHtmlParser(), LinovelibDiagnostics { _, _, _, _ -> }, metadata = metadata
        )
        assertTrue(redirect.searchBookIds("query").toList().first() is SearchResult.SingleBook)
        assertNull(metadata.bookInformation("3"))
    }

    private fun searchProvider(metadata: LinovelibMetadataStore, rows: String, load: suspend (String) -> String) =
        LinovelibSearchProvider(load, { LinovelibSearchResponse("${LinovelibUrls.HOST}/search.html", rows) },
            LinovelibHtmlParser(), LinovelibDiagnostics { _, _, _, _ -> }, metadata = metadata)

    private fun row(id: Int) = """<a class="book-layout" href="/novel/$id.html"><span class="book-title">Book $id</span><span class="book-author">Writer</span></a>"""
    private fun detail(title: String) = """<h1 class="book-title">$title</h1><span class="authorname"><a href="/author/a.html">Author</a></span>"""
}
