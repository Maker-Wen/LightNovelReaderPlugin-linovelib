package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class LinovelibBookDetailsTest {
    @Test
    fun `cached details display immediately but still refresh and register new targets`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val parser = LinovelibHtmlParser()
        metadata.rememberBook(parser.parseBookInformation("1", bookHtml("old", "2026-01-01")))
        val requested = mutableListOf<String>()
        val details = LinovelibBookDetails(
            htmlLoader = { url ->
                requested += url
                bookHtml("new", "2026-10-13").replace("<a>Author new", "<a href=\"/author/new.html\">Author new")
            }, parser = parser, diagnostics = silentDiagnostics(), metadata = metadata
        )
        val results = mutableListOf<ParsedBookInformation>()
        details.enrich(listOf(ParsedExploreBook("1", "List title", "", ""))).collect {
            if (results.isEmpty()) assertTrue(requested.isEmpty())
            results += it
        }
        assertEquals(listOf("Book old", "Book new"), results.map { it.title })
        assertEquals(listOf(LinovelibUrls.book("1")), requested)
        assertEquals("Book new", metadata.bookInformation("1")?.title)
        assertEquals("${LinovelibUrls.HOST}/author/new.html", metadata.relatedTarget("作者：Author new"))
    }

    @Test
    fun `invalid or cancelled detail refresh keeps previous successful data`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val parser = LinovelibHtmlParser()
        val known = parser.parseBookInformation("1", bookHtml("known", "2026-01-01"))
        metadata.rememberBook(known)
        val books = listOf(ParsedExploreBook("1", "List title", "", ""))
        val invalid = LinovelibBookDetails({ "<html>Verification required</html>" }, parser, silentDiagnostics(), metadata = metadata)
        assertEquals(listOf(known), invalid.enrich(books).toList())
        val cancelled = LinovelibBookDetails({ throw kotlinx.coroutines.CancellationException("Cancelled") }, parser, silentDiagnostics(), metadata = metadata)
        val emitted = mutableListOf<ParsedBookInformation>()
        try {
            cancelled.enrich(books).collect { emitted += it }
            error("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(listOf(known), emitted)
            assertEquals(known, metadata.bookInformation("1"))
            assertNull(metadata.bookInformation("2"))
        }
    }

    @Test
    fun `does not link coroutine synchronization classes unavailable in the host app`() {
        val sourcePath = "io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookDetails.kt"
        val source = listOf(
            File(System.getProperty("user.dir"), "plugin/src/main/kotlin/$sourcePath"),
            File(System.getProperty("user.dir"), "src/main/kotlin/$sourcePath")
        ).first { it.isFile }.readText()

        assertFalse(source.contains("kotlinx.coroutines.sync"))
    }

    @Test
    fun `loads complete information for lightweight search rows`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val details = LinovelibBookDetails(
            htmlLoader = { url ->
                requestedUrls += url
                bookHtml(id = url.substringAfterLast('/').substringBefore('.'), date = "2026-06-30")
            },
            parser = LinovelibHtmlParser(),
            diagnostics = silentDiagnostics(),
            metadata = LinovelibMetadataStore()
        )

        val result = details.enrich(
            listOf(ParsedExploreBook("101", "Search title", "Search author", ""))
        ).toList()

        assertEquals(listOf(LinovelibUrls.book("101")), requestedUrls)
        assertEquals(2, result.size)
        assertEquals("Search title", result.first().title)
        assertEquals("101", result.last().id)
        assertEquals(LocalDate.of(2026, 6, 30), result.last().lastUpdated)
        assertEquals(123_000, result.last().wordCount)
        assertTrue(result.last().isComplete)
    }

    @Test
    fun `loads detail requests one at a time`() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val details = LinovelibBookDetails(
            htmlLoader = { url ->
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, nowActive) }
                delay(20)
                active.decrementAndGet()
                bookHtml(url.substringAfterLast('/').substringBefore('.'), "2026-06-30")
            },
            parser = LinovelibHtmlParser(),
            diagnostics = silentDiagnostics(),
            metadata = LinovelibMetadataStore()
        )
        val books = (1..9).map { id -> ParsedExploreBook(id.toString(), "Book $id", "Author", "") }

        val result = details.enrich(books).toList()

        assertEquals(18, result.size)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `emits all search rows before requests and retains failed or undated details`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val details = LinovelibBookDetails(
            htmlLoader = { url ->
                requestedUrls += url
                if (url == LinovelibUrls.book("405")) error("Detail request failed")
                bookHtml(id = "404", date = "")
            },
            parser = LinovelibHtmlParser(),
            diagnostics = silentDiagnostics(),
            metadata = LinovelibMetadataStore()
        )
        val result = linkedMapOf<String, ParsedBookInformation>()
        var emitted = 0
        details.enrich(
            listOf(
                ParsedExploreBook("404", "No date", "Author", ""),
                ParsedExploreBook("405", "Retained title", "Retained author", "https://example.com/cover.jpg")
            )
        ).collect { book ->
            if (emitted < 2) assertTrue(requestedUrls.isEmpty())
            result[book.id] = book
            emitted++
        }

        assertEquals(3, emitted)
        assertEquals(listOf("404", "405"), result.keys.toList())
        assertEquals("Book 404", result.getValue("404").title)
        assertTrue(LinovelibDates.isUnknown(result.getValue("404").lastUpdated))
        assertEquals("Retained title", result.getValue("405").title)
        assertEquals("Retained author", result.getValue("405").author)
        assertEquals("https://example.com/cover.jpg", result.getValue("405").coverUrl)
    }

    @Test
    fun `fetch always refreshes and accepts valid details with related targets`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        val requests = mutableListOf<String>()
        val host = "https://example.test"
        val details = LinovelibBookDetails({ url ->
            requests += url
            bookHtml("fresh", "2026-10-14").replace("<a>Author fresh", "<a href=\"/author/fresh.html\">Author fresh") +
                "<div class=\"tag-small-group\"><span class=\"tag-small\"><a href=\"/tag/fantasy.html\">Fantasy</a></span></div>"
        }, LinovelibHtmlParser(host), silentDiagnostics(), host, metadata)
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
            LinovelibHtmlParser(), silentDiagnostics(), metadata = metadata)
        val known = details.acceptHtml("1", bookHtml("known", "2026-01-01"))
        listOf("", "<html>Verification required</html>", "<h1 class=book-title> </h1>").forEach { html ->
            assertNull(details.acceptHtml("1", html))
            assertEquals(known, metadata.bookInformation("1"))
        }
        val invalid = LinovelibBookDetails({ "<html>Verification required</html>" },
            LinovelibHtmlParser(), silentDiagnostics(), metadata = metadata)
        assertNull(invalid.fetch("1"))
        assertEquals(known, metadata.bookInformation("1"))
    }

    @Test
    fun `fetch propagates errors and cancellation without logging or falling back`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        metadata.rememberBooks(listOf(ParsedExploreBook("1", "Saved", "", "")))
        val events = mutableListOf<String>()
        listOf(IllegalStateException("Transport"), kotlinx.coroutines.CancellationException("Cancelled")).forEach { failure ->
            val details = LinovelibBookDetails({ throw failure }, LinovelibHtmlParser(),
                LinovelibDiagnostics { _, _, message, _ -> events += message }, metadata = metadata)
            assertSame(failure, runCatching { details.fetch("1") }.exceptionOrNull())
        }
        assertTrue(events.isEmpty())
        assertNull(metadata.bookInformation("1"))
        assertEquals("Saved", metadata.fallbackBookInformation("1")?.title)
    }

    @Test
    fun `search initial results use the current summary instead of a stored old summary`() = runBlocking {
        val metadata = LinovelibMetadataStore()
        metadata.rememberBooks(listOf(ParsedExploreBook("1", "Old summary", "", "")))
        val events = mutableListOf<String>()
        val requested = mutableListOf<String>()
        val details = LinovelibBookDetails({ url ->
            requested += url
            if (url.endsWith("1.html")) error("First detail unavailable")
            bookHtml("fresh", "2026-10-14")
        }, LinovelibHtmlParser(), LinovelibDiagnostics { _, _, message, _ -> events += message }, metadata = metadata)
        val result = details.enrich(listOf(
            ParsedExploreBook("1", "Current summary", "", ""),
            ParsedExploreBook("2", "Second summary", "", "")
        )).toList()
        assertEquals(listOf("Current summary", "Second summary", "Book fresh"), result.map { it.title })
        assertEquals(2, requested.size)
        assertTrue(events.single().startsWith("SEARCH_DETAIL_ERROR "))
    }

    @Test
    fun `enrichment cancellation or collector failure cannot be swallowed as detail failures`() = runBlocking {
        val books = (1..2).map { ParsedExploreBook("$it", "Book $it", "", "") }
        val events = mutableListOf<String>()
        val requested = mutableListOf<String>()
        val cancelled = kotlinx.coroutines.CancellationException("Cancelled")
        val details = LinovelibBookDetails({ url -> requested += url; throw cancelled }, LinovelibHtmlParser(),
            LinovelibDiagnostics { _, _, message, _ -> events += message }, metadata = LinovelibMetadataStore())
        assertSame(cancelled, runCatching { details.enrich(books).toList() }.exceptionOrNull())
        assertEquals(listOf(LinovelibUrls.book("1")), requested)
        assertTrue(events.isEmpty())

        val failure = IllegalStateException("Collector failed")
        requested.clear()
        assertSame(failure, runCatching { details.enrich(books).collect { throw failure } }.exceptionOrNull())
        assertTrue(requested.isEmpty())
        assertTrue(events.isEmpty())
    }

    private fun bookHtml(id: String, date: String) = """
        <html><body>
          <h1 class="book-title">Book $id</h1>
          <span class="authorname"><a>Author $id</a></span>
          <p class="book-meta book-layout-inline">12.3 ${"\u842c\u5b57"} | ${"\u5b8c\u7d50"}</p>
          <a class="book-meta book-status"><div>last update $date</div></a>
        </body></html>
    """.trimIndent()

    private fun silentDiagnostics() = LinovelibDiagnostics { _, _, _, _ -> }
}
