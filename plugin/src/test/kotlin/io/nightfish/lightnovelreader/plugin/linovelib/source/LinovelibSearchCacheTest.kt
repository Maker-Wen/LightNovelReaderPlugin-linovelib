package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibSearchCacheTest {
    @Test
    fun `entries expire thirty seconds from their write regardless of reads`() {
        var now = 0L
        val cache = LinovelibSearchCache { now }
        val books = listOf(ParsedExploreBook("1", "Book", "", ""))
        cache.remember("keyword", books)

        now = 29_999L
        assertEquals(books, cache.get("keyword"))
        now = 30_000L
        assertNull(cache.get("keyword"))
    }

    @Test
    fun `retains only eight completed queries and copies the supplied list`() {
        val cache = LinovelibSearchCache { 0L }
        val book = ParsedExploreBook("1", "Book", "", "")
        val books = mutableListOf(book)
        repeat(9) { cache.remember("keyword $it", books) }
        books.clear()

        assertNull(cache.get("keyword 0"))
        (1..8).forEach { assertEquals(listOf(book), cache.get("keyword $it")) }
    }
}
