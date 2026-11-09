package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class LinovelibMetadataFlowHostTest {
    @Test
    fun wenkuKeepsEmptyDisplayRowAndExpandedEntry() = runBlocking {
        listOf(
            """<ol class="book-ol"></ol>""",
            "<html>Access denied</html>",
            """<ol class="book-ol"><li class="book-li">Invalid book</li></ol>"""
        ).forEach { html ->
            val provider = exploreProvider(html, LinovelibMetadataStore())
            val emissions = provider.exploreTapPageDataSourceMap.values.single { it.title == "文库" }
                .getRowsFlow().toList()
            assertTrue(emissions.first().isEmpty())
            val row = emissions.last().single()
            assertEquals("全部轻小说", row.title)
            assertTrue(row.bookList.isEmpty())
            assertTrue(row.expandable)
            assertNotNull(provider.exploreExpandedPageDataSourceMap[row.expandedPageDataSourceId])
        }
    }

    @Test
    fun wenkuPreviewRecordsOnlyDisplayedSummariesWithoutHiddenBooksEvictingThem() = runBlocking {
        val links = (1..300).joinToString("") { row(it) }
        val metadata = LinovelibMetadataStore()
        val provider = exploreProvider(exploreHtml("文库", links), metadata)
        val emissions = provider.exploreTapPageDataSourceMap.values.single { it.title == "文库" }
            .getRowsFlow().toList()
        assertTrue(emissions.first().isEmpty())
        assertEquals((1..12).map(Int::toString), emissions.last().single().bookList.map { it.id })
        assertEquals(
            (1..12).map { LinovelibUrls.cover(LinovelibUrls.HOST, "$it") },
            emissions.last().single().bookList.map { it.coverUri.toString() }
        )
        (1..12).forEach { id ->
            val fallback: BookInformation = metadata.fallbackBookInformation("$id")!!.toMutableBookInformation()
            assertEquals("Book $id", fallback.title)
            assertEquals(listOf("作者：Writer"), fallback.tags)
            assertEquals(LinovelibUrls.cover(LinovelibUrls.HOST, "$id"), fallback.coverUri.toString())
            assertEquals(LocalDateTime.MIN, fallback.lastUpdated)
        }
        (13..300).forEach { id ->
            assertNull("Wenku must not record hidden book $id", metadata.fallbackBookInformation("$id"))
        }
    }

    @Test
    fun recommendationWithoutModulesDoesNotDisplayOrRecordPageLinks() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val provider = exploreProvider((1..13).joinToString("") { row(it) }, metadata)
        val emissions = provider.exploreTapPageDataSourceMap.values.single { it.title == "推荐" }
            .getRowsFlow().toList()

        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.all { it.isEmpty() })
        (1..13).forEach { id -> assertNull(metadata.fallbackBookInformation("$id")) }
        assertEquals(setOf(LinovelibExplorePageProvider.WENKU_PAGE_ID), provider.exploreExpandedPageDataSourceMap.keys)
    }

    @Test
    fun rankingKeepsAllSourceRowsAndBooksWhenRecordingSummariesAndRegisteringPages() = runBlocking {
        val title = "排行"
        val metadata = LinovelibMetadataStore()
        // Invalid source rows still have no displayable books or expanded entries.
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

        assertEquals((1..9).map { "Row $it" }, rows.map { it.title })
        rows.forEachIndexed { index, displayed ->
            val ids = (1..13).map { "${(index + 1) * 100 + it}" }
            val pageId = "linovelib-list-/top/row${index + 1}/1.html"
            assertEquals(ids, displayed.bookList.map { it.id })
            assertTrue(displayed.expandable)
            assertEquals(pageId, displayed.expandedPageDataSourceId)
            ids.forEach { id -> assertEquals("Book $id", metadata.fallbackBookInformation(id)?.title) }
        }
        assertNull(metadata.fallbackBookInformation("999"))
        val visiblePageIds = (1..9).map { "linovelib-list-/top/row$it/1.html" }.toSet()
        assertEquals(
            visiblePageIds + LinovelibExplorePageProvider.WENKU_PAGE_ID,
            provider.exploreExpandedPageDataSourceMap.keys
        )
    }

    @Test
    fun hiddenWenkuBooksNeitherReplaceExistingSummariesNorRefreshTheirRetention() = runBlocking {
        val metadata = LinovelibMetadataStore()
        metadata.rememberBooks(listOf(ParsedExploreBook("13", "Original summary", "Original author", "")))
        metadata.rememberBooks((1_000..1_242).map { ParsedExploreBook("$it", "Existing $it", "", "") })
        val provider = exploreProvider(exploreHtml("文库", (1..13).joinToString("") { row(it) }), metadata)
        provider.exploreTapPageDataSourceMap.values.single { it.title == "文库" }.getRowsFlow().toList()

        assertEquals("Original summary", metadata.fallbackBookInformation("13")?.title)
        // The 12 visible books fill the 256-entry index. The hidden book remains its oldest write.
        metadata.rememberBooks(listOf(ParsedExploreBook("2000", "Next summary", "", "")))
        assertNull("Wenku must not refresh the hidden summary", metadata.fallbackBookInformation("13"))
        assertNotNull(metadata.fallbackBookInformation("1000"))
    }

    @Test
    fun searchesRememberListSummariesWithoutRequestingDetails() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val provider = testSearchProvider(
            htmlLoader = { error("List search must not request details") },
            searchHtmlLoader = { LinovelibHtmlResponse(it, row(1) + row(2)) },
            metadata = metadata
        )
        val results = provider.search(provider.searchTypes.single(), "query").toList()

        assertEquals(listOf("Book 1", "Book 2"), results.filterIsInstance<SearchResult.MultipleBook>()
            .map { it.bookInformation.title })
        assertEquals("Book 1", metadata.fallbackBookInformation("1")?.title)
        assertEquals("Book 2", metadata.fallbackBookInformation("2")?.title)
        assertNull(metadata.bookInformation("1"))
    }

    @Test
    fun relatedSearchAcceptsRedirectHtmlAndRejectsInvalidRedirectWithoutEagerDetails() = runBlocking {
        val metadata = LinovelibMetadataStore()
        var html = detail("Known")
        val provider = testSearchProvider(
            { error("Redirect must not request details") },
            { LinovelibHtmlResponse("${LinovelibUrls.HOST}/novel/1.html", html) },
            metadata = metadata
        )
        val result = provider.searchRelated("query").toList().first() as SearchResult.MultipleBook
        assertEquals("Known", result.bookInformation.title)
        html = detail("").replace("/author/a.html", "/author/invalid.html")
        val invalid = provider.searchRelated("query").toList()
        assertTrue(invalid.first() is SearchResult.Error)
        assertTrue(invalid.last() is SearchResult.End)
        assertEquals("Known", metadata.bookInformation("1")?.title)
        assertEquals("${LinovelibUrls.HOST}/author/a.html", metadata.relatedTarget("作者：Author"))
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

    private fun row(id: Int) = """<a class="book-layout" href="/novel/$id.html"><span class="book-title">Book $id</span><span class="book-author"><svg class="icon icon-human"><title>作者</title><use xlink:href="#icon-human"></use></svg>Writer</span></a>"""
    private fun detail(title: String) = """<h1 class="book-title">$title</h1><span class="authorname"><a href="/author/a.html">Author</a></span>"""
}
