package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.ContextWrapper
import android.graphics.Bitmap
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LinovelibVolumeCoverHostTest {
    private val html = javaClass.getResource("/html/catalog-5209.html")!!.readText()
    private val coverIds = listOf("315754", "317139", "320816", "322438")

    @Test
    fun loadedRealCatalogSuppliesEachVolumeCoverWithoutChapterContent() = runBlocking {
        val directory = temporaryDirectory()
        try {
            val context = context(directory)
            val requests = mutableListOf<String>()
            val source = source(context) { url ->
                requests += url
                html
            }
            val cachedCovers = coverIds.map { cacheCover(directory, it) }

            val volumes = source.getBookVolumes("5209").volumes

            assertEquals(4, volumes.size)
            assertEquals(
                listOf("linovelib-v3:325354", "linovelib-v3:326435", "linovelib-v3:332778", "linovelib-v3:333951"),
                volumes.map { it.chapters.first().id }
            )
            volumes.forEachIndexed { index, volume ->
                val uri = source.getCoverUriInVolume("5209", volume, mutableMapOf(), context)
                assertEquals(cachedCovers[index].toURI().toString(), uri?.toString())
            }
            assertEquals(listOf("https://www.bilinovel.net/novel/5209/catalog"), requests)
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun loadedCatalogWithoutExplicitCoversDoesNotSelectIllustrationImages() = runBlocking {
        val directory = temporaryDirectory()
        try {
            // Mutate the real catalog to exercise removal of both legacy title matches.
            val document = Jsoup.parse(html)
            document.select(".volume-cover img").removeAttr("data-src")
            document.select(".catalog-volume").forEachIndexed { index, volume ->
                volume.selectFirst(".jsChapter .chapter-index")!!.text(if (index % 2 == 0) "插图" else "插圖")
            }
            val context = context(directory)
            val source = source(context) { document.outerHtml() }
            val volumes = source.getBookVolumes("5209").volumes

            assertEquals(4, volumes.size)
            volumes.forEachIndexed { index, volume ->
                val firstChapter = volume.chapters.first()
                assertEquals(if (index % 2 == 0) "插图" else "插圖", firstChapter.title)
                val chapter = MutableChapterContent(
                    id = firstChapter.id,
                    title = firstChapter.title,
                    content = Json.parseToJsonElement(
                        javaClass.getResource("/migration/real-illustration-components.json")!!.readText()
                    ).jsonObject
                )
                assertNull(source.getCoverUriInVolume(
                    "5209", volume, mutableMapOf<String, ChapterContent>(chapter.id to chapter), context
                ))
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun coverLocalizationFailureDoesNotSelectAnIllustrationImage() = runBlocking {
        val directory = temporaryDirectory()
        try {
            // A file at the cache directory path makes localization fail without a network request.
            val imageDirectory = directory.resolve("linovelib/chapter-images")
            assertTrue(imageDirectory.parentFile!!.mkdirs())
            assertTrue(imageDirectory.createNewFile())
            val document = Jsoup.parse(html)
            document.selectFirst(".jsChapter .chapter-index")!!.text("插图")
            val context = context(directory)
            val events = mutableListOf<String>()
            val requests = mutableListOf<String>()
            val source = LinovelibWebDataSource(
                context, LinovelibMetadataStore(), LinovelibDiagnostics { _, _, message, _ -> events += message }
            ) { url ->
                requests += url
                document.outerHtml()
            }
            val volumes = source.getBookVolumes("5209").volumes
            assertEquals(4, volumes.size)
            val volume = volumes.first()
            val firstChapter = volume.chapters.first()
            val chapter = MutableChapterContent(
                id = firstChapter.id,
                title = firstChapter.title,
                content = Json.parseToJsonElement(
                    javaClass.getResource("/migration/real-illustration-components.json")!!.readText()
                ).jsonObject
            )

            assertNull(source.getCoverUriInVolume(
                "5209", volume, mutableMapOf<String, ChapterContent>(chapter.id to chapter), context
            ))
            assertTrue(events.single().startsWith("IMAGE_DIRECTORY_ERROR "))
            assertEquals(listOf("https://www.bilinovel.net/novel/5209/catalog"), requests)
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun reloadingCatalogRemovesCoversNoLongerProvidedByThePage() = runBlocking {
        val directory = temporaryDirectory()
        try {
            var currentHtml = html
            val context = context(directory)
            val source = source(context) { currentHtml }
            val cachedCover = cacheCover(directory, coverIds.first())
            val original = source.getBookVolumes("5209").volumes
            assertEquals(4, original.size)
            assertEquals(
                cachedCover.toURI().toString(),
                source.getCoverUriInVolume("5209", original.first(), mutableMapOf(), context)?.toString()
            )

            currentHtml = Jsoup.parse(html).apply { select(".volume-cover").remove() }.outerHtml()
            val refreshed = source.getBookVolumes("5209").volumes

            assertEquals(4, refreshed.size)
            refreshed.forEach { volume ->
                assertNull(source.getCoverUriInVolume("5209", volume, mutableMapOf(), context))
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    private fun cacheCover(directory: File, coverId: String): File {
        val coverUrl = "https://img3.readpai.com/cover/5209/$coverId.jpg"
        val cacheKey = MessageDigest.getInstance("SHA-256")
            .digest(coverUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val cachedCover = directory.resolve("linovelib/chapter-images/$cacheKey")
        cachedCover.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            cachedCover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        return cachedCover
    }

    private fun source(context: ContextWrapper, load: suspend (String) -> String) =
        LinovelibWebDataSource(context, LinovelibMetadataStore(), LinovelibDiagnostics { _, _, _, _ -> }, load)

    private fun context(directory: File) = object : ContextWrapper(null) {
        override fun getFilesDir() = directory
    }

    private fun temporaryDirectory(): File =
        File.createTempFile("linovelib-cover-hook-", ".tmp", File("/data/local/tmp")).apply {
            check(delete())
            check(mkdir())
        }
}
