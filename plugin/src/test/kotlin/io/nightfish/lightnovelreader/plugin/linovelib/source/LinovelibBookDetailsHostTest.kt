package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.Context
import android.content.ContextWrapper
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinovelibBookDetailsHostTest {
    @Test
    fun ordinaryDetailsKeepLiveFirstAndBothFallbackLevels() = runBlocking {
        val metadata = LinovelibMetadataStore()
        metadata.rememberBooks(listOf(ParsedExploreBook("1", "Summary", "Writer", "")))
        var html = "<html>Verification required</html>"
        var failure: Throwable? = null
        val requests = mutableListOf<String>()
        val events = mutableListOf<String>()
        val source = source(metadata, events) { url ->
            requests += url
            failure?.let { throw it }
            html
        }

        assertEquals("Summary", source.getBookInformation("1").title)
        html = detail("Fresh")
        assertEquals("Fresh", source.getBookInformation("1").title)
        assertEquals("${LinovelibUrls.HOST}/author/a.html", metadata.relatedTarget("作者：Author"))
        html = "<html>Verification required</html>"
        assertEquals("Fresh", source.getBookInformation("1").title)
        assertTrue(events.isEmpty())
        failure = IllegalStateException("Unavailable")
        assertEquals("Fresh", source.getBookInformation("1").title)
        assertTrue(source.getBookInformation("2").isEmpty())
        assertEquals(5, requests.size)
        assertEquals(2, events.count { it.startsWith("BOOK_ERROR ") })

        val other = source(LinovelibMetadataStore(), mutableListOf()) { html }
        assertTrue(other.getBookInformation("1").isEmpty())
    }

    @Test
    fun searchAndOrdinaryDetailsShareAcceptedMetadataButHaveDifferentFallbacks() = runBlocking {
        var html = detail("Search detail")
        val events = mutableListOf<String>()
        val source = source(LinovelibMetadataStore(), events) { html }
        val search = source.searchProvider
        assertTrue(search.search(search.searchTypes.single(), "1").toList().first() is SearchResult.MultipleBook)
        html = "<html>Verification required</html>"
        assertEquals("Search detail", source.getBookInformation("1").title)
        assertTrue(search.search(search.searchTypes.single(), "1").toList().first() is SearchResult.SingleBook)
        assertFalse(events.any { it.contains("ERROR") })
    }

    @Test
    fun ordinaryAndDirectCancellationPropagatesWithoutFallbackOrFailureLog() = runBlocking {
        val metadata = LinovelibMetadataStore()
        metadata.rememberBooks(listOf(ParsedExploreBook("1", "Summary", "", "")))
        val cancelled = CancellationException("Cancelled")
        val events = mutableListOf<String>()
        val source = source(metadata, events) { throw cancelled }
        assertSame(cancelled, runCatching { source.getBookInformation("1") }.exceptionOrNull())
        val search = source.searchProvider
        assertSame(cancelled, runCatching { search.search(search.searchTypes.single(), "1").toList() }.exceptionOrNull())
        assertFalse(events.any { it.contains("ERROR") })
        assertNull(metadata.bookInformation("1"))
    }

    @Test
    fun redirectedDetailsUseOnlyExistingHtmlAndInvalidPagesKeepSingleBookFallback() = runBlocking {
        var html = detail("Redirected")
        val metadata = LinovelibMetadataStore()
        var requests = 0
        val provider = LinovelibSearchProvider(
            { requests++; error("Redirect must not request details") },
            { LinovelibSearchResponse("${LinovelibUrls.HOST}/novel/1.html", html) },
            LinovelibHtmlParser(), LinovelibDiagnostics { _, _, _, _ -> }, metadata = metadata
        )
        assertTrue(provider.search(provider.searchTypes.single(), "query").toList().first() is SearchResult.MultipleBook)
        html = ""
        assertTrue(provider.search(provider.searchTypes.single(), "query").toList().first() is SearchResult.SingleBook)
        assertEquals("Redirected", metadata.bookInformation("1")?.title)
        assertEquals(0, requests)
    }

    @Test
    fun publicHostConstructorRemainsAvailable() {
        assertNotNull(LinovelibWebDataSource::class.java.getConstructor(Context::class.java))
    }

    private fun source(
        metadata: LinovelibMetadataStore,
        events: MutableList<String>,
        load: suspend (String) -> String
    ) = LinovelibWebDataSource(
        object : ContextWrapper(null) {
            // The constructor only retains this path; these tests never localize images.
            override fun getFilesDir() = File("/data/local/tmp/linovelib-book-details-unused")
        }, metadata, LinovelibDiagnostics { _, _, message, _ -> events += message }, load
    )

    private fun detail(title: String) =
        "<h1 class=\"book-title\">$title</h1><span class=\"authorname\"><a href=\"/author/a.html\">Author</a></span>"
}
