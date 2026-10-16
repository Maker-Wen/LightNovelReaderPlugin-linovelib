package io.nightfish.lightnovelreader.plugin.linovelib.migration

import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The real API 3 serializers reject fields unavailable in that host contract. */
class MigrationBodyHostTest {
    @Test fun malformedTwReadingDatesAreSkippedOnTheShrunkHost() {
        val reading = buildJsonObject {
            put("id", "1"); put("lastReadTime", "not-a-time"); put("totalReadTime", 10)
            put("readingProgress", 0.8); put("lastReadChapterId", "linovelib-v3:11"); put("lastReadChapterTitle", "continue")
            put("currentChapterReadingProgressMap", buildJsonObject { put("linovelib-v3:11", 0.8); put("linovelib-v3:12", 1.2); put("broken", 0.5) })
            put("maxChapterReadingProgressMap", buildJsonObject { put("linovelib-v3:11", 1.2) })
        }
        val tw = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).let {
            it.copy(data = JsonObject(it.data + ("userReadingDataEntities" to JsonArray(listOf(reading)))))
        }
        assertFalse(MigrationPlanner.hasRecoverableData(tw))
        val plan = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw)
        assertFalse(plan.hasRecoverableData)
        assertTrue(plan.snapshot.data.getValue("userReadingDataEntities").jsonArray.isEmpty())
    }

    @Test fun legalSentinelsAndValidChapterProgressSurviveOnTheShrunkHost() {
        val reading = buildJsonObject {
            put("id", "1"); put("lastReadTime", java.time.LocalDateTime.MIN.toString()); put("totalReadTime", -1)
            put("readingProgress", 0.0); put("lastReadChapterId", ""); put("lastReadChapterTitle", "")
            put("currentChapterReadingProgressMap", buildJsonObject { put("linovelib-v3:11", 0.8); put("linovelib-v3:12", 1.2); put("broken", 0.5) })
            put("maxChapterReadingProgressMap", buildJsonObject { put("linovelib-v3:11", 1.0); put("linovelib-v3:12", -0.2) })
        }
        val tw = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).let {
            it.copy(data = JsonObject(it.data + ("userReadingDataEntities" to JsonArray(listOf(reading)))))
        }
        val plan = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw)
        assertTrue(plan.hasRecoverableData)
        val retained = plan.snapshot.data.getValue("userReadingDataEntities").jsonArray.single().jsonObject
        assertEquals(java.time.LocalDateTime.MIN.toString(), retained.getValue("lastReadTime").jsonPrimitive.content)
        assertEquals(-1, retained.getValue("totalReadTime").jsonPrimitive.int)
        assertEquals("", retained.getValue("lastReadChapterId").jsonPrimitive.content)
        assertEquals("", retained.getValue("lastReadChapterTitle").jsonPrimitive.content)
        assertEquals(0.0, retained.getValue("readingProgress").jsonPrimitive.double, 0.0)
        assertEquals(buildJsonObject { put("linovelib-v3:11", 0.8) }, retained["currentChapterReadingProgressMap"])
        assertEquals(buildJsonObject { put("linovelib-v3:11", 1.0) }, retained["maxChapterReadingProgressMap"])
    }

    @Test fun invalidRecordTimesAreSkippedOnTheShrunkHost() {
        val record = buildJsonObject {
            put("bookId", "1"); put("date", "2026-10-11"); put("reads", 3); put("seconds", 200)
            put("firstSeen", "invalid-time"); put("lastSeen", "12:00")
        }
        val tw = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).let {
            it.copy(data = JsonObject(it.data + ("bookRecordEntities" to JsonArray(listOf(record)))))
        }
        val plan = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw)
        assertFalse(plan.hasRecoverableData)
        assertTrue(plan.snapshot.data.getValue("bookRecordEntities").jsonArray.isEmpty())
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
            put("id", "linovelib-v3:11"); put("title", "chapter"); put("content", content.toString())
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
