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
            "volumeEntities" to listOf(volume("1", "old-volume", listOf("11", "12")), volume("2", "tw-volume-2", listOf("21"))),
            "chapterInformationEntities" to listOf(chapter("11", "舊章名"), chapter("12", "孤立舊章"), chapter("21", "另一章")),
            "chapterContentEntities" to listOf(body("11", "舊正文"), body("12", "獨有繁體正文", previous = "11"), body("21", "圖片引用 file:/old/image.png")),
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
    fun normalizesAliasPriorityAndMaximumProgressDeterministically() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "chapterContentEntities" to listOf(body("11", "raw"), body("linovelib-v2:11", "v2"), body("linovelib-v3:11", "v3")),
            "userReadingDataEntities" to listOf(reading("1", 10, "11", 0.1f,
                current = obj("11" to 0.9f, "linovelib-v2:11" to 0.5f, "linovelib-v3:11" to 0.2f),
                maximum = obj("11" to 0.9f, "linovelib-v2:11" to 0.5f, "linovelib-v3:11" to 0.2f))),
        )
        val result = MigrationPlanner.plan(current, snapshot(MigrationData.TW_SOURCE_ID)).snapshot.data
        assertTrue(result.rows("chapterContentEntities").single().text("content").contains("v3"))
        val row = result.rows("userReadingDataEntities").single()
        assertEquals("0.2", row["currentChapterReadingProgressMap"]!!.jsonObject["linovelib-v3:11"]!!.jsonPrimitive.content)
        assertEquals("0.9", row["maxChapterReadingProgressMap"]!!.jsonObject["linovelib-v3:11"]!!.jsonPrimitive.content)
        assertEquals("linovelib-v3:11", row.text("lastReadChapterId"))
    }

    @Test
    fun usesValidBodyBeforeAliasVersionPriority() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "chapterContentEntities" to listOf(body("11", "available"), body("linovelib-v3:11", "", empty = true)),
        )
        val result = MigrationPlanner.plan(current, snapshot(MigrationData.TW_SOURCE_ID)).snapshot.data
        assertTrue(result.rows("chapterContentEntities").single().text("content").contains("available"))
    }

    @Test
    fun addsPerBookStatisticsButCapsHourBucketsAtSixty() {
        val current = snapshot(MigrationData.CURRENT_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 2, 100, "11:00", "12:00", favorite = true)),
            "dailyCountEntities" to listOf(daily(intArrayOf(30, 30, 30, 45))),
            "userReadingDataEntities" to listOf(reading("1", 120, "11", 0.1f, timestamp = "2026-10-11T12:00")),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 2, 200, "10:00", "13:00", finished = true)),
            "dailyCountEntities" to listOf(daily(intArrayOf(29, 30, 31, 40))),
            "userReadingDataEntities" to listOf(reading("1", 240, "12", 0.7f, timestamp = "2026-10-11T13:00")),
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
            "volumeEntities" to listOf(volume("1", "v", listOf("11", "12"))),
            "chapterInformationEntities" to listOf(chapter("11", "A"), chapter("12", "B")),
            "userReadingDataEntities" to listOf(reading("1", 5, "99", 0f,
                current = obj("11" to 0.1f), maximum = obj("11" to 0.3f, "99" to 0.2f))),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("1", 5, "12", 1f,
                current = obj("11" to 0.8f, "12" to 0.5f), maximum = obj("11" to 0.6f, "12" to 0.8f))),
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
        val old = snapshot(MigrationData.TW_SOURCE_ID, "userReadingDataEntities" to listOf(reading("1", -1, "11", 0.7f)))
        val row = MigrationPlanner.plan(current, old).snapshot.data.rows("userReadingDataEntities").single()
        assertEquals("-1", row.text("totalReadTime")); assertEquals("0.0", row.text("readingProgress"))
        assertEquals("linovelib-v3:11", row.text("lastReadChapterId")); assertEquals("chapter-11", row.text("lastReadChapterTitle"))
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
            "userReadingDataEntities" to listOf(reading("1", 1, "11", 0f, timestamp = "2026-10-11T12:00"), reading("2", 1, "21", 0f, timestamp = "2026-10-11T12:00")),
        )
        val old = snapshot(MigrationData.TW_SOURCE_ID,
            "userDataEntities" to listOf(user("reading_books", "StringList", "3,1"), user("search.history", "StringList", "old-search"), user("old.preference", "Boolean", "true")),
            "userReadingDataEntities" to listOf(reading("3", 1, "31", 0f, timestamp = "2026-10-11T13:00")),
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
    fun rejectsOverflowBeforeReturningAnyMutationAndInputsRemainUnchanged() {
        val a = snapshot(MigrationData.CURRENT_SOURCE_ID, "bookRecordEntities" to listOf(record("1", Int.MAX_VALUE, 10)))
        val b = snapshot(MigrationData.TW_SOURCE_ID, "bookRecordEntities" to listOf(record("1", 1, 10)))
        val before = a.data.toString()
        rejects { MigrationPlanner.plan(a, b) }
        assertEquals(before, a.data.toString())
    }

    @Test
    fun rejectsUnknownIdsMalformedProgressAndInvalidCountInsteadOfGuessing() {
        rejects { MigrationPlanner.normalizeChapterId("linovelib-v4:11") }
        rejects { MigrationPlanner.normalizeChapterId("book/1/chapter11.html") }
        rejects { MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), snapshot(MigrationData.TW_SOURCE_ID,
            "userReadingDataEntities" to listOf(reading("1", 1, "11", -0.2f)))) }
        rejects { MigrationPlanner.decodeHours("////////////////////////") }
        rejects { MigrationPlanner.plan(snapshot(MigrationData.CURRENT_SOURCE_ID), snapshot(MigrationData.TW_SOURCE_ID,
            "bookRecordEntities" to listOf(record("1", 1, -1)))) }
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

    private fun catalogTw() = snapshot(MigrationData.TW_SOURCE_ID,
        "volumeEntities" to listOf(volume("1", "tw-volume", listOf("11"))),
        "chapterInformationEntities" to listOf(chapter("11", "繁體章名")),
        "chapterContentEntities" to listOf(body("11", "舊正文")),
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
