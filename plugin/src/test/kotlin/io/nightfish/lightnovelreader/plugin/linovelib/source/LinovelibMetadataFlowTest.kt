package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibMetadataFlowTest {
    @Test
    fun `expanded pages record summaries before removing duplicate results`() = runBlocking {
        withTimeout(5_000) {
            val metadata = LinovelibMetadataStore()
            val first = CompletableDeferred<Unit>()
            val page = LinovelibLinkedExpandedPageDataSource(
                "Books", "${LinovelibUrls.HOST}/wenku/lastupdate_0_0_0_0_0_0_0_1_0.html",
                htmlLoader = { url ->
                    val (id, title) = when {
                        url.endsWith("_2_0.html") -> "1" to "Updated duplicate"
                        url.endsWith("_3_0.html") -> "2" to "Second"
                        else -> "1" to "First"
                    }
                    """<a class="book-layout" href="/novel/$id.html"><span class="book-title">$title</span></a><div id="pagelink"><a class="last">3</a></div>"""
                }, parser = LinovelibHtmlParser(), metadata = metadata
            )
            val ids = mutableListOf<String>()
            val collector = launch {
                page.getResultFlow().collect {
                    if (it is SearchResult.SingleBook) { ids += it.bookId; first.complete(Unit) }
                }
            }
            first.await()
            page.loadMore()
            collector.join()
            assertEquals(listOf("1", "2"), ids)
            assertEquals("Updated duplicate", metadata.fallbackBookInformation("1")?.title)
            assertEquals("Second", metadata.fallbackBookInformation("2")?.title)
        }
    }

    @Test
    fun `evicted targets leave registered page definitions usable but unknown tags use search`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val requested = mutableListOf<String>()
        val searched = mutableListOf<String>()
        val provider = LinovelibExplorePageProvider(
            htmlLoader = { requested += it; "<ol class=\"book-ol\"></ol>" },
            parser = LinovelibHtmlParser(),
            searchBookIds = { searched += it; flowOf(SearchResult.End()) }, metadata = metadata
        )
        val book = ParsedExploreBook("1", "Book", "", "").toParsedBookInformation()
        metadata.rememberBook(book.copy(relatedLinks = mapOf("Tag" to "${LinovelibUrls.HOST}/wenku/known/1.html")))
        val id = provider.registerRelatedPage("Tag")
        val oldPage = provider.exploreExpandedPageDataSourceMap.getValue(id)
        (1..256).forEach { metadata.rememberBook(book.copy(relatedLinks = mapOf("Other$it" to "/other/$it"))) }
        assertNull(metadata.relatedTarget("Tag"))
        assertEquals(id, provider.registerRelatedPage("Tag"))
        oldPage.getResultFlow().toList()
        provider.exploreExpandedPageDataSourceMap.getValue(id).getResultFlow().toList()
        val unknownId = provider.registerRelatedPage("Unknown")
        provider.exploreExpandedPageDataSourceMap.getValue(unknownId).getResultFlow().toList()
        assertEquals(List(2) { "${LinovelibUrls.HOST}/wenku/known/1.html" }, requested)
        assertEquals(listOf("Unknown"), searched)
    }
}
