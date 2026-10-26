package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LinovelibMetadataStoreTest {
    private val parser = LinovelibHtmlParser()
    private val metadata = LinovelibMetadataStore()
    private val details = LinovelibBookDetails(
        htmlLoader = { error("Test supplies HTML directly") },
        parser = parser,
        diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
        metadata = metadata
    )

    @Test
    fun `each index evicts the oldest write at 257 entries and reads do not refresh it`() {
        fun remember(id: Int) {
            metadata.rememberBook(book(id).copy(relatedLinks = mapOf("Tag$id" to "/tag/$id")))
            metadata.rememberBooks(listOf(summary(id)))
        }
        (1..256).forEach(::remember)
        assertNotNull(metadata.bookInformation("1"))
        assertNotNull(metadata.fallbackBookInformation("1"))
        assertEquals("/tag/1", metadata.relatedTarget("Tag1"))
        remember(2)
        remember(257)

        assertNull(metadata.bookInformation("1"))
        assertNull(metadata.fallbackBookInformation("1"))
        assertNull(metadata.relatedTarget("Tag1"))
        listOf(2, 3, 256, 257).forEach { id ->
            assertNotNull(metadata.bookInformation("$id"))
            assertNotNull(metadata.fallbackBookInformation("$id"))
            assertEquals("/tag/$id", metadata.relatedTarget("Tag$id"))
        }
        // Exercise summary retention separately from the preferred detail index.
        (258..513).forEach { metadata.rememberBook(book(it)) }
        assertEquals(summary(2).toParsedBookInformation(), metadata.fallbackBookInformation("2"))
        assertEquals(summary(3).toParsedBookInformation(), metadata.fallbackBookInformation("3"))
    }

    @Test
    fun `fallback prefers details then summaries and indices expire independently`() {
        metadata.rememberBooks(listOf(summary(1)))
        metadata.rememberBook(book(1).copy(title = "Full title", relatedLinks = mapOf("Tag" to "/known")))
        assertEquals("Full title", metadata.fallbackBookInformation("1")?.title)
        (2..257).forEach { metadata.rememberBook(book(it)) }
        assertEquals(summary(1).toParsedBookInformation(), metadata.fallbackBookInformation("1"))
        assertEquals("/known", metadata.relatedTarget("Tag"))
        (2..257).forEach { metadata.rememberBooks(listOf(summary(it))) }
        assertNull(metadata.fallbackBookInformation("1"))
        assertEquals("/known", metadata.relatedTarget("Tag"))
    }

    @Test
    fun `latest valid target wins and missing links do not erase known targets`() {
        metadata.rememberBook(book(1).copy(author = "A", authorUrl = "/author/a", relatedLinks = mapOf("Tag" to "/first")))
        metadata.rememberBook(book(2).copy(relatedLinks = mapOf("Tag" to "/latest")))
        metadata.rememberBook(book(1))
        assertNull(details.acceptHtml("3", """
            <h1 class="book-title"> </h1>
            <span class="tag-small-group"><em class="tag-small red"><a href="/invalid">Tag</a></em></span>
        """))

        assertEquals("/latest", metadata.relatedTarget("Tag"))
        assertEquals("/author/a", metadata.relatedTarget("作者：A"))
        assertNull(metadata.bookInformation("3"))
    }

    @Test
    fun `stored details and summaries belong to one source`() {
        metadata.rememberBook(book(1).copy(tags = listOf("Tag"), relatedLinks = mapOf("Tag" to "/original")))
        metadata.rememberBooks(listOf(summary(2)))

        assertEquals(listOf("Tag"), metadata.bookInformation("1")?.tags)
        assertEquals(mapOf("Tag" to "/original"), metadata.bookInformation("1")?.relatedLinks)
        assertEquals("/original", metadata.relatedTarget("Tag"))
        val otherSource = LinovelibMetadataStore()
        assertNull(otherSource.bookInformation("1"))
        assertNull(otherSource.fallbackBookInformation("2"))
        assertNull(otherSource.relatedTarget("Tag"))
    }

    @Test
    fun `parallel callers keep complete records within each capacity`() {
        val executor = Executors.newFixedThreadPool(6)
        try {
            executor.invokeAll((1..600).map { id -> Callable {
                metadata.rememberBook(book(id).copy(relatedLinks = mapOf("Tag$id" to "/tag/$id")))
                metadata.rememberBooks(listOf(summary(id)))
                metadata.bookInformation("$id")?.let { assertEquals("$id", it.id) }
                metadata.fallbackBookInformation("$id")?.let { assertEquals("$id", it.id) }
            } }).forEach { it.get() }
            val retainedDetails = (1..600).filter { metadata.bookInformation("$it") != null }
            val retainedLinks = (1..600).filter { metadata.relatedTarget("Tag$it") != null }
            assertEquals(256, retainedDetails.size)
            assertEquals(retainedDetails, retainedLinks)
            (601..856).forEach { metadata.rememberBook(book(it)) }
            assertEquals(256, (1..600).count { metadata.fallbackBookInformation("$it") != null })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun summary(id: Int) = ParsedExploreBook("$id", "Summary $id", "Author", "")
    private fun book(id: Int) = summary(id).toParsedBookInformation()

    @Test
    fun invalidBookInformationDoesNotReplaceRelatedTargets() {
        fun html(title: String, target: String) = """
            $title
            <span class="authorname"><a href="/author/$target.html">Author</a></span>
            <span class="tag-small-group">
              <em class="tag-small red"><a href="/wenku/$target.html">Tag</a></em>
              <em class="tag-small orange"><a href="/publisher/$target.html">Publisher</a></em>
            </span>
        """
        assertNotNull(details.acceptHtml("1", html("<h1 class=\"book-title\">Book</h1>", "valid")))
        assertNull(details.acceptHtml("1", html("", "invalid")))

        assertEquals("${LinovelibUrls.HOST}/author/valid.html", metadata.relatedTarget("作者：Author"))
        assertEquals("${LinovelibUrls.HOST}/wenku/valid.html", metadata.relatedTarget("Tag"))
        assertEquals("${LinovelibUrls.HOST}/publisher/valid.html", metadata.relatedTarget("Publisher"))
    }

    @Test
    fun invalidBookInformationDoesNotReplaceSuccessfulCache() {
        val cached = requireNotNull(details.acceptHtml("1", "<h1 class=\"book-title\">Book</h1>"))

        listOf("<html></html>", "<meta property=\"og:novel:book_name\" content=\"   \" />").forEach { html ->
            assertNull(details.acceptHtml("1", html))
            assertEquals(cached, metadata.bookInformation("1"))
            assertNull(details.acceptHtml("2", html))
            assertNull(metadata.bookInformation("2"))
        }
    }

}
