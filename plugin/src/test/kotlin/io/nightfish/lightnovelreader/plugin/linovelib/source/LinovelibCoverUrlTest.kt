package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibCoverUrlTest {
    private val parser = LinovelibHtmlParser()

    @Test
    fun detailAndListPagesPreserveExplicitCoverDomains() = listOf(
        "www.bilinovel.net", "www.bilinovel.com", "tw.linovelib.com", "images.example.com"
    ).forEach { host ->
        val cover = "https://$host/files/article/image/1/1804/1804s.jpg"
        val detail = parser.parseBookInformation(
            "1804",
            """<h1 class="book-title">Book</h1><meta property="og:image" content="$cover">"""
        )
        val list = requireNotNull(parser.parseListPage(
            """<a class="book-layout" href="/novel/1804.html"><img data-src="$cover" alt="Book"></a>"""
        ))

        assertEquals(cover, detail.coverUrl)
        assertEquals(cover, list.books.single().coverUrl)
    }
}
