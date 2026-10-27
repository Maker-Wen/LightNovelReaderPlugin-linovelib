package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibVolumeCoverTest {
    private val parser = LinovelibHtmlParser()
    private val metadata = LinovelibMetadataStore()
    private val html = javaClass.getResource("/html/catalog-5209.html")!!.readText()

    @Test
    fun `real catalog supplies one explicit cover for each volume`() {
        val catalog = parser.parseCatalog("5209", html)
        metadata.rememberCatalog(catalog)

        assertEquals(4, catalog.volumes.size)
        val covers = listOf("315754", "317139", "320816", "322438")
        catalog.volumes.forEachIndexed { index, volume ->
            val expected = "https://img3.readpai.com/cover/5209/${covers[index]}.jpg"
            assertEquals(expected, volume.coverUrl)
            assertEquals(
                expected,
                metadata.volumeCover("5209", LinovelibChapterIds.forApp(volume.chapters.first().id))
            )
        }
        assertNull(metadata.volumeCover("another-book", catalog.volumes.first().chapters.first().id))
    }

    @Test
    fun `missing and blank cover URLs do not use illustration titles or placeholders`() {
        for (coverUrl in listOf(null, "", " \t\n")) {
            val document = Jsoup.parse(html)
            val images = document.select(".volume-cover img")
            if (coverUrl == null) images.removeAttr("data-src") else images.attr("data-src", coverUrl)
            val catalog = parser.parseCatalog("5209", document.outerHtml())
            metadata.rememberCatalog(catalog)

            assertEquals(4, catalog.volumes.size)
            catalog.volumes.forEach { volume ->
                assertEquals("封面插画", volume.chapters.first().title)
                assertNull("data-src=$coverUrl", volume.coverUrl)
                assertNull("data-src=$coverUrl", metadata.volumeCover("5209", volume.chapters.first().id))
            }
        }
    }

    @Test
    fun `refreshed catalog without explicit covers removes previous mappings`() {
        val original = parser.parseCatalog("5209", html)
        metadata.rememberCatalog(original)
        val document = Jsoup.parse(html)
        document.select(".volume-cover").remove()
        metadata.rememberCatalog(parser.parseCatalog("5209", document.outerHtml()))

        original.volumes.forEach { volume ->
            assertNull(metadata.volumeCover("5209", volume.chapters.first().id))
        }
    }
}
