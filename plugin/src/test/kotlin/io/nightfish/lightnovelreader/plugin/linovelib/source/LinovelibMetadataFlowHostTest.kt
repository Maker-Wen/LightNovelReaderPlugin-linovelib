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
    fun allExploreTabsRecordOnlyDisplayedSummariesWithoutHiddenBooksEvictingThem() = runBlocking {
        val links = (1..300).joinToString("") { row(it) }
        listOf("推荐", "文库", "排行", "完结").forEach { title ->
            val metadata = LinovelibMetadataStore()
            val provider = exploreProvider(exploreHtml(title, links), metadata)
            val emissions = provider.exploreTapPageDataSourceMap.values.single { it.title == title }
                .getRowsFlow().toList()
            assertTrue(emissions.first().isEmpty())
            assertEquals((1..12).map(Int::toString), emissions.last().single().bookList.map { it.id })
            (1..12).forEach { id ->
                val fallback = metadata.fallbackBookInformation("$id")!!.toBookInformation()
                assertEquals("Book $id", fallback.title)
                assertEquals(listOf("作者：Writer"), fallback.tags)
                assertEquals(LinovelibUrls.cover(LinovelibUrls.HOST, "$id"), fallback.coverUri.toString())
                assertEquals(LocalDateTime.MIN, fallback.lastUpdated)
            }
            (13..300).forEach { id ->
                assertNull("$title must not record hidden book $id", metadata.fallbackBookInformation("$id"))
            }
        }
    }

    @Test
    fun recommendationFallbackRecordsOnlyItsDisplayedSummaries() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val provider = exploreProvider((1..13).joinToString("") { row(it) }, metadata)
        val rows = provider.exploreTapPageDataSourceMap.values.single { it.title == "推荐" }
            .getRowsFlow().toList().last()

        assertEquals("Home", rows.single().title)
        assertEquals((1..12).map(Int::toString), rows.single().bookList.map { it.id })
        assertEquals("Book 12", metadata.fallbackBookInformation("12")?.title)
        assertNull(metadata.fallbackBookInformation("13"))
        assertEquals(setOf(LinovelibExplorePageProvider.WENKU_PAGE_ID), provider.exploreExpandedPageDataSourceMap.keys)
    }

    @Test
    fun recommendationAndRankingLimitValidRowsBeforeRecordingSummariesAndRegisteringPages() = runBlocking {
        listOf("推荐", "排行").forEach { title ->
            val metadata = LinovelibMetadataStore()
            // Missing titles and rows with no valid books must not consume a display slot.
            val invalidRows = exploreHtml(title, row(999), " ") + exploreHtml(title, "", "Empty")
            val html = invalidRows + (1..9).joinToString("") { index ->
                exploreHtml(
                    title,
                    (1..13).joinToString("") { offset -> row(index * 100 + offset) },
                    "Row $index",
                    "/top/row$index/1.html"
                )
            }
            val provider = exploreProvider(html, metadata)
            val rows = provider.exploreTapPageDataSourceMap.values.single { it.title == title }
                .getRowsFlow().toList().last()

            assertEquals((1..8).map { "Row $it" }, rows.map { it.title })
            rows.forEachIndexed { index, displayed ->
                val ids = (1..12).map { "${(index + 1) * 100 + it}" }
                val pageId = "linovelib-list-/top/row${index + 1}/1.html"
                assertEquals(ids, displayed.bookList.map { it.id })
                assertTrue(displayed.expandable)
                assertEquals(pageId, displayed.expandedPageDataSourceId)
                ids.forEach { id -> assertEquals("Book $id", metadata.fallbackBookInformation(id)?.title) }
                assertNull(metadata.fallbackBookInformation("${(index + 1) * 100 + 13}"))
            }
            assertNull(metadata.fallbackBookInformation("999"))
            assertNull(metadata.fallbackBookInformation("901"))
            val visiblePageIds = (1..8).map { "linovelib-list-/top/row$it/1.html" }.toSet()
            assertEquals(
                visiblePageIds + LinovelibExplorePageProvider.WENKU_PAGE_ID,
                provider.exploreExpandedPageDataSourceMap.keys
            )
        }
    }

    @Test
    fun hiddenBooksNeitherReplaceExistingSummariesNorRefreshTheirRetention() = runBlocking {
        listOf("推荐", "文库", "排行", "完结").forEach { title ->
            val metadata = LinovelibMetadataStore()
            metadata.rememberBooks(listOf(ParsedExploreBook("13", "Original summary", "Original author", "")))
            metadata.rememberBooks((1_000..1_242).map { ParsedExploreBook("$it", "Existing $it", "", "") })
            val provider = exploreProvider(exploreHtml(title, (1..13).joinToString("") { row(it) }), metadata)
            provider.exploreTapPageDataSourceMap.values.single { it.title == title }.getRowsFlow().toList()

            assertEquals("Original summary", metadata.fallbackBookInformation("13")?.title)
            // The 12 visible books fill the 256-entry index. The hidden book remains its oldest write.
            metadata.rememberBooks(listOf(ParsedExploreBook("2000", "Next summary", "", "")))
            assertNull("$title must not refresh the hidden summary", metadata.fallbackBookInformation("13"))
            assertNotNull(metadata.fallbackBookInformation("1000"))
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

    private fun exploreProvider(html: String, metadata: LinovelibMetadataStore) =
        LinovelibExplorePageProvider({ html }, LinovelibHtmlParser(), { flowOf(SearchResult.End()) }, metadata = metadata)

    private fun exploreHtml(tab: String, books: String, title: String = "Books", expandedUrl: String? = null): String {
        val moreLink = expandedUrl?.let { "<a class=\"module-header-btn\" href=\"$it\">More</a>" }.orEmpty()
        return when (tab) {
            "推荐" -> """
                <section class="module"><header class="module-header">
                <h2 class="module-title">$title</h2>$moreLink</header>$books</section>
            """
            "排行" -> """
                <div class="category-list"><a class="fl-header" href="${expandedUrl.orEmpty()}">
                <span class="fl-header-l">$title</span></a><div class="fl-content">$books</div></div>
            """
            else -> """<ol class="book-ol">$books</ol>"""
        }
    }

    private fun searchProvider(metadata: LinovelibMetadataStore, rows: String, load: suspend (String) -> String) =
        LinovelibSearchProvider(load, { LinovelibSearchResponse("${LinovelibUrls.HOST}/search.html", rows) },
            LinovelibHtmlParser(), LinovelibDiagnostics { _, _, _, _ -> }, metadata = metadata)

    private fun row(id: Int) = """<a class="book-layout" href="/novel/$id.html"><span class="book-title">Book $id</span><span class="book-author">Writer</span></a>"""
    private fun detail(title: String) = """<h1 class="book-title">$title</h1><span class="authorname"><a href="/author/a.html">Author</a></span>"""
}
