package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.book.BookInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LinovelibBookMappingHostTest {
    @Test
    fun searchAndFallbackSummariesUseTheSameAuthorTagAndUnknownDate() {
        val summary = ParsedExploreBook("1", "Title", "Author", "https://example.com/cover.jpg")
        val metadata = LinovelibMetadataStore().apply { rememberBooks(listOf(summary)) }
        val search: BookInformation = summary.toParsedBookInformation().toMutableBookInformation()
        val fallback: BookInformation = metadata.fallbackBookInformation("1")!!.toMutableBookInformation()

        listOf(search, fallback).forEach { book ->
            assertEquals("1", book.id)
            assertEquals("Title", book.title)
            assertEquals("Author", book.author)
            assertEquals("https://example.com/cover.jpg", book.coverUri.toString())
            assertEquals(listOf("作者：Author"), book.tags)
            assertEquals(LocalDateTime.MIN, book.lastUpdated)
            assertEquals("", book.subtitle)
            assertEquals("", book.description)
            assertEquals("", book.publishingHouse)
            assertEquals(0, book.wordCount.count)
            assertEquals(false, book.isComplete)
        }
    }

    @Test
    fun emptyCoverPreservesTheHostUriValue() {
        val book = ParsedExploreBook("1", "Title", "", "")
            .toParsedBookInformation().toMutableBookInformation()

        assertEquals("", book.coverUri.toString())
        assertEquals(Uri.EMPTY, book.coverUri)
    }

    @Test
    fun detailMappingPreservesPublisherTagsAndRealDatesWithoutLeakingLinks() {
        val parsed = LinovelibHtmlParser().parseBookInformation("1", """
            <h1 class="book-title">Title</h1>
            <meta property="og:image" content="https://example.com/cover.jpg">
            <span class="authorname"><a href="/author">Author</a></span>
            <div class="book-detail-info">
                <div class="book-meta book-layout-inline">123字 完结</div>
            </div>
            <div class="book-status">2026-10-12</div>
            <span class="tag-small-group">
                <em class="tag-small"><a href="/tag"> Tag </a></em>
                <em class="tag-small">Tag</em><em class="tag-small"> </em>
                <em class="tag-small orange"><a href="/publisher">Publisher</a></em>
            </span>
        """)
        assertEquals(listOf("Tag", "Tag"), parsed.tags)
        val book: BookInformation = parsed.toMutableBookInformation()
        assertEquals(listOf("作者：Author", "Tag"), book.tags)
        assertEquals("Publisher", book.publishingHouse)
        assertEquals("2026-10-12T00:00", book.lastUpdated.toString())
        assertEquals(123, book.wordCount.count)
        assertTrue(book.isComplete)
    }

    @Test
    fun catalogMappingRetainsStableVolumeAndAppChapterIds() {
        val result = ParsedCatalog("12", listOf(
            ParsedVolume("Volume A", listOf(ParsedChapter("34", "Chapter"))),
            ParsedVolume("Volume B", emptyList())
        )).toBookVolumes()
        assertEquals(listOf("12-1", "12-2"), result.volumes.map { it.volumeId })
        assertEquals("Volume A", result.volumes.first().volumeTitle)
        assertEquals("linovelib-v3:34", result.volumes.first().chapters.single().id)
        assertEquals("Chapter", result.volumes.first().chapters.single().title)
    }
}
