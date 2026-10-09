package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfBookMetadata
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessingRepositoryApi
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDateTime

class LinovelibCoverDataTest {
    @Test
    fun startupChecksEachReadingAndBookshelfBookOnceWithoutAccessingCatalogsOrProgress() {
        val calls = mutableListOf<String>()
        val processing = FakeTextProcessing()
        val data = LinovelibCoverData(
            localData(calls, "42", "43"), processing, bookshelves("42", "44")
        ) { true }

        data.onLoad()

        assertEquals(
            listOf("getAllUserReadingData", "getBookInformation:42", "getBookInformation:43", "getBookInformation:44"),
            calls
        )
        assertSame(data, processing.processors.single())
    }

    @Test
    fun directCoverRepairOnlyLooksUpBookInformation() = runBlocking {
        val calls = mutableListOf<String>()
        val data = LinovelibCoverData(localData(calls), FakeTextProcessing(), bookshelves()) { true }

        data.repairCover("42")

        assertEquals(listOf("getBookInformation:42"), calls)
    }

    @Test
    fun registeredProcessorPreservesCatalogAndContentAndFollowsTheActiveSource() {
        var active = true
        val calls = mutableListOf<String>()
        val processing = FakeTextProcessing()
        LinovelibCoverData(localData(calls), processing, bookshelves()) { active }.onLoad()
        calls.clear()
        val processor = processing.processors.single()
        val volumes = BookVolumes("42", listOf(Volume(
            "42-1", "Formatted volume title",
            listOf(ChapterInformation("linovelib-v3:101", "Formatted chapter title"))
        )))
        val json = buildJsonObject { put("custom", "keep all fields") }
        val chapter = MutableChapterContent("linovelib-v3:101", "Title", json, "", "linovelib-v3:102")
        val componentProcessor = ComponentProcessor(emptyMap(), emptyMap(), json)

        assertSame(volumes, processor.processBookVolumes(volumes))
        assertSame(chapter, processor.processChapterContent("42", chapter, componentProcessor))
        assertSame(json, chapter.content)
        assertSame(json, componentProcessor.content)
        assertEquals("文本", processor.processText("文本"))
        assertTrue(processor.enabled)
        active = false
        assertFalse(processor.enabled)
        assertTrue(processing.processors.filter { it.enabled }.isEmpty())
        active = true
        assertTrue(processor.enabled)
        assertTrue(calls.isEmpty())
    }

    private fun localData(calls: MutableList<String>, vararg readingIds: String): LocalBookDataSourceApi =
        Proxy.newProxyInstance(
            LocalBookDataSourceApi::class.java.classLoader,
            arrayOf(LocalBookDataSourceApi::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllUserReadingData" -> {
                    calls.add(method.name)
                    readingIds.map { id ->
                        Proxy.newProxyInstance(
                            UserReadingData::class.java.classLoader,
                            arrayOf(UserReadingData::class.java)
                        ) { _, property, _ ->
                            check(property.name == "getId") { "Do not access reading progress: ${property.name}" }
                            id
                        } as UserReadingData
                    }
                }
                "getBookInformation" -> {
                    calls.add("${method.name}:${args!![0]}")
                    null
                }
                else -> error("Unexpected local data access: ${method.name}")
            }
        } as LocalBookDataSourceApi

    private fun bookshelves(vararg bookIds: String): BookshelfRepositoryApi = Proxy.newProxyInstance(
        BookshelfRepositoryApi::class.java.classLoader,
        arrayOf(BookshelfRepositoryApi::class.java)
    ) { _, method, _ ->
        check(method.name == "getAllBookshelfBooksMetadata") { "Unexpected bookshelf access: ${method.name}" }
        bookIds.map { BookshelfBookMetadata(it, LocalDateTime.MIN, listOf(1)) }
    } as BookshelfRepositoryApi

    private class FakeTextProcessing : TextProcessingRepositoryApi {
        val processors = mutableListOf<TextProcessor>()

        override fun registerProcessors(processor: TextProcessor) {
            processors.add(processor)
        }
    }
}
