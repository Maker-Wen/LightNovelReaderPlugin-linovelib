package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationPlannerTest {
    @Test
    fun preservesCurrentWholeCatalogAndSupplementsIndividualBodiesWithoutTextConversion() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "bookInformationEntities" to listOf(info("1", "当前资料")),
            "volumeEntities" to listOf(volume("1", "current-volume", listOf("linovelib-v3:11"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "当前章名")),
            "chapterContentEntities" to listOf(body("linovelib-v3:11", "当前正文")),
        )
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "bookInformationEntities" to listOf(info("1", "繁體資料"), info("2", "獨有繁體資料")),
            "volumeEntities" to listOf(volume("1", "old-volume", listOf("linovelib-v3:11", "linovelib-v3:12")), volume("2", "tw-volume-2", listOf("linovelib-v3:21"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "舊章名"), chapter("linovelib-v3:12", "孤立舊章"), chapter("linovelib-v3:21", "另一章")),
            "chapterContentEntities" to listOf(body("linovelib-v3:11", "舊正文"), body("linovelib-v3:12", "獨有繁體正文", previous = "linovelib-v3:11"), body("linovelib-v3:21", "圖片引用 file:/old/image.png")),
        )
        val result = MigrationPlanner.plan(current, tw).snapshot.data
        assertEquals(listOf("当前资料", "獨有繁體資料"), result.rows("bookInformationEntities").map { it.text("title") })
        assertEquals(listOf("current-volume", "tw-volume-2"), result.rows("volumeEntities").map { it.text("volumeId") })
        val bodies = result.rows("chapterContentEntities").associateBy { it.text("id") }
        assertTrue(bodies.getValue("linovelib-v3:11").text("content").contains("当前正文"))
        assertTrue(bodies.getValue("linovelib-v3:12").text("content").contains("獨有繁體正文"))
        assertEquals("linovelib-v3:11", bodies.getValue("linovelib-v3:12").text("lastChapter"))
        assertTrue(bodies.getValue("linovelib-v3:21").text("content").contains("file:/old/image.png"))
    }

    @Test
    fun fallsBackToTwWhenCurrentCatalogOrBodyIsEmpty() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "volumeEntities" to listOf(volume("1", "empty-current", emptyList())),
            "chapterContentEntities" to listOf(body("linovelib-v3:11", "", empty = true)),
        )
        val old = catalogTw()
        val result = MigrationPlanner.plan(current, old).snapshot.data
        assertEquals("tw-volume", result.rows("volumeEntities").single().text("volumeId"))
        assertTrue(result.rows("chapterContentEntities").single().text("content").contains("舊正文"))
    }

    @Test
    fun acceptsOnlyV3ChapterIdsAndExplicitlyEmptyOptionalReferences() {
        assertEquals("linovelib-v3:61960", MigrationPlanner.normalizeChapterId("linovelib-v3:61960"))
        assertEquals("", MigrationPlanner.normalizeChapterId("", allowEmpty = true))
        rejects { MigrationPlanner.normalizeChapterId("") }
        listOf("61960", "linovelib-v2:61960", "linovelib-v3:", "linovelib-v3:0",
            "linovelib-v3:061960", "linovelib-v3:-1", "linovelib-v3:61960 ").forEach { id ->
            rejects { MigrationPlanner.normalizeChapterId(id) }
            rejects { MigrationPlanner.normalizeChapterId(id, allowEmpty = true) }
        }
    }

    @Test
    fun rejectsLegacyIncomingCatalogAndBodyIdsEvenWhenV3CounterpartsExist() {
        listOf("11", "linovelib-v2:11").forEach { legacyId ->
            val tw = snapshot(MigrationData.TW_SOURCE_ID,
                "volumeEntities" to listOf(volume("1", "unsupported", listOf(legacyId)),
                    volume("2", "valid", listOf("linovelib-v3:21"))),
                "chapterInformationEntities" to listOf(chapter(legacyId, "legacy metadata"),
                    chapter("linovelib-v3:11", "v3 metadata"), chapter("linovelib-v3:21", "valid metadata")),
                "chapterContentEntities" to listOf(body(legacyId, "legacy body"),
                    body("linovelib-v3:11", "", empty = true), body("linovelib-v3:21", "valid body")),
            )
            val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw).snapshot.data
            assertEquals(listOf("valid"), result.rows("volumeEntities").map { it.text("volumeId") })
            val chapters = result.rows("chapterInformationEntities").associateBy { it.text("id") }
            assertEquals(setOf("linovelib-v3:11", "linovelib-v3:21"), chapters.keys)
            assertEquals("v3 metadata", chapters.getValue("linovelib-v3:11").text("title"))
            val bodies = result.rows("chapterContentEntities").associateBy { it.text("id") }
            assertEquals(setOf("linovelib-v3:11", "linovelib-v3:21"), bodies.keys)
            assertEquals("{\"components\":[]}", bodies.getValue("linovelib-v3:11").text("content"))
            assertTrue(bodies.getValue("linovelib-v3:21").text("content").contains("valid body"))
        }
    }

    @Test
    fun skipsLegacyIncomingReadingLocationsButStillFiltersCacheReferencesAndProgressItems() {
        listOf("11", "linovelib-v2:11").forEach { legacyId ->
            val unsupportedNavigation = JsonObject(body("linovelib-v3:21", "kept body", previous = legacyId)
                .toMutableMap().apply { put("nextChapter", JsonPrimitive(legacyId)) })
            val validNavigation = JsonObject(body("linovelib-v3:22", "other body", previous = "linovelib-v3:21")
                .toMutableMap().apply { put("nextChapter", JsonPrimitive("linovelib-v3:23")) })
            val tw = snapshot(MigrationData.TW_SOURCE_ID,
                "chapterContentEntities" to listOf(unsupportedNavigation, validNavigation),
                "userReadingDataEntities" to listOf(reading("1", 45, legacyId, 0.3f,
                    current = obj(legacyId to 0.9f, "linovelib-v3:11" to 0.2f),
                    maximum = obj(legacyId to 0.9f, "linovelib-v3:11" to 0.4f)),
                    reading("2", 45, "linovelib-v3:11", 0.3f,
                        current = obj(legacyId to 0.9f, "linovelib-v3:11" to 0.2f),
                        maximum = obj(legacyId to 0.9f, "linovelib-v3:11" to 0.4f))),
            )
            val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw).snapshot.data
            val bodies = result.rows("chapterContentEntities").associateBy { it.text("id") }
            assertEquals("", bodies.getValue("linovelib-v3:21").text("lastChapter"))
            assertEquals("", bodies.getValue("linovelib-v3:21").text("nextChapter"))
            assertEquals(validNavigation, bodies.getValue("linovelib-v3:22"))
            val reading = result.rows("userReadingDataEntities").single()
            assertEquals("2", reading.text("id"))
            assertEquals("linovelib-v3:11", reading.text("lastReadChapterId"))
            assertEquals("45", reading.text("totalReadTime"))
            assertEquals(obj("linovelib-v3:11" to 0.2f), reading["currentChapterReadingProgressMap"])
            assertEquals(obj("linovelib-v3:11" to 0.4f), reading["maxChapterReadingProgressMap"])
        }
    }

    @Test
    fun preservesCurrentReadingRowsWithLegacyChapterIdsAndBlocksMatchingIncomingRows() {
        listOf("11", "linovelib-v2:11").forEach { legacyId ->
            val affectedRows = listOf(
                reading("1", 30, legacyId, 0.2f),
                reading("1", 30, "linovelib-v3:11", 0.2f, current = obj(legacyId to 0.2f)),
                reading("1", 30, "linovelib-v3:11", 0.2f, maximum = obj(legacyId to 0.2f)),
            )
            affectedRows.forEach { currentReading ->
                val plan = MigrationPlanner.plan(
                    snapshot(MigrationData.CURRENT_SOURCE_ID, "userReadingDataEntities" to listOf(currentReading)),
                    snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(
                        reading("1", 45, "linovelib-v3:12", 0.5f))),
                )
                assertEquals(listOf(currentReading), plan.snapshot.data.rows("userReadingDataEntities"))
                assertFalse(plan.hasRecoverableData)
            }
        }
    }

    @Test
    fun addsPerBookStatisticsButCapsHourBucketsAtSixty() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 2, 100, "11:00", "12:00", favorite = true)),
            "dailyCountEntities" to listOf(daily(intArrayOf(30, 30, 30, 45))),
            "userReadingDataEntities" to listOf(reading("1", 120, "linovelib-v3:11", 0.1f, timestamp = "2026-10-11T12:00")),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 2, 200, "10:00", "13:00", finished = true)),
            "dailyCountEntities" to listOf(daily(intArrayOf(29, 30, 31, 40))),
            "userReadingDataEntities" to listOf(reading("1", 240, "linovelib-v3:12", 0.7f, timestamp = "2026-10-11T13:00")),
        )
        val result = MigrationPlanner.plan(current, old).snapshot.data
        val record = result.rows("bookRecordEntities").single()
        assertEquals("4", record.text("reads")); assertEquals("300", record.text("seconds"))
        assertEquals("10:00", record.text("firstSeen")); assertEquals("13:00", record.text("lastSeen"))
        assertEquals("true", record.text("isFavorited")); assertEquals("true", record.text("isFinished"))
        val reading = result.rows("userReadingDataEntities").single()
        assertEquals("360", reading.text("totalReadTime")); assertEquals("linovelib-v3:11", reading.text("lastReadChapterId"))
        assertEquals("2026-10-11T13:00", reading.text("lastReadTime"))
        val buckets = MigrationPlanner.decodeHours(result.rows("dailyCountEntities").single().text("timeCount"))
        assertEquals(listOf(59, 60, 60, 60), buckets.take(4))
    }

    @Test
    fun computesHostOverallProgressWithoutFilteringHistoricalChapterMap() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "volumeEntities" to listOf(volume("1", "v", listOf("linovelib-v3:11", "linovelib-v3:12"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "A"), chapter("linovelib-v3:12", "B")),
            "userReadingDataEntities" to listOf(reading("1", 5, "linovelib-v3:99", 0f,
                current = obj("linovelib-v3:11" to 0.1f), maximum = obj("linovelib-v3:11" to 0.3f, "linovelib-v3:99" to 0.2f))),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("1", 5, "linovelib-v3:12", 1f,
                current = obj("linovelib-v3:11" to 0.8f, "linovelib-v3:12" to 0.5f), maximum = obj("linovelib-v3:11" to 0.6f, "linovelib-v3:12" to 0.8f))),
        )
        val row = MigrationPlanner.plan(current, old).snapshot.data.rows("userReadingDataEntities").single()
        assertEquals(0.8f, row.text("readingProgress").toFloat(), 0.0001f)
        assertEquals("linovelib-v3:99", row.text("lastReadChapterId"))
        assertEquals("0.1", row["currentChapterReadingProgressMap"]!!.jsonObject["linovelib-v3:11"]!!.jsonPrimitive.content)
        assertEquals(3, row["maxChapterReadingProgressMap"]!!.jsonObject.size)
    }

    @Test
    fun preservesZeroOverallProgressAndMissingCatalogLocation() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID, "userReadingDataEntities" to listOf(reading("1", -1, "", 0f)))
        val old = snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(reading("1", -1, "linovelib-v3:11", 0.7f)))
        val row = MigrationPlanner.plan(current, old).snapshot.data.rows("userReadingDataEntities").single()
        assertEquals("-1", row.text("totalReadTime")); assertEquals("0.0", row.text("readingProgress"))
        assertEquals("linovelib-v3:11", row.text("lastReadChapterId")); assertEquals("chapter-linovelib-v3:11", row.text("lastReadChapterTitle"))
    }

    @Test
    fun ignoresEmptyReadingIdsInBothSourcesWithoutMovingTheirTimeIntoValidStatistics() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "userReadingDataEntities" to listOf(
                reading("1", 120, "linovelib-v3:11", 0.1f, current = obj("linovelib-v3:11" to 0.2f), maximum = obj("linovelib-v3:11" to 0.4f)),
                reading("", 142, "", 0f, timestamp = "2026-10-11T10:27:34.833"),
            ),
            "bookRecordEntities" to listOf(record("1", 2, 100)),
            "dailyCountEntities" to listOf(daily(intArrayOf(30))),
        )
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(
                reading("1", 240, "linovelib-v3:12", 0.7f, current = obj("linovelib-v3:11" to 0.8f), maximum = obj("linovelib-v3:11" to 0.9f)),
                reading("2", 45, "linovelib-v3:21", 0.3f),
                reading("", 109, "", 0f, timestamp = "2026-09-19T12:19:49.862"),
            ),
            "bookRecordEntities" to listOf(record("1", 2, 200)),
            "dailyCountEntities" to listOf(daily(intArrayOf(29))),
        )
        val currentBefore = current.data.toString()
        val twBefore = tw.data.toString()
        MigrationPlanner.validate(current)
        MigrationPlanner.validate(tw)
        val plan = MigrationPlanner.plan(current, tw)
        MigrationPlanner.validate(plan.snapshot)

        assertEquals(2, plan.bookCount)
        val result = plan.snapshot.data
        val readingRows = result.rows("userReadingDataEntities").associateBy { it.text("id") }
        assertEquals(setOf("1", "2"), readingRows.keys)
        val merged = readingRows.getValue("1")
        assertEquals("360", merged.text("totalReadTime"))
        assertEquals("0.1", merged.text("readingProgress"))
        assertEquals("linovelib-v3:11", merged.text("lastReadChapterId"))
        assertEquals("0.2", merged["currentChapterReadingProgressMap"]!!.jsonObject["linovelib-v3:11"]!!.jsonPrimitive.content)
        assertEquals("0.9", merged["maxChapterReadingProgressMap"]!!.jsonObject["linovelib-v3:11"]!!.jsonPrimitive.content)
        assertEquals("45", readingRows.getValue("2").text("totalReadTime"))
        val record = result.rows("bookRecordEntities").single()
        assertEquals("4", record.text("reads"))
        assertEquals("300", record.text("seconds"))
        assertEquals(59, MigrationPlanner.decodeHours(result.rows("dailyCountEntities").single().text("timeCount"))[0])
        assertEquals(currentBefore, current.data.toString())
        assertEquals(twBefore, tw.data.toString())
    }

    @Test
    fun ignoresEntireEmptyIdReadingRowsWithoutCreatingBooksOrStatistics() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("", 142, "", 0f), obj("id" to "")),
        )
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("", 109, "", 0f)),
        )
        MigrationPlanner.validate(current)
        MigrationPlanner.validate(tw)
        val plan = MigrationPlanner.plan(current, tw)
        assertEquals(0, plan.bookCount)
        assertTrue(plan.snapshot.data.rows("userReadingDataEntities").isEmpty())
        assertTrue(plan.snapshot.data.rows("bookRecordEntities").isEmpty())
        assertTrue(plan.snapshot.data.rows("dailyCountEntities").isEmpty())
        assertEquals(MigrationPlanner.personalFingerprint(tw), plan.twFingerprint)
        assertEquals(MigrationPlanner.personalFingerprint(tw),
            MigrationPlanner.personalFingerprint(snapshot(MigrationData.TW_SOURCE_ID)))
    }

    @Test
    fun preservesUnknownCurrentIdsAndSkipsInvalidIncomingIds() {
        listOf(MigrationData.CURRENT_SOURCE_ID, MigrationData.TW_SOURCE_ID).forEach { source ->
            listOf(" ", "0", "book-1").forEach { id ->
                val invalid = snapshot(source, "userReadingDataEntities" to listOf(reading(id, 1, "", 0f)))
                MigrationPlanner.validate(invalid)
                val plan = if (source == MigrationData.CURRENT_SOURCE_ID) {
                    MigrationPlanner.plan(invalid, snapshot(MigrationData.TW_SOURCE_ID))
                } else MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), invalid)
                assertEquals(if (source == MigrationData.CURRENT_SOURCE_ID) invalid.data.rows("userReadingDataEntities") else emptyList<JsonObject>(),
                    plan.snapshot.data.rows("userReadingDataEntities"))
            }
            listOf(obj(), obj("id" to 1)).forEach { row ->
                val invalid = snapshot(source, "userReadingDataEntities" to listOf(row))
                MigrationPlanner.validate(invalid)
                val plan = if (source == MigrationData.CURRENT_SOURCE_ID) MigrationPlanner.plan(invalid, snapshot(MigrationData.TW_SOURCE_ID))
                    else MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), invalid)
                assertEquals(if (source == MigrationData.CURRENT_SOURCE_ID) listOf(row) else emptyList<JsonObject>(),
                    plan.snapshot.data.rows("userReadingDataEntities"))
            }
            MigrationPlanner.validate(snapshot(source, "bookRecordEntities" to listOf(record("", 1, 142))))
        }
    }

    @Test
    fun mapsShelvesByExactNameAllocatesConflictingIdAndRebuildsReverseReferences() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "bookshelfEntities" to listOf(shelf(7, "书架", listOf("1"), auto = true)),
            "bookshelfBookMetadataEntities" to listOf(metadata("1", listOf(7))),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "bookshelfEntities" to listOf(shelf(3, "书架", listOf("1", "2")), shelf(7, "書架", listOf("3"), auto = true)),
            "bookshelfBookMetadataEntities" to listOf(metadata("1", listOf(3)), metadata("2", listOf(3)), metadata("3", listOf(7))),
        )
        val result = MigrationPlanner.plan(current, old).snapshot.data
        val shelves = result.rows("bookshelfEntities")
        assertEquals(2, shelves.size)
        assertEquals(listOf("1", "2"), (shelves[0]["allBookIds"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("true", shelves[0].text("autoCache"))
        assertEquals("書架", shelves[1].text("name")); assertEquals("false", shelves[1].text("autoCache"))
        assertEquals("default", shelves[1].text("sortType")); assertTrue(shelves[1].text("id") != "7")
        val reverse = result.rows("bookshelfBookMetadataEntities").associateBy { it.text("id") }
        assertEquals("[7]", reverse.getValue("2")["bookShelfIds"].toString())
        assertEquals(shelves[1].text("id"), (reverse.getValue("3")["bookShelfIds"] as JsonArray).single().jsonPrimitive.content)
    }

    @Test
    fun mergesRecentListByLastTimeAndKeepsTargetSettingsOnly() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "userDataEntities" to listOf(user("reading_books", "StringList", "1,2"), user("search.history", "StringList", "current-search")),
            "userReadingDataEntities" to listOf(reading("1", 1, "linovelib-v3:11", 0f, timestamp = "2026-10-11T12:00"), reading("2", 1, "linovelib-v3:21", 0f, timestamp = "2026-10-11T12:00")),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "userDataEntities" to listOf(user("reading_books", "StringList", "3,1"), user("search.history", "StringList", "old-search"), user("old.preference", "Boolean", "true")),
            "userReadingDataEntities" to listOf(reading("3", 1, "linovelib-v3:31", 0f, timestamp = "2026-10-11T13:00")),
            "bookInformationEntities" to listOf(info("4", "cache only")),
        )
        val rows = MigrationPlanner.plan(current, old).snapshot.data.rows("userDataEntities").associateBy { it.text("path") }
        val storedRecent = rows.getValue("reading_books").text("value").split(',')
        assertEquals(listOf("1", "2", "3"), storedRecent)
        // ReadingHomeViewModel reverses the stored list; the newest book is first.
        assertEquals(listOf("3", "2", "1"), storedRecent.reversed())
        assertEquals("current-search", rows.getValue("search.history").text("value"))
        assertFalse("old.preference" in rows)
    }

    @Test
    fun preservesRealTwRecentOrderSoHostContinueReadingUsesLatestBook() {
        // Minimal reproduction from an API 3 session: native TW list is oldest -> newest.
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "userDataEntities" to listOf(user("reading_books", "StringList", "4612,3348")),
            "userReadingDataEntities" to listOf(
                reading("4612", 45, "linovelib-v3:269993", 0f, timestamp = "2026-10-12T18:40:28.442"),
                reading("3348", -1, "linovelib-v3:169802", 0f, timestamp = "2026-10-12T18:42:54.326"),
            ),
        )
        val result = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw).snapshot.data
        val storedRecent = result.rows("userDataEntities").single().text("value").split(',')
        assertEquals(listOf("4612", "3348"), storedRecent)
        assertEquals(listOf("3348", "4612"), storedRecent.reversed())
        val readingRows = result.rows("userReadingDataEntities").associateBy { it.text("id") }
        assertEquals("45", readingRows.getValue("4612").text("totalReadTime"))
        assertEquals("-1", readingRows.getValue("3348").text("totalReadTime"))
    }

    @Test
    fun completionRecordsAreTypeAwareAndRequireCompleteSelectedCatalog() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "userDataEntities" to listOf(user("completedDownloadBookList", "CompletedDownloadItemList", "EPUB_EXPORT|1, CACHE|1, CACHE|2")),
        )
        val old = catalogTw().let { snapshot -> MigrationSnapshot(snapshot.sourceId, JsonObject(snapshot.data.toMutableMap().apply {
            put("userDataEntities", JsonArray(listOf(user("completedDownloadBookList", "CompletedDownloadItemList", "CACHE|1, EPUB_EXPORT|3"))))
        })) }
        val row = MigrationPlanner.plan(current, old).snapshot.data.rows("userDataEntities").single()
        assertEquals("EPUB_EXPORT|1, CACHE|1", row.text("value"))
    }

    @Test
    fun doesNotCreateDownloadHistoryFromBodiesAlone() {
        val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), catalogTw()).snapshot.data
        assertTrue(result.rows("userDataEntities").isEmpty())
    }

    @Test
    fun fingerprintsPersonalDataIndependentlyOfCachesAndTableOrdering() {
        val a = snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(record("1", 1, 10), record("2", 2, 20)))
        val b = snapshot(MigrationData.TW_SOURCE_ID,
            "bookRecordEntities" to listOf(record("2", 2, 20), record("1", 1, 10)),
            "bookInformationEntities" to listOf(info("1", "different cache")),
        )
        assertEquals(MigrationPlanner.personalFingerprint(a), MigrationPlanner.personalFingerprint(b))
    }

    @Test
    fun fingerprintsIgnoreOnlyEmptyIdReadingRows() {
        val valid = reading("1", 45, "linovelib-v3:11", 0.3f)
        val clean = snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(valid))
        val withEmptyIds = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("", 142, "", 0f), valid, obj("id" to "")),
        )
        assertEquals(MigrationPlanner.personalFingerprint(clean), MigrationPlanner.personalFingerprint(withEmptyIds))
        val withNonemptyInvalidId = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(valid, reading(" ", 142, "", 0f)),
        )
        assertFalse(MigrationPlanner.personalFingerprint(clean) == MigrationPlanner.personalFingerprint(withNonemptyInvalidId))
        assertEquals(1, MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), withNonemptyInvalidId)
            .snapshot.data.rows("userReadingDataEntities").size)
    }

    @Test
    fun retainsCurrentOverflowingCounterAndInputsRemainUnchanged() {
        val a = snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(record("1", Int.MAX_VALUE, 10)))
        val b = snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(record("1", 1, 10)))
        val before = a.data.toString()
        val row = MigrationPlanner.plan(a, b).snapshot.data.rows("bookRecordEntities").single()
        assertEquals(Int.MAX_VALUE.toString(), row.text("reads"))
        assertEquals("20", row.text("seconds"))
        assertEquals(before, a.data.toString())
    }

    @Test
    fun skipsInvalidCountersWhileRejectingUnknownIdentity() {
        rejects { MigrationPlanner.normalizeChapterId("linovelib-v4:11") }
        rejects { MigrationPlanner.normalizeChapterId("book/1/chapter11.html") }
        rejects { MigrationPlanner.decodeHours("////////////////////////") }
        assertTrue(MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), snapshot(MigrationData.TW_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 1, -1)))).snapshot.data.rows("bookRecordEntities").isEmpty())
        rejects { MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), snapshot(MigrationData.TW_SOURCE_ID,
            "userDataEntities" to listOf(user(MigrationData.SELECTED_SOURCE, "Int", MigrationData.CURRENT_SOURCE_ID.toString())))) }
    }

    @Test
    fun preservesStatisticsOnlyInputAndSupportsEmptyTarget() {
        val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), snapshot(MigrationData.TW_SOURCE_ID,
            "dailyCountEntities" to listOf(daily(intArrayOf(10))))).snapshot.data
        assertEquals(1, result.rows("dailyCountEntities").size)
        assertTrue(result.rows("bookshelfEntities").isEmpty()); assertTrue(result.rows("userReadingDataEntities").isEmpty())
    }

    @Test
    fun acceptsHostBufferedRecordWhoseLastSeenPrecedesCreationTimestamp() {
        val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), snapshot(MigrationData.TW_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 1, 60, "12:01", "12:00")))).snapshot.data
        assertEquals("12:01", result.rows("bookRecordEntities").single().text("firstSeen"))
        assertEquals("12:00", result.rows("bookRecordEntities").single().text("lastSeen"))
    }

    @Test
    fun countEncodingMatchesOfficialBigEndianFixture() {
        val zero = IntArray(24)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAA", MigrationPlanner.encodeHours(zero))
        val firstHourOne = zero.copyOf().apply { this[0] = 1 }
        assertEquals("BAAAAAAAAAAAAAAAAAAAAAAA", MigrationPlanner.encodeHours(firstHourOne))
        val all = IntArray(24) { it * 2 }
        assertEquals(all.toList(), MigrationPlanner.decodeHours(MigrationPlanner.encodeHours(all)).toList())
    }

    @Test
    fun acceptsOfficialHostGoldenSnapshotIncludingNullableWordCountUnits() {
        val source = javaClass.getResource("/migration/tw-snapshot.json")!!.readText()
        val tw = MigrationSnapshot(MigrationData.TW_SOURCE_ID, Json.parseToJsonElement(source).jsonObject)
        val result = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID), tw).snapshot.data
        assertEquals(2, result.rows("bookInformationEntities").size)
        assertEquals(JsonNull, result.rows("bookInformationEntities")[0]["wordCount"]!!.jsonObject["unit"])
        assertEquals(45, MigrationPlanner.decodeHours(tw.data.rows("dailyCountEntities").single().text("timeCount"))[10])
    }

    @Test
    fun skipsInvalidChapterProgressItemsWhileKeepingValidReadingFields() {
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(
                reading("1", 45, "linovelib-v3:11", 0.4f,
                    current = obj("linovelib-v3:11" to 1.3f, "linovelib-v3:12" to -0.2f, "bad-key" to 0.6f,
                        "linovelib-v3:13" to "NaN", "linovelib-v3:14" to 0f, "linovelib-v3:15" to 1f),
                    maximum = obj("linovelib-v3:11" to 0.8f, "linovelib-v3:12" to 1.4f)),
                reading("bad-book", 100, "linovelib-v3:11", 0.3f),
                reading("2", 45, "linovelib-v3:21", 0.3f),
            ),
        )
        val plan = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
        val rows = plan.snapshot.data.rows("userReadingDataEntities").associateBy { it.text("id") }
        assertEquals(setOf("1", "2"), rows.keys)
        val recovered = rows.getValue("1")
        assertEquals("linovelib-v3:11", recovered.text("lastReadChapterId"))
        assertEquals("2026-10-11T12:00", recovered.text("lastReadTime"))
        assertEquals("45", recovered.text("totalReadTime"))
        assertEquals("0.4", recovered.text("readingProgress"))
        assertEquals(obj("linovelib-v3:14" to 0f, "linovelib-v3:15" to 1f), recovered["currentChapterReadingProgressMap"])
        assertEquals(obj("linovelib-v3:11" to 0.8f), recovered["maxChapterReadingProgressMap"])
        assertTrue(plan.hasRecoverableData)
    }

    @Test
    fun preservesUnknownCurrentPersonalRowsAndBlocksOnlyTheSameIncomingKeys() {
        val opaqueReading = reading("1", 10, "unknown:chapter", 3f, timestamp = "unknown-time")
        val opaqueRecord = record("1", 2, -100)
        val opaqueDaily = obj("date" to "2026-10-11", "timeCount" to "unknown-count")
        val opaqueRecent = user(MigrationData.READING_BOOKS, "UnknownList", "future-format")
        val customPreference = user("custom.setting", "UnknownType", "keep-me")
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "userReadingDataEntities" to listOf(opaqueReading, reading("legacy-personal-x", 20, "linovelib-v3:11", 0f)),
            "bookRecordEntities" to listOf(opaqueRecord),
            "dailyCountEntities" to listOf(opaqueDaily),
            "userDataEntities" to listOf(opaqueRecent, customPreference),
        )
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("1", 100, "linovelib-v3:11", 0.2f), reading("2", 45, "linovelib-v3:21", 0.3f)),
            "bookRecordEntities" to listOf(record("1", 3, 200), record("2", 1, 30)),
            "dailyCountEntities" to listOf(daily(intArrayOf(30))),
            "userDataEntities" to listOf(user(MigrationData.READING_BOOKS, "StringList", "1,2")),
        )
        val result = MigrationPlanner.plan(current, tw).snapshot.data
        val readings = result.rows("userReadingDataEntities").associateBy { it.text("id") }
        assertEquals(opaqueReading, readings.getValue("1"))
        assertEquals(current.data.rows("userReadingDataEntities")[1], readings.getValue("legacy-personal-x"))
        assertEquals("45", readings.getValue("2").text("totalReadTime"))
        assertEquals(opaqueRecord, result.rows("bookRecordEntities").first { it.text("bookId") == "1" })
        assertEquals("30", result.rows("bookRecordEntities").first { it.text("bookId") == "2" }.text("seconds"))
        assertEquals(listOf(opaqueDaily), result.rows("dailyCountEntities"))
        assertEquals(opaqueRecent, result.rows("userDataEntities").first { it.text("path") == MigrationData.READING_BOOKS })
        assertEquals(customPreference, result.rows("userDataEntities").first { it.text("path") == "custom.setting" })
    }

    @Test
    fun preservesUnknownCurrentFieldValuesWithoutInterpretingThem() {
        val unknown = reading("1", 30, "linovelib-v3:11", 0f).let {
            JsonObject(it.toMutableMap().apply { put("lastReadTime", JsonNull) })
        }
        val result = MigrationPlanner.plan(
            snapshot(MigrationData.CURRENT_SOURCE_ID, "userReadingDataEntities" to listOf(unknown)),
            snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(reading("1", 200, "linovelib-v3:12", 0.9f))),
        ).snapshot.data
        assertEquals(listOf(unknown), result.rows("userReadingDataEntities"))
    }

    @Test
    fun retainsCurrentCounterOnOverflowWhileMergingOtherSafeFields() {
        val plan = MigrationPlanner.plan(
            snapshot(MigrationData.CURRENT_SOURCE_ID,
                "bookRecordEntities" to listOf(record("1", Int.MAX_VALUE, 100)),
                "userReadingDataEntities" to listOf(reading("1", Int.MAX_VALUE, "linovelib-v3:11", 0f)),
            ),
            snapshot(MigrationData.TW_SOURCE_ID,
                "bookRecordEntities" to listOf(record("1", 1, 200)),
                "userReadingDataEntities" to listOf(reading("1", 50, "linovelib-v3:12", 0.3f, maximum = obj("linovelib-v3:12" to 0.5f))),
            ),
        )
        val record = plan.snapshot.data.rows("bookRecordEntities").single()
        assertEquals(Int.MAX_VALUE.toString(), record.text("reads"))
        assertEquals("300", record.text("seconds"))
        val reading = plan.snapshot.data.rows("userReadingDataEntities").single()
        assertEquals(Int.MAX_VALUE.toString(), reading.text("totalReadTime"))
        assertEquals("linovelib-v3:11", reading.text("lastReadChapterId"))
        assertEquals(obj("linovelib-v3:12" to 0.5f), reading["maxChapterReadingProgressMap"])
    }

    @Test
    fun skipsTwHistoryWithBadCountersOrBooleansWithoutChangingCurrentHistory() {
        val valid = record("1", 3, 200, "10:00", "14:00", finished = true, favorite = true)
        val invalidRows = buildList {
            listOf("reads", "seconds").forEach { field ->
                listOf(JsonPrimitive(-1), JsonPrimitive("3"), JsonNull).forEach { value ->
                    add(JsonObject(valid + (field to value)))
                }
                add(JsonObject(valid - field))
            }
            listOf("isFinished", "isFavorited").forEach { field ->
                listOf(JsonPrimitive("false"), JsonPrimitive(0), JsonNull).forEach { value ->
                    add(JsonObject(valid + (field to value)))
                }
            }
        }
        val currentRow = record("1", 2, 100, "12:00", "13:00")
        val validOther = record("2", 1, 45)
        invalidRows.forEach { invalid ->
            val tw = snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(invalid))
            val emptyTarget = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
            assertTrue("Invalid history must be skipped: $invalid", emptyTarget.snapshot.data.rows("bookRecordEntities").isEmpty())
            assertFalse(emptyTarget.hasRecoverableData)
            val current = snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(currentRow))
            val withCurrent = MigrationPlanner.plan(current, tw)
            assertEquals(listOf(currentRow), withCurrent.snapshot.data.rows("bookRecordEntities"))
            assertFalse(withCurrent.hasRecoverableData)
            val mixed = MigrationPlanner.plan(current,
                snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(invalid, validOther)))
            assertEquals(listOf(currentRow, validOther), mixed.snapshot.data.rows("bookRecordEntities"))
            assertTrue(mixed.hasRecoverableData)
        }
    }

    @Test
    fun skipsTwReadingWithBadRequiredFieldsWithoutChangingCurrentStateOrValidSiblings() {
        val valid = reading("1", 100, "linovelib-v3:12", 0.7f,
            current = obj("linovelib-v3:12" to 0.7f), maximum = obj("linovelib-v3:12" to 0.9f))
        val invalidFields = mapOf(
            "lastReadTime" to listOf(JsonPrimitive("unknown-time"), JsonPrimitive(12), JsonNull),
            "lastReadChapterId" to listOf(JsonPrimitive("unknown:chapter"), JsonPrimitive(12), JsonNull),
            "lastReadChapterTitle" to listOf(JsonPrimitive(12), JsonNull),
            "totalReadTime" to listOf(JsonPrimitive(-9), JsonPrimitive("100"), JsonNull),
            "currentChapterReadingProgressMap" to listOf(JsonPrimitive("{}"), JsonArray(emptyList()), JsonNull),
            "maxChapterReadingProgressMap" to listOf(JsonPrimitive("{}"), JsonArray(emptyList()), JsonNull),
        )
        val invalidRows = invalidFields.flatMap { (field, values) ->
            values.map { JsonObject(valid + (field to it)) } + JsonObject(valid - field)
        }
        val currentRow = reading("1", 45, "linovelib-v3:11", 0.3f)
        val validOther = reading("2", 30, "linovelib-v3:21", 0.4f)
        invalidRows.forEach { invalid ->
            val tw = snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(invalid))
            val emptyTarget = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
            assertTrue("Invalid reading state must be skipped: $invalid", emptyTarget.snapshot.data.rows("userReadingDataEntities").isEmpty())
            assertFalse(emptyTarget.hasRecoverableData)
            val current = snapshot(MigrationData.CURRENT_SOURCE_ID, "userReadingDataEntities" to listOf(currentRow))
            val withCurrent = MigrationPlanner.plan(current, tw)
            assertEquals(listOf(currentRow), withCurrent.snapshot.data.rows("userReadingDataEntities"))
            assertFalse(withCurrent.hasRecoverableData)
            val mixed = MigrationPlanner.plan(current,
                snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(invalid, validOther)))
            assertEquals(listOf(currentRow, validOther), mixed.snapshot.data.rows("userReadingDataEntities"))
            assertTrue(mixed.hasRecoverableData)
        }
    }

    @Test
    fun skipsTwRecordsWithInvalidOrMissingTimesWithoutChangingCurrentHistory() {
        val valid = record("1", 3, 200, "10:00", "14:00", finished = true)
        val invalidRows = listOf(
            record("1", 3, 200, "bad", "14:00"),
            record("1", 3, 200, "10:00", "bad"),
            record("1", 3, 200, "bad", "bad"),
            JsonObject(valid - "firstSeen"),
            JsonObject(valid - "lastSeen"),
            JsonObject(valid + ("firstSeen" to JsonNull)),
            JsonObject(valid + ("lastSeen" to JsonPrimitive(12))),
        )
        val currentRow = record("1", 2, 100, "12:00", "13:00")
        invalidRows.forEach { invalid ->
            val tw = snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(invalid))
            val emptyTarget = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
            assertTrue(emptyTarget.snapshot.data.rows("bookRecordEntities").isEmpty())
            assertFalse(emptyTarget.hasRecoverableData)
            val current = snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(currentRow))
            val withCurrent = MigrationPlanner.plan(current, tw)
            assertEquals(listOf(currentRow), withCurrent.snapshot.data.rows("bookRecordEntities"))
            assertFalse(withCurrent.hasRecoverableData)
            val validOther = record("2", 1, 45)
            val mixed = MigrationPlanner.plan(current,
                snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(invalid, validOther)))
            assertEquals(listOf(currentRow, validOther), mixed.snapshot.data.rows("bookRecordEntities"))
            assertTrue(mixed.hasRecoverableData)
        }
    }

    @Test
    fun preservesCurrentBadTimesAndBlocksValidTwHistoryForTheSameKey() {
        val current = record("1", 2, 100, "bad", "13:00")
        val result = MigrationPlanner.plan(
            snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(current)),
            snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(record("1", 3, 200))),
        )
        assertEquals(listOf(current), result.snapshot.data.rows("bookRecordEntities"))
        assertFalse(result.hasRecoverableData)
    }

    @Test
    fun rejectsInvalidTwOverallProgressInsteadOfClampingOrReplacingItWithZero() {
        val currentRow = reading("1", 45, "linovelib-v3:11", 0.3f)
        val valid = reading("1", 100, "linovelib-v3:12", 0.7f)
        val invalidValues = listOf(JsonPrimitive(-0.2f), JsonPrimitive(1.2f), JsonNull, JsonPrimitive("0.7"))
        val invalidRows = invalidValues.map { JsonObject(valid + ("readingProgress" to it)) } +
            JsonObject(valid - "readingProgress")
        invalidRows.forEach { invalid ->
            val tw = snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(invalid))
            val emptyTarget = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
            assertTrue(emptyTarget.snapshot.data.rows("userReadingDataEntities").isEmpty())
            assertFalse(emptyTarget.hasRecoverableData)
            val withCurrent = MigrationPlanner.plan(
                snapshot(MigrationData.CURRENT_SOURCE_ID, "userReadingDataEntities" to listOf(currentRow)), tw)
            assertEquals(listOf(currentRow), withCurrent.snapshot.data.rows("userReadingDataEntities"))
            assertFalse(withCurrent.hasRecoverableData)
        }
    }

    @Test
    fun preservesLegalDefaultsAndRealProgressEndpoints() {
        val rawRecord = JsonObject(record("1", 1, 10) - setOf("isFinished", "isFavorited"))
        val rawShelf = JsonObject(shelf(1, "已收藏", listOf("1")) - "sortReversed")
        val plan = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID),
            snapshot(MigrationData.TW_SOURCE_ID,
                "bookRecordEntities" to listOf(rawRecord), "bookshelfEntities" to listOf(rawShelf),
                "userReadingDataEntities" to listOf(
                    JsonObject(reading("1", -1, "", 0f, timestamp = java.time.LocalDateTime.MIN.toString()) +
                        ("lastReadChapterTitle" to JsonPrimitive(""))), reading("2", -1, "", 1f))))
        val result = plan.snapshot.data
        val restoredRecord = result.rows("bookRecordEntities").single()
        assertEquals("false", restoredRecord.text("isFinished"))
        assertEquals("false", restoredRecord.text("isFavorited"))
        assertEquals("false", result.rows("bookshelfEntities").single().text("sortReversed"))
        assertEquals(listOf("0.0", "1.0"), result.rows("userReadingDataEntities").map { it.text("readingProgress") })
        assertEquals(listOf("-1", "-1"), result.rows("userReadingDataEntities").map { it.text("totalReadTime") })
        val sentinel = result.rows("userReadingDataEntities").first()
        assertEquals(java.time.LocalDateTime.MIN.toString(), sentinel.text("lastReadTime"))
        assertEquals("", sentinel.text("lastReadChapterId"))
        assertEquals("", sentinel.text("lastReadChapterTitle"))
        assertEquals(obj(), sentinel["currentChapterReadingProgressMap"])
        assertEquals(obj(), sentinel["maxChapterReadingProgressMap"])
    }

    @Test
    fun boundsComputedHostOverallProgressWhenHistoricalChaptersExceedTheCurrentCatalog() {
        val tw = catalogTw().let { it.copy(data = JsonObject(it.data + ("userReadingDataEntities" to JsonArray(listOf(
            reading("1", 45, "linovelib-v3:11", 0.8f,
                maximum = obj("linovelib-v3:11" to 1f, "linovelib-v3:99" to 1f)),
        ))))) }
        val row = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
            .snapshot.data.rows("userReadingDataEntities").single()
        assertEquals("1.0", row.text("readingProgress"))
        assertEquals(obj("linovelib-v3:11" to 1f, "linovelib-v3:99" to 1f), row["maxChapterReadingProgressMap"])
    }

    @Test
    fun includesRealMidnightInRecordTimeMerging() {
        val result = MigrationPlanner.plan(
            snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(record("1", 2, 100, "12:00", "13:00"))),
            snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(record("1", 1, 20, "00:00", "14:00"))),
        ).snapshot.data.rows("bookRecordEntities").single()
        assertEquals("00:00", result.text("firstSeen"))
        assertEquals("14:00", result.text("lastSeen"))
    }

    @Test
    fun isolatesIncomingDatesOutsideTheHostSqlRangeAndPreservesCurrentRows() {
        listOf("0001-01-01", "+10000-01-01").forEach { invalidDate ->
            val currentRecord = JsonObject(record("4", 2, 100).toMutableMap().apply { put("date", JsonPrimitive(invalidDate)) })
            val currentDaily = JsonObject(daily(intArrayOf(20)).toMutableMap().apply { put("date", JsonPrimitive(invalidDate)) })
            val incomingRecord = JsonObject(record("1", 3, 200).toMutableMap().apply { put("date", JsonPrimitive(invalidDate)) })
            val incomingDaily = JsonObject(daily(intArrayOf(30)).toMutableMap().apply { put("date", JsonPrimitive(invalidDate)) })
            val result = MigrationPlanner.plan(
                snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(currentRecord),
                    "dailyCountEntities" to listOf(currentDaily)),
                snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(incomingRecord, record("2", 1, 45)),
                    "dailyCountEntities" to listOf(incomingDaily, daily(intArrayOf(10))),
                    "userReadingDataEntities" to listOf(reading("2", 45, "linovelib-v3:21", 0.3f))),
            ).snapshot.data
            val records = result.rows("bookRecordEntities").associateBy { it.text("bookId") }
            assertEquals(setOf("2", "4"), records.keys)
            assertEquals(currentRecord, records.getValue("4"))
            assertEquals("45", records.getValue("2").text("seconds"))
            val dailyRows = result.rows("dailyCountEntities")
            assertEquals(2, dailyRows.size)
            assertTrue(currentDaily in dailyRows)
            assertEquals("linovelib-v3:21", result.rows("userReadingDataEntities").single().text("lastReadChapterId"))
        }
    }

    @Test
    fun protectsUnknownCurrentShelvesFromIncomingIdAndNameCollisions() {
        val opaqueShelf = shelf(7, "protected", listOf("1")).let {
            JsonObject(it.toMutableMap().apply { put("autoCache", JsonNull) })
        }
        val result = MigrationPlanner.plan(
            snapshot(MigrationData.CURRENT_SOURCE_ID, "bookshelfEntities" to listOf(opaqueShelf)),
            snapshot(MigrationData.TW_SOURCE_ID, "bookshelfEntities" to listOf(
                shelf(7, "same-id", listOf("2")), shelf(8, "protected", listOf("3")), shelf(9, "allowed", listOf("4")))),
        ).snapshot.data
        val shelves = result.rows("bookshelfEntities")
        assertEquals(opaqueShelf, shelves.first { it.text("id") == "7" })
        assertEquals(setOf("protected", "allowed"), shelves.map { it.text("name") }.toSet())
        assertEquals(listOf("4"), (shelves.first { it.text("name") == "allowed" }["allBookIds"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun isolatesBrokenTwCatalogByWholeBookAndKeepsUnrelatedBodiesAndCatalogs() {
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "volumeEntities" to listOf(volume("1", "partial-1", listOf("linovelib-v3:11")),
                volume("1", "broken-1", listOf("missing-chapter")), volume("2", "valid-2", listOf("linovelib-v3:21"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "valid standalone"), chapter("linovelib-v3:21", "valid catalog")),
            "chapterContentEntities" to listOf(body("linovelib-v3:11", "standalone content"),
                obj("id" to "linovelib-v3:12", "title" to "bad body", "content" to "{", "lastChapter" to "", "nextChapter" to ""), body("linovelib-v3:21", "valid content")),
        )
        val plan = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw)
        assertEquals(listOf("valid-2"), plan.snapshot.data.rows("volumeEntities").map { it.text("volumeId") })
        assertEquals(setOf("linovelib-v3:11", "linovelib-v3:21"), plan.snapshot.data.rows("chapterContentEntities").map { it.text("id") }.toSet())
        assertTrue(plan.hasRecoverableData)
    }

    @Test
    fun isolatesTwCatalogThatConflictsWithACurrentChapterOwner() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "volumeEntities" to listOf(volume("1", "current-1", listOf("linovelib-v3:11"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "current")),
        )
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "volumeEntities" to listOf(volume("2", "conflict-2", listOf("linovelib-v3:11")), volume("3", "valid-3", listOf("linovelib-v3:31"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "tw conflict"), chapter("linovelib-v3:31", "tw valid")),
        )
        val result = MigrationPlanner.plan(current, tw).snapshot.data
        assertEquals(listOf("current-1", "valid-3"), result.rows("volumeEntities").map { it.text("volumeId") })
    }

    @Test
    fun isolatesMalformedBodyComponentsAndFallsBackToTheOtherSource() {
        listOf<JsonElement>(JsonPrimitive("broken"), obj("id" to "simple_text", "data" to obj()),
            obj("id" to "image", "data" to obj("uri" to 1)), obj("id" to "simple_text", "data" to JsonNull)).forEach { invalid ->
            fun broken(id: String) = JsonObject(body(id, "unused").toMutableMap().apply {
                put("content", JsonPrimitive(obj("components" to JsonArray(listOf(invalid))).toString()))
            })
            val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
                "volumeEntities" to listOf(volume("1", "current", listOf("linovelib-v3:11", "linovelib-v3:12"))),
                "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "first"), chapter("linovelib-v3:12", "second")),
                "chapterContentEntities" to listOf(broken("linovelib-v3:11"), body("linovelib-v3:12", "current good")),
            )
            val tw = snapshot(MigrationData.TW_SOURCE_ID,
                "chapterContentEntities" to listOf(body("linovelib-v3:11", "TW good"), broken("linovelib-v3:12")),
                "userDataEntities" to listOf(user(MigrationData.COMPLETED_DOWNLOADS, "CompletedDownloadItemList", "CACHE|1")),
            )
            val result = MigrationPlanner.plan(current, tw).snapshot.data
            val bodies = result.rows("chapterContentEntities").associateBy { it.text("id") }
            assertTrue(bodies.getValue("linovelib-v3:11").text("content").contains("TW good"))
            assertTrue(bodies.getValue("linovelib-v3:12").text("content").contains("current good"))
            assertEquals("CACHE|1", result.rows("userDataEntities").single().text("value"))
            val brokenOnly = snapshot(MigrationData.TW_SOURCE_ID,
                "volumeEntities" to listOf(volume("1", "tw", listOf("linovelib-v3:11"))),
                "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "first")),
                "chapterContentEntities" to listOf(broken("linovelib-v3:11")),
                "userDataEntities" to listOf(user(MigrationData.COMPLETED_DOWNLOADS, "CompletedDownloadItemList", "CACHE|1")),
            )
            val withoutFallback = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), brokenOnly).snapshot.data
            assertTrue(withoutFallback.rows("chapterContentEntities").isEmpty())
            assertEquals("", withoutFallback.rows("userDataEntities").single().text("value"))
        }
    }

    @Test
    fun keepsUnknownWellFormedBodyComponentsForTheHostErrorRenderer() {
        val unknown = JsonObject(body("linovelib-v3:11", "unused").toMutableMap().apply {
            put("content", JsonPrimitive(obj("components" to listOf(obj("id" to "future_component", "data" to obj()))).toString()))
        })
        val plan = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID),
            snapshot(MigrationData.TW_SOURCE_ID, "chapterContentEntities" to listOf(unknown)))
        assertEquals(unknown.text("content"), plan.snapshot.data.rows("chapterContentEntities").single().text("content"))
        assertTrue(plan.hasRecoverableData)
    }

    @Test
    fun repairsUnsupportedKnownComponentDataFieldsWithoutLosingTextOrImages() {
        val unknownData = obj("future" to "keep", "extra" to 1)
        val rawContent = obj("components" to listOf(
            obj("id" to "simple_text", "data" to obj("text" to "kept text", "id" to "simple_text", "extra" to 1)),
            obj("id" to "image", "data" to obj("uri" to "file:/old/image.png", "id" to 123, "extra" to true)),
            obj("id" to "future_component", "data" to unknownData),
        )).toString()
        val rawBody = JsonObject(body("linovelib-v3:11", "unused").toMutableMap().apply { put("content", JsonPrimitive(rawContent)) })
        val plan = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID),
            snapshot(MigrationData.TW_SOURCE_ID, "chapterContentEntities" to listOf(rawBody)))
        val recoveredContent = plan.snapshot.data.rows("chapterContentEntities").single().text("content")
        val components = Json.parseToJsonElement(recoveredContent).jsonObject["components"] as JsonArray
        assertEquals(obj("text" to "kept text", "id" to "simple_text"), components[0].jsonObject["data"])
        assertEquals(obj("uri" to "file:/old/image.png"), components[1].jsonObject["data"])
        assertEquals(unknownData, components[2].jsonObject["data"])
        assertFalse(rawContent == recoveredContent)
        assertTrue(plan.hasRecoverableData)
    }

    @Test
    fun keepsOriginalContentBytesWhenComponentDataAlreadyMatchesTheHostSchema() {
        val rawContent = "{ \"components\" : [ {\"id\":\"simple_text\", \"data\":{\"id\":\"simple_text\",\"text\":\"kept text\"}}, " +
            "{\"id\":\"image\",\"data\":{\"uri\":\"file:/old/image.png\",\"id\":\"image\"}} ] }"
        val rawBody = JsonObject(body("linovelib-v3:11", "unused").toMutableMap().apply { put("content", JsonPrimitive(rawContent)) })
        val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID),
            snapshot(MigrationData.TW_SOURCE_ID, "chapterContentEntities" to listOf(rawBody))).snapshot.data
        assertEquals(rawContent, result.rows("chapterContentEntities").single().text("content"))
    }

    @Test
    fun onlyRecoverableTwInputCanCompleteMigrationIncludingAlreadyPresentData() {
        val noContent = snapshot(MigrationData.TW_SOURCE_ID,
            "bookshelfEntities" to listOf(shelf(1, "已收藏", emptyList())),
            "userReadingDataEntities" to listOf(reading("", 142, "", 0f), reading("bad-book", 10, "linovelib-v3:11", 0f)),
            "userDataEntities" to listOf(user(MigrationData.SELECTED_SOURCE, "Int", MigrationData.TW_SOURCE_ID.toString()),
                user("theme", "String", "dark")),
        )
        assertFalse(MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), noContent).hasRecoverableData)
        listOf(
            snapshot(MigrationData.TW_SOURCE_ID, "dailyCountEntities" to listOf(daily(intArrayOf(10)))),
            snapshot(MigrationData.TW_SOURCE_ID, "userDataEntities" to listOf(user(MigrationData.READING_BOOKS, "StringList", "1"))),
            snapshot(MigrationData.TW_SOURCE_ID, "bookshelfEntities" to listOf(shelf(1, "已收藏", listOf("1")))),
            catalogTw(),
        ).forEach { tw -> assertTrue(MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw).hasRecoverableData) }
        val known = snapshot(MigrationData.CURRENT_SOURCE_ID, "chapterContentEntities" to listOf(body("linovelib-v3:11", "same body")))
        val tw = snapshot(MigrationData.TW_SOURCE_ID, "chapterContentEntities" to listOf(body("linovelib-v3:11", "same body")))
        assertTrue(MigrationPlanner.plan(known, tw).hasRecoverableData)
    }

    @Test
    fun doesNotCompleteWhenEveryIncomingPersonalRowIsBlockedByUnknownCurrentData() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("1", 30, "unknown:chapter", 0f)),
        )
        val tw = snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(reading("1", 45, "linovelib-v3:11", 0.3f)))
        val plan = MigrationPlanner.plan(current, tw)
        assertTrue(MigrationPlanner.hasRecoverableData(tw))
        assertFalse(plan.hasRecoverableData)
        assertEquals(current.data.rows("userReadingDataEntities"), plan.snapshot.data.rows("userReadingDataEntities"))
    }

    @Test
    fun doesNotCompleteForAnEmptyReadingSessionOrUnavailableCacheCompletion() {
        val emptyReading = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(obj("id" to "1", "totalReadTime" to 0)),
        )
        assertFalse(MigrationPlanner.hasRecoverableData(emptyReading))
        assertFalse(MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), emptyReading).hasRecoverableData)
        val completed = snapshot(MigrationData.TW_SOURCE_ID,
            "userDataEntities" to listOf(user(MigrationData.COMPLETED_DOWNLOADS, "CompletedDownloadItemList", "CACHE|1")),
        )
        val unavailable = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), completed)
        assertFalse(unavailable.hasRecoverableData)
        assertEquals("", unavailable.snapshot.data.rows("userDataEntities").single().text("value"))
        val currentCache = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "volumeEntities" to listOf(volume("1", "v", listOf("linovelib-v3:11"))),
            "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "cached")),
            "chapterContentEntities" to listOf(body("linovelib-v3:11", "cached body")),
        )
        assertTrue(MigrationPlanner.plan(currentCache, completed).hasRecoverableData)
    }

    @Test
    fun repairsIncomingKnownListsAndShelfReferencesWithoutAddingUnknownMembers() {
        val tw = snapshot(MigrationData.TW_SOURCE_ID,
            "bookshelfEntities" to listOf(shelf(7, "已收藏", listOf("1", "", "1", "bad-id", "2")).let {
                JsonObject(it.toMutableMap().apply {
                    put("pinnedBookIds", JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("2"), JsonPrimitive("3"))))
                    put("updatedBookIds", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("3"))))
                })
            }),
            "bookshelfBookMetadataEntities" to listOf(metadata("1", listOf(999)).let {
                JsonObject(it.toMutableMap().apply { put("lastUpdate", JsonPrimitive("bad-time")) })
            }),
            "userDataEntities" to listOf(user(MigrationData.READING_BOOKS, "StringList", "1, ,1,bad-id,2")),
        )
        val result = MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), tw).snapshot.data
        val shelf = result.rows("bookshelfEntities").single()
        assertEquals("[\"1\",\"2\"]", shelf["allBookIds"].toString())
        assertEquals("[\"2\"]", shelf["pinnedBookIds"].toString())
        assertEquals("[\"1\"]", shelf["updatedBookIds"].toString())
        val meta = result.rows("bookshelfBookMetadataEntities").associateBy { it.text("id") }
        assertEquals("[7]", meta.getValue("1")["bookShelfIds"].toString())
        assertEquals("[7]", meta.getValue("2")["bookShelfIds"].toString())
        assertEquals(java.time.LocalDateTime.MIN.toString(), meta.getValue("1").text("lastUpdate"))
        assertEquals("1,2", result.rows("userDataEntities").single().text("value"))
    }

    @Test
    fun fingerprintsUnknownRawPersonalEvidenceWithoutParsingItsFields() {
        val unknownReading = obj("unknown" to "first")
        val unknownPreference = obj("type" to "future", "value" to "raw")
        val a = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(unknownReading), "userDataEntities" to listOf(unknownPreference),
        )
        val b = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(obj("unknown" to "second")), "userDataEntities" to listOf(unknownPreference),
        )
        assertFalse(MigrationPlanner.personalFingerprint(a) == MigrationPlanner.personalFingerprint(b))
        val reordered = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(JsonObject(unknownReading.toSortedMap(reverseOrder()))),
            "userDataEntities" to listOf(JsonObject(unknownPreference.toSortedMap(reverseOrder()))),
        )
        assertEquals(MigrationPlanner.personalFingerprint(a), MigrationPlanner.personalFingerprint(reordered))
    }

    private fun catalogTw() = snapshot(MigrationData.TW_SOURCE_ID,
        "volumeEntities" to listOf(volume("1", "tw-volume", listOf("linovelib-v3:11"))),
        "chapterInformationEntities" to listOf(chapter("linovelib-v3:11", "繁體章名")),
        "chapterContentEntities" to listOf(body("linovelib-v3:11", "舊正文")),
    )

    private fun snapshot(source: Int, vararg fields: Pair<String, List<JsonObject>>) = MigrationSnapshot(source,
        JsonObject(MigrationData.emptySnapshot(source).data.toMutableMap().apply { fields.forEach { (key, rows) -> put(key, JsonArray(rows)) } }))

    private fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(fields.associate { (key, value) -> key to element(value) })
    private fun element(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::element))
        else -> error("unsupported fixture value")
    }
    private fun info(id: String, title: String) = obj("id" to id, "title" to title, "subtitle" to "", "coverUri" to "https://old.example/cover.jpg",
        "author" to "作者", "description" to "資料", "tags" to emptyList<String>(), "publishingHouse" to "",
        "wordCount" to obj("count" to 0, "unit" to JsonNull, "unitResId" to JsonNull), "lastUpdated" to "2026-10-11T12:00", "isComplete" to false)
    private fun volume(book: String, id: String, chapters: List<String>) = obj("bookId" to book, "volumeId" to id, "volumeTitle" to "卷", "chapterIds" to chapters, "index" to 0)
    private fun chapter(id: String, title: String) = obj("id" to id, "title" to title)
    private fun body(id: String, text: String, previous: String = "", empty: Boolean = false) = obj("id" to id, "title" to "章節",
        "content" to (if (empty) "{\"components\":[]}" else obj("components" to listOf(obj("id" to "simple_text", "data" to obj("text" to text)))).toString()), "lastChapter" to previous, "nextChapter" to "")
    private fun record(id: String, reads: Int, seconds: Int, first: String = "11:00", last: String = "12:00", finished: Boolean = false, favorite: Boolean = false) = obj(
        "bookId" to id, "date" to "2026-10-11", "reads" to reads, "seconds" to seconds, "firstSeen" to first, "lastSeen" to last, "isFinished" to finished, "isFavorited" to favorite)
    private fun reading(id: String, seconds: Int, chapter: String, progress: Float,
        current: JsonObject = obj(), maximum: JsonObject = obj(), timestamp: String = "2026-10-11T12:00") = obj(
        "id" to id, "totalReadTime" to seconds, "readingProgress" to progress, "lastReadTime" to timestamp,
        "lastReadChapterId" to chapter, "lastReadChapterTitle" to "chapter-$chapter", "currentChapterReadingProgressMap" to current, "maxChapterReadingProgressMap" to maximum)
    private fun daily(first: IntArray) = obj("date" to "2026-10-11", "timeCount" to MigrationPlanner.encodeHours(IntArray(24) { first.getOrElse(it) { 0 } }))
    private fun shelf(id: Int, name: String, books: List<String>, auto: Boolean = false) = obj("id" to id, "name" to name, "sortType" to "name", "sortReversed" to true,
        "autoCache" to auto, "systemUpdateReminder" to true, "allBookIds" to books, "pinnedBookIds" to books.take(1), "updatedBookIds" to emptyList<String>())
    private fun metadata(id: String, shelves: List<Int>) = obj("id" to id, "lastUpdate" to "2026-10-11T12:00", "bookShelfIds" to shelves)
    private fun user(path: String, type: String, value: String) = obj("path" to path, "group" to "", "type" to type, "value" to value)
    private fun JsonObject.rows(key: String) = (get(key) as JsonArray).map { it.jsonObject }
    private fun JsonObject.text(key: String) = get(key)!!.jsonPrimitive.content
    private fun rejects(action: () -> Unit) {
        try { action(); throw AssertionError("Expected validation failure") } catch (_: MigrationValidationException) { }
    }
}
