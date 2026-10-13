package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.BookInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LinovelibBookMappingHostTest {
    @Test
    fun searchAndFallbackSummariesUseTheSameAuthorTagAndUnknownDate() {
        val summary = ParsedExploreBook("1", "Title", "Author", "https://example.com/cover.jpg")
        val metadata = LinovelibMetadataStore().apply { rememberBooks(listOf(summary)) }
        val search = summary.toParsedBookInformation().toMutableBookInformation()
        val fallback = metadata.fallbackBookInformation("1")!!.toBookInformation()

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
    fun detailMappingPreservesPublisherTagsAndRealDatesWithoutLeakingLinks() {
        val parsed = ParsedExploreBook("1", "Title", "Author", "https://example.com/cover.jpg").toParsedBookInformation().copy(
            tags = listOf("Tag", "Tag", ""), publishingHouse = "Publisher", wordCount = 123,
            lastUpdated = LocalDate.of(2026, 10, 12), isComplete = true,
            authorUrl = "/author", relatedLinks = mapOf("Tag" to "/tag")
        )
        val book: BookInformation = parsed.toMutableBookInformation()
        assertEquals(listOf("作者：Author", "Tag"), book.tags)
        assertEquals("Publisher", book.publishingHouse)
        assertEquals("2026-10-12T00:00", book.lastUpdated.toString())
        assertEquals(123, book.wordCount.count)
        assertTrue(book.isComplete)
        assertTrue(parsed.copy(title = "   ").toBookInformation().isEmpty())
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
