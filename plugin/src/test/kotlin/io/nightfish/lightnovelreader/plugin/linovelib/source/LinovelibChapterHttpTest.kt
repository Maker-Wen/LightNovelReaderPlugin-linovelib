package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibChapterHttpTest {
    @Test
    fun `chapter pages resolve against final URLs and stop before the next chapter`() = runBlocking {
        LinovelibHttpTestSite { exchange ->
            when (exchange.requestURI.path) {
                "/novel/100/101.html" -> {
                    exchange.responseHeaders.add("Location", "/mirror/novel/100/101.html")
                    exchange.respond(302)
                }
                "/mirror/novel/100/101.html" -> exchange.respond(200, """
                    <h1 id="atitle">Chapter</h1><div id="acontent"><p>First page</p><img src="/files/one.jpg"></div>
                    <script>var ReadParams={url_previous:"/novel/100/100.html",url_next:"101_2.html"};</script>
                """)
                "/mirror/novel/100/101_2.html" -> exchange.respond(200, """
                    <div id="acontent"><p>Second page</p><img src="images/two.jpg"></div>
                    <script>var ReadParams={url_next:"/novel/100/102.html"};</script>
                """)
                else -> exchange.respond(404)
            }
        }.use { site ->
            val chapter = loadChapter(site)
            assertEquals(LinovelibChapterIds.forApp("101"), chapter.id)
            assertEquals("Chapter", chapter.title)
            assertEquals(LinovelibChapterIds.forApp("100"), chapter.previousChapterId)
            assertEquals(LinovelibChapterIds.forApp("102"), chapter.nextChapterId)
            assertEquals(listOf(
                ParsedContentBlock.Text("First page"), ParsedContentBlock.Image("${site.host}/files/one.jpg"),
                ParsedContentBlock.Text("Second page"), ParsedContentBlock.Image("${site.host}/mirror/novel/100/images/two.jpg")
            ), chapter.blocks)
            assertEquals(listOf("/novel/100/101.html", "/mirror/novel/100/101.html", "/mirror/novel/100/101_2.html"), site.requests.map { it.path })
        }
    }

    @Test
    fun `a repeated chapter URL is loaded only once`() = runBlocking {
        LinovelibHttpTestSite { exchange -> exchange.respond(200, page("Only page", "101.html")) }.use { site ->
            val chapter = loadChapter(site)
            assertEquals(listOf(ParsedContentBlock.Text("Only page")), chapter.blocks)
            assertEquals(1, site.requests.size)
        }
    }

    @Test
    fun `chapter pagination keeps its existing page limit`() = runBlocking {
        LinovelibHttpTestSite { exchange ->
            val index = Regex("101_(\\d+)\\.html").find(exchange.requestURI.path)?.groupValues?.get(1)?.toInt() ?: 1
            exchange.respond(200, page("Page $index", "101_${index + 1}.html"))
        }.use { site ->
            val chapter = loadChapter(site)
            assertEquals(100, site.requests.size)
            assertEquals(100, chapter.blocks.size)
            assertEquals(ParsedContentBlock.Text("Page 100"), chapter.blocks.last())
            assertTrue(site.requests.none { it.path.endsWith("101_101.html") })
        }
    }

    @Test
    fun `a failed later chapter page propagates the resource error without marking offline`() = runBlocking {
        LinovelibHttpTestSite { exchange ->
            if (exchange.requestURI.path.endsWith("101.html")) exchange.respond(200, page("First page", "101_2.html"))
            else exchange.respond(404)
        }.use { site ->
            val client = site.client()
            val failure = runCatching { loadChapter(site, client) }.exceptionOrNull()
            assertTrue(failure is LinovelibHttpStatusException)
            assertEquals(404, (failure as LinovelibHttpStatusException).statusCode)
            assertEquals(2, site.requests.size)
            assertFalse(client.isOffLineFlow.value)
        }
    }

    private suspend fun loadChapter(site: LinovelibHttpTestSite, client: LinovelibHttpClient = site.client()) = getChapterContentPages(
        chapterId = LinovelibChapterIds.forApp("101"), bookId = "100",
        parser = LinovelibHtmlParser(site.host), diagnostics = LinovelibDiagnostics { _, _, _, _ -> },
        loadPage = client::loadChapterPage, host = site.host
    )

    private fun page(text: String, next: String) = """
        <div id="acontent"><p>$text</p></div><script>var ReadParams={url_next:"$next"};</script>
    """
}
