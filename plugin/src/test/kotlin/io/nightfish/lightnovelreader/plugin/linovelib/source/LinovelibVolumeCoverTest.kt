package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibVolumeCoverTest {
    @Test
    fun `uses first image from simplified and traditional illustration chapters as volume cover`() {
        val images = Json.parseToJsonElement(
            javaClass.getResource("/migration/real-illustration-components.json")!!.readText()
        ).jsonObject.getValue("components").jsonArray
        assertEquals(2, images.size)
        // Reversal tests the selection rule; only the original order comes from the backup.
        for (orderedImages in listOf(images.toList(), images.reversed())) {
            val expectedUri = orderedImages.first().jsonObject.getValue("data")
                .jsonObject.getValue("uri").jsonPrimitive.content
            for (title in listOf("插图", "插圖")) {
                val volume = Volume(
                    volumeId = "volume-1",
                    volumeTitle = "Volume 1",
                    chapters = listOf(ChapterInformation("linovelib-v3:121898", title))
                )
                val chapter = MutableChapterContent(
                    id = "linovelib-v3:121898",
                    title = title,
                    content = buildJsonObject {
                        putJsonArray("components") { orderedImages.forEach { add(it) } }
                    }
                )

                assertEquals(title, expectedUri, findVolumeCoverUri(volume, mapOf(chapter.id to chapter)))
            }
        }
    }

    @Test
    fun `does not infer volume covers from other chapter titles`() {
        for (title in listOf("Chapter 1", "Illustrations", "插画")) {
            val volume = Volume(
                volumeId = "volume-1",
                volumeTitle = "Volume 1",
                chapters = listOf(ChapterInformation("chapter-1", title))
            )
            val chapter = MutableChapterContent(
                id = "chapter-1",
                title = title,
                content = contentWithImage("file:/chapter-images/inline.webp")
            )

            assertNull(title, findVolumeCoverUri(volume, mapOf(chapter.id to chapter)))
        }
    }

    @Test
    fun `does not treat a regular title containing illustration as a cover chapter`() {
        val volume = Volume(
            volumeId = "volume-1",
            volumeTitle = "Volume 1",
            chapters = listOf(ChapterInformation("chapter-3", "第3章 插图里的秘密"))
        )
        val chapter = MutableChapterContent(
            id = "chapter-3",
            title = "第3章 插图里的秘密",
            content = contentWithImage("file:/chapter-images/inline.webp")
        )

        assertNull(findVolumeCoverUri(volume, mapOf(chapter.id to chapter)))
    }

    private fun contentWithImage(uri: String) = buildJsonObject {
        putJsonArray("components") {
            addJsonObject {
                put("id", ImageComponentData.ID)
                putJsonObject("data") {
                    put("uri", uri)
                }
            }
        }
    }
}
