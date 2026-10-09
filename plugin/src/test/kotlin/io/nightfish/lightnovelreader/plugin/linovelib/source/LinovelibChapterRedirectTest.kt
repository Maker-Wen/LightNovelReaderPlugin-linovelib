package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibChapterRedirectTest {
    private val parser = LinovelibHtmlParser(host = "https://tw.linovelib.com")
    private val finalUrl = "https://www.bilinovel.net/novel/1804/101.html"

    @Test
    fun chapterImagesResolveAgainstFinalResponseUrl() {
        val chapter = parser.parseChapterContent(
            chapterId = "101",
            html = """
                <div id="acontent">
                  <img src="/files/illustration.jpg">
                  <img data-src="images/second.jpg">
                  <p><img src="../third.jpg"></p>
                </div>
            """.trimIndent(),
            baseUrl = finalUrl
        )

        assertEquals(
            listOf(
                ParsedContentBlock.Image("https://www.bilinovel.net/files/illustration.jpg"),
                ParsedContentBlock.Image("https://www.bilinovel.net/novel/1804/images/second.jpg"),
                ParsedContentBlock.Image("https://www.bilinovel.net/novel/third.jpg")
            ),
            chapter.blocks
        )
    }

    @Test
    fun parserPreservesNextPageLinkForCallerResolution() {
        listOf(
            "101_2.html",
            "/novel/1804/101_2.html",
            "https://www.bilinovel.net/novel/1804/101_2.html?from=chapter"
        ).forEach { nextPageUrl ->
            val chapter = parser.parseChapterContent(
                chapterId = "101",
                html = """<script>var ReadParams={url_next:"$nextPageUrl"}</script>""",
                baseUrl = finalUrl
            )

            assertEquals(nextPageUrl, chapter.nextPageUrl)
        }
    }
}
