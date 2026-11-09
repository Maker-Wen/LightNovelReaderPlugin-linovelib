package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LinovelibBookDetailsTest {
    @Test
    fun `fetch always refreshes and accepts valid details with related targets`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val requests = mutableListOf<String>()
        val host = "https://example.test"
        val details = LinovelibBookDetails({ url ->
            requests += url
            bookHtml("fresh", "2026-10-14").replace("<a>Author fresh", "<a href=\"/author/fresh.html\">Author fresh") +
                "<div class=\"tag-small-group\"><span class=\"tag-small\"><a href=\"/tag/fantasy.html\">Fantasy</a></span></div>"
        }, LinovelibHtmlParser(host), host, metadata)
        details.acceptHtml("1", bookHtml("old", "2026-01-01"))

        repeat(2) { assertEquals("Book fresh", details.fetch("1")?.title) }

        assertEquals(List(2) { "$host/novel/1.html" }, requests)
        assertEquals("Book fresh", metadata.bookInformation("1")?.title)
        assertEquals("$host/author/fresh.html", metadata.relatedTarget("作者：Author fresh"))
        assertEquals("$host/tag/fantasy.html", metadata.relatedTarget("Fantasy"))
    }

    @Test
    fun `existing HTML never fetches and invalid details never return cached data`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val details = LinovelibBookDetails({ error("Existing HTML must not fetch") },
            LinovelibHtmlParser(), metadata = metadata)
        val known = details.acceptHtml("1", bookHtml("known", "2026-01-01"))
        listOf("", "<html>Verification required</html>", "<h1 class=book-title> </h1>").forEach { html ->
            assertNull(details.acceptHtml("1", html))
            assertEquals(known, metadata.bookInformation("1"))
        }
        val invalid = LinovelibBookDetails({ "<html>Verification required</html>" },
            LinovelibHtmlParser(), metadata = metadata)
        assertNull(invalid.fetch("1"))
        assertEquals(known, metadata.bookInformation("1"))
    }

    @Test
    fun `fetch propagates errors and cancellation without falling back`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        metadata.rememberBooks(listOf(ParsedExploreBook("1", "Saved", "", "")))
        listOf(IllegalStateException("Transport"), kotlinx.coroutines.CancellationException("Cancelled")).forEach { failure ->
            val details = LinovelibBookDetails({ throw failure }, LinovelibHtmlParser(),
                metadata = metadata)
            assertSame(failure, runCatching { details.fetch("1") }.exceptionOrNull())
        }
        assertNull(metadata.bookInformation("1"))
        assertEquals("Saved", metadata.fallbackBookInformation("1")?.title)
    }

    private fun bookHtml(id: String, date: String) = """
        <html><body>
          <div class="book-detail-info">
            <h1 class="book-title">Book $id</h1>
            <span class="authorname"><a>Author $id</a></span>
            <p class="book-meta book-layout-inline">12.3 ${"\u842c\u5b57"} | ${"\u5b8c\u7d50"}</p>
          </div>
          <a class="book-meta book-status"><div>last update $date</div></a>
        </body></html>
    """.trimIndent()
}
