package io.nightfish.lightnovelreader.plugin.linovelib.migration

import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The real API 3 serializers reject fields unavailable in that host contract. */
class MigrationBodyHostTest {
    @Test fun malformedTwDatesRecoverOnTheShrunkHostWithoutLosingContinuation() {
        val reading = buildJsonObject {
            put("id", "1"); put("lastReadTime", "not-a-time"); put("totalReadTime", 10)
            put("readingProgress", 1.2); put("lastReadChapterId", "11"); put("lastReadChapterTitle", "continue")
            put("currentChapterReadingProgressMap", buildJsonObject { put("11", 1.2); put("broken", 0.5) })
            put("maxChapterReadingProgressMap", buildJsonObject { put("11", 1.2) })
        }
        val tw = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).let {
            it.copy(data = JsonObject(it.data + ("userReadingDataEntities" to JsonArray(listOf(reading)))))
        }
        assertTrue(MigrationPlanner.hasRecoverableData(tw))
        val plan = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw)
        assertTrue(plan.hasRecoverableData)
        val recovered = plan.snapshot.data.getValue("userReadingDataEntities").jsonArray.single().jsonObject
        assertEquals("-999999999-01-01T00:00", recovered.getValue("lastReadTime").jsonPrimitive.content)
        assertEquals("linovelib-v3:11", recovered.getValue("lastReadChapterId").jsonPrimitive.content)
        assertEquals(1.0, recovered.getValue("readingProgress").jsonPrimitive.double, 0.0)
        assertEquals(setOf("linovelib-v3:11"), recovered.getValue("currentChapterReadingProgressMap").jsonObject.keys)
    }

    @Test fun recoverableTextWithUnsupportedFieldsLoadsOnTheActualHost() {
        val original = buildJsonObject { put("text", "retained text"); put("unsupported", 1) }
        assertThrows(IllegalArgumentException::class.java) { SimpleTextComponentData.jsonSerializer.fromJsonElement(original) }
        val repaired = migratedData("simple_text", original)
        assertEquals("retained text", SimpleTextComponentData.jsonSerializer.fromJsonElement(repaired).text)
    }

    @Test fun recoverableImageWithUnsupportedFieldsLoadsOnTheActualHost() {
        val original = buildJsonObject { put("uri", "file:/existing/image.png"); put("unsupported", true) }
        assertThrows(IllegalArgumentException::class.java) { ImageComponentData.jsonSerializer.fromJsonElement(original) }
        val repaired = migratedData("image", original)
        assertEquals("file:/existing/image.png", ImageComponentData.jsonSerializer.fromJsonElement(repaired).uri.toString())
    }

    private fun migratedData(id: String, data: JsonObject): JsonObject {
        val content = buildJsonObject { put("components", JsonArray(listOf(buildJsonObject { put("id", id); put("data", data) }))) }
        val body = buildJsonObject {
            put("id", "11"); put("title", "chapter"); put("content", content.toString())
            put("lastChapter", ""); put("nextChapter", "")
        }
        val tw = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).let {
            it.copy(data = JsonObject(it.data + ("chapterContentEntities" to JsonArray(listOf(body)))))
        }
        val result = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw).snapshot
        return Json.parseToJsonElement(result.data.getValue("chapterContentEntities").jsonArray.single().jsonObject
            .getValue("content").jsonPrimitive.content).jsonObject.getValue("components").jsonArray.single().jsonObject
            .getValue("data").jsonObject
    }
}
