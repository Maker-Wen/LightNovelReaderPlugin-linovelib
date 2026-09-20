package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibVolumeCoverTest {
    @Test
    fun `uses first image from illustration chapter as volume cover`() {
        val volume = Volume(
            volumeId = "volume-1",
            volumeTitle = "Volume 1",
            chapters = listOf(ChapterInformation("illustrations", "Illustrations"))
        )
        val chapter = ChapterContent(
            id = "illustrations",
            title = "Illustrations",
            content = contentWithImage("file:/chapter-images/cover.webp")
        )

        assertEquals(
            "file:/chapter-images/cover.webp",
            findVolumeCoverUri(volume, mapOf(chapter.id to chapter))
        )
    }

    @Test
    fun `does not use images from regular chapters as volume cover`() {
        val volume = Volume(
            volumeId = "volume-1",
            volumeTitle = "Volume 1",
            chapters = listOf(ChapterInformation("chapter-1", "Chapter 1"))
        )
        val chapter = ChapterContent(
            id = "chapter-1",
            title = "Chapter 1",
            content = contentWithImage("file:/chapter-images/inline.webp")
        )

        assertNull(findVolumeCoverUri(volume, mapOf(chapter.id to chapter)))
    }

    @Test
    fun `does not treat a regular title containing illustration as a cover chapter`() {
        val volume = Volume(
            volumeId = "volume-1",
            volumeTitle = "Volume 1",
            chapters = listOf(ChapterInformation("chapter-3", "第3章 插图里的秘密"))
        )
        val chapter = ChapterContent(
            id = "chapter-3",
            title = "第3章 插图里的秘密",
            content = contentWithImage("file:/chapter-images/inline.webp")
        )

        assertNull(findVolumeCoverUri(volume, mapOf(chapter.id to chapter)))
    }

    private fun contentWithImage(uri: String) = buildJsonObject {
        putJsonArray("components") {
            addJsonObject {
                put("id", ImageComponentData.id.toString())
                putJsonObject("data") {
                    put("uri", uri)
                }
            }
        }
    }
}
