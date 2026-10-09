package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibCoverMigrationTest {
    private val parser = LinovelibHtmlParser()

    @Test
    fun detailAndListPagesMigrateOldSiteCovers() = listOf("www.bilinovel.com", "tw.linovelib.com").forEach { host ->
        val oldCover = "https://$host/files/article/image/1/1804/1804s.jpg"
        val expectedCover = "https://www.bilinovel.net/files/article/image/1/1804/1804s.jpg"
        val detail = parser.parseBookInformation(
            "1804",
            """<h1 class="book-title">Book</h1><meta property="og:image" content="$oldCover">"""
        )
        val list = parser.parseListRow(
            "Books",
            """<a class="book-layout" href="/novel/1804.html"><img data-src="$oldCover" alt="Book"></a>"""
        )

        assertEquals(expectedCover, detail.coverUrl)
        assertEquals(expectedCover, list.books.single().coverUrl)
    }

    @Test
    fun currentCoverUrlPreservesPathQueryAndFragment() {
        listOf("http://www.bilinovel.com", "https://www.bilinovel.com", "http://tw.linovelib.com", "https://tw.linovelib.com").forEach { origin ->
            assertEquals(
                "https://www.bilinovel.net/files/article/image/1/1804/1804s.jpg?size=large&v=2#cover",
                LinovelibUrls.currentCoverUrl(
                    "$origin/files/article/image/1/1804/1804s.jpg?size=large&v=2#cover"
                )
            )
        }
    }

    @Test
    fun currentCoverUrlLeavesUnrelatedUrlsAndEmptyValuesUntouched() {
        listOf(
            "https://tw.linovelib.com/novel/1804/101.html",
            "https://tw.linovelib.com.example.com/files/article/image/1/1804/1804s.jpg",
            "https://example.com/files/article/image/1/1804/1804s.jpg",
            "https://www.bilinovel.com.example.com/files/article/image/1/1804/1804s.jpg",
            "https://www.bilinovel.net/files/article/image/1/1804/1804s.jpg",
            "https://www.bilinovel.com/novel/1804/101.html",
            "https://www.bilinovel.com/files/illustration.jpg",
            "file:///storage/emulated/0/books/1804/cover.jpg",
            ""
        ).forEach { url ->
            assertEquals(url, url, LinovelibUrls.currentCoverUrl(url))
        }
    }
}
