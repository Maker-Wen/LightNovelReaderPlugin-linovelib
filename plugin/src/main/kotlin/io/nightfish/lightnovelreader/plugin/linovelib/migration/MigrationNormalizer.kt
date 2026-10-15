package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Rows safe to merge, plus current personal rows that must survive without interpretation. */
internal data class NormalizedMigrationSnapshot(
    val data: JsonObject,
    val preservedPersonalRows: Map<String, List<JsonObject>>,
    val protectedKeys: Map<String, Set<String>>,
    val hasRecoverableData: Boolean,
    val unavailableRecordTimes: Map<String, Set<String>> = emptyMap(),
)

/** Recovery never guesses personal history; only incoming TW fields may use safe defaults. */
internal object MigrationNormalizer {
    const val ALL_KEYS = "*"
    private val numericId = Regex("[1-9][0-9]*")

    fun current(snapshot: MigrationSnapshot): NormalizedMigrationSnapshot {
        requireData(snapshot.sourceId == MigrationData.CURRENT_SOURCE_ID, "当前源标识不匹配")
        return normalize(snapshot, true)
    }

    fun tw(snapshot: MigrationSnapshot): NormalizedMigrationSnapshot {
        requireData(snapshot.sourceId == MigrationData.TW_SOURCE_ID, "TW 源标识不匹配")
        return normalize(snapshot, false)
    }

    /** The planner calls this again after excluding incoming rows protected by current data. */
    fun containsRecoverableData(data: JsonObject): Boolean =
        data.rows("bookInformationEntities").any { it.text("title").orEmpty().isNotBlank() } ||
            data.rows("chapterInformationEntities").isNotEmpty() ||
            data.rows("chapterContentEntities").any(::hasBody) ||
            data.rows("volumeEntities").any { it.requiredStringList("chapterIds").isNotEmpty() } ||
            data.rows("bookshelfEntities").any {
                it.requiredStringList("allBookIds").isNotEmpty() ||
                    it.requiredText("name").let { name -> name.isNotBlank() && name != "已收藏" }
            } ||
            data.rows("bookshelfBookMetadataEntities").any {
                it.requiredIntList("bookShelfIds").isNotEmpty() || it.requiredText("lastUpdate") != LocalDateTime.MIN.toString()
            } ||
            data.rows("bookRecordEntities").any(::meaningfulRecord) ||
            data.rows("dailyCountEntities").any { MigrationPlanner.decodeHours(it.requiredText("timeCount")).any { value -> value > 0 } } ||
            data.rows("userReadingDataEntities").any(::meaningfulReading) ||
            data.rows("userDataEntities").any(::meaningfulUserData)

    fun personalKey(table: String, row: JsonObject): String? = when (table) {
        "bookshelfEntities" -> row.integer("id")?.toString()
        "bookshelfBookMetadataEntities", "userReadingDataEntities" -> row.text("id")
        "bookRecordEntities" -> row.text("bookId")?.let { book -> row.text("date")?.let { "$book\u0000$it" } }
        "dailyCountEntities" -> row.text("date")
        "userDataEntities" -> row.text("path")
        else -> null
    }

    private fun normalize(snapshot: MigrationSnapshot, current: Boolean): NormalizedMigrationSnapshot {
        requireData(snapshot.data.integer("webBookDataSourceId") == snapshot.sourceId, "快照源标识不匹配")
        val rows = MigrationData.TABLES.associateWith { snapshot.data.rows(it) }
        val selected = rows.getValue("userDataEntities").filter { it.text("path") == MigrationData.SELECTED_SOURCE }
        requireData(selected.size <= 1, "快照选中源字段重复")
        selected.singleOrNull()?.let { row ->
            requireData(row.text("type") == "Int", "快照选中源字段类型非法")
            requireData(row.text("value")?.toIntOrNull() == snapshot.sourceId, "快照选中源与身份不匹配")
        }
        val output = MigrationData.emptySnapshot(snapshot.sourceId).data.toMutableMap()
        val preserved = linkedMapOf<String, MutableList<JsonObject>>()
        val protected = linkedMapOf<String, MutableSet<String>>()
        fun preserve(table: String, row: JsonObject) {
            preserved.getOrPut(table) { mutableListOf() } += row
            protected.getOrPut(table) { linkedSetOf() } += personalKey(table, row) ?: ALL_KEYS
        }
        fun personal(table: String, transform: (JsonObject) -> JsonObject?): List<JsonObject> {
            val input = rows.getValue(table).filterNot { table == "userReadingDataEntities" && it.text("id") == "" }
            val counts = input.mapNotNull { personalKey(table, it) }.groupingBy { it }.eachCount()
            return input.mapNotNull { raw ->
                val duplicate = personalKey(table, raw)?.let { counts.getValue(it) > 1 } == true
                val normalized = if (duplicate) null else attempt { transform(raw) }
                if (normalized == null && current) preserve(table, raw)
                normalized
            }
        }
        val information = keyedCaches(rows.getValue("bookInformationEntities"), "id", ::information) { it.text("title").orEmpty().isNotBlank() }
        output["bookInformationEntities"] = JsonArray(information)
        val chapters = aliases(rows.getValue("chapterInformationEntities"), { row ->
            row.with("id" to JsonPrimitive(chapterId(row.requiredText("id"))), "title" to JsonPrimitive(row.text("title").orEmpty()))
        }) { true }
        output["chapterInformationEntities"] = JsonArray(chapters)
        val bodies = aliases(rows.getValue("chapterContentEntities"), ::body, ::hasBody)
        output["chapterContentEntities"] = JsonArray(bodies)
        val volumes = volumes(rows.getValue("volumeEntities"), chapters)
        output["volumeEntities"] = JsonArray(volumes)
        val shelves = personal("bookshelfEntities") { if (current) strictShelf(it) else incomingShelf(it) }
        output["bookshelfEntities"] = JsonArray(shelves)
        val shelfIds = shelves.mapNotNull { it.integer("id") }.toSet()
        val metadata = personal("bookshelfBookMetadataEntities") { raw ->
            if (current) {
                bookId(raw.requiredText("id")); validDateTime(raw.requiredText("lastUpdate"))
                requireData(shelfIds.containsAll(raw.requiredIntList("bookShelfIds")), "书架反向引用无效")
                raw
            } else {
                val id = raw.requiredText("id"); bookId(id)
                raw.with("lastUpdate" to JsonPrimitive(dateTimeOrNull(raw.text("lastUpdate"))?.toString() ?: LocalDateTime.MIN.toString()),
                    "bookShelfIds" to JsonArray(shelves.filter { id in it.requiredStringList("allBookIds") }.map { JsonPrimitive(it.requiredInt("id")) }))
            }
        }
        output["bookshelfBookMetadataEntities"] = JsonArray(metadata)
        val unavailableRecordTimes = linkedMapOf<String, Set<String>>()
        val records = personal("bookRecordEntities") { raw ->
            if (current) strictRecord(raw) else incomingRecord(raw).also { row ->
                val unavailable = setOf("firstSeen", "lastSeen").filter { timeOrNull(raw.text(it)) == null }.toSet()
                if (unavailable.isNotEmpty()) unavailableRecordTimes[personalKey("bookRecordEntities", row)!!] = unavailable
            }
        }
        output["bookRecordEntities"] = JsonArray(records)
        val daily = personal("dailyCountEntities") { raw ->
            validDate(raw.requiredText("date")); MigrationPlanner.decodeHours(raw.requiredText("timeCount")); raw
        }
        output["dailyCountEntities"] = JsonArray(daily)
        val reading = personal("userReadingDataEntities") { if (current) strictReading(it) else incomingReading(it) }
        output["userReadingDataEntities"] = JsonArray(reading)
        val userData = personal("userDataEntities") { if (current) strictUserData(it) else incomingUserData(it) }
        output["userDataEntities"] = JsonArray(userData)
        // Formatting and ordinary preferences belong to the current installation.
        if (current) output["formattingRuleEntities"] = JsonArray(rows.getValue("formattingRuleEntities"))
        val data = JsonObject(output)
        return NormalizedMigrationSnapshot(data, preserved.mapValues { it.value.toList() },
            protected.mapValues { it.value.toSet() }, containsRecoverableData(data), unavailableRecordTimes)
    }

    private fun information(raw: JsonObject): JsonObject {
        bookId(raw.requiredText("id"))
        val wordCount = raw["wordCount"] as? JsonObject
        val fields = listOf("title", "subtitle", "coverUri", "author", "description", "publishingHouse")
            .map { it to JsonPrimitive(raw.text(it).orEmpty()) }
        return raw.with(*(fields + listOf(
            "tags" to JsonArray((raw["tags"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.map(::JsonPrimitive)),
            "lastUpdated" to JsonPrimitive(dateTimeOrNull(raw.text("lastUpdated"))?.toString() ?: LocalDateTime.MIN.toString()),
            "isComplete" to JsonPrimitive(raw.boolean("isComplete") ?: false),
            "wordCount" to JsonObject(mapOf("count" to JsonPrimitive(wordCount?.integer("count")?.takeIf { it >= 0 } ?: 0),
                "unit" to (wordCount?.text("unit")?.let(::JsonPrimitive) ?: JsonNull),
                "unitResId" to (wordCount?.integer("unitResId")?.let(::JsonPrimitive) ?: JsonNull))),
        )).toTypedArray())
    }

    private fun strictShelf(raw: JsonObject): JsonObject {
        requireData(raw.requiredInt("id") != -1, "书架 ID 无效")
        raw.requiredText("name"); raw.requiredText("sortType")
        raw.requiredBoolean("sortReversed", false); raw.requiredBoolean("autoCache"); raw.requiredBoolean("systemUpdateReminder")
        val members = raw.requiredStringList("allBookIds").also { it.forEach(::bookId) }.toSet()
        listOf("pinnedBookIds", "updatedBookIds").forEach { key ->
            val ids = raw.requiredStringList(key).also { it.forEach(::bookId) }
            requireData(members.containsAll(ids), "书架引用无效")
        }
        return raw
    }

    private fun incomingShelf(raw: JsonObject): JsonObject {
        requireData(raw.requiredInt("id") != -1, "书架 ID 无效")
        raw.requiredText("name")
        val members = incomingIds(raw["allBookIds"])
        return raw.with("allBookIds" to members.strings(),
            "pinnedBookIds" to incomingIds(raw["pinnedBookIds"]).filter { it in members }.strings(),
            "updatedBookIds" to incomingIds(raw["updatedBookIds"]).filter { it in members }.strings(),
            "sortType" to JsonPrimitive(raw.text("sortType") ?: "default"),
            "sortReversed" to JsonPrimitive(raw.boolean("sortReversed") ?: false),
            "autoCache" to JsonPrimitive(raw.boolean("autoCache") ?: false),
            "systemUpdateReminder" to JsonPrimitive(raw.boolean("systemUpdateReminder") ?: false))
    }

    private fun strictRecord(raw: JsonObject): JsonObject {
        bookId(raw.requiredText("bookId")); validDate(raw.requiredText("date"))
        requireData(raw.requiredInt("reads") >= 0 && raw.requiredInt("seconds") >= 0, "阅读统计不能为负数")
        raw.requiredBoolean("isFinished", false); raw.requiredBoolean("isFavorited", false)
        validTime(raw.requiredText("firstSeen")); validTime(raw.requiredText("lastSeen"))
        return raw
    }

    private fun incomingRecord(raw: JsonObject): JsonObject {
        bookId(raw.requiredText("bookId")); validDate(raw.requiredText("date"))
        val first = timeOrNull(raw.text("firstSeen")); val last = timeOrNull(raw.text("lastSeen"))
        return raw.with("reads" to JsonPrimitive(raw.integer("reads")?.takeIf { it >= 0 } ?: 0),
            "seconds" to JsonPrimitive(raw.integer("seconds")?.takeIf { it >= 0 } ?: 0),
            "isFinished" to JsonPrimitive(raw.boolean("isFinished") ?: false),
            "isFavorited" to JsonPrimitive(raw.boolean("isFavorited") ?: false),
            "firstSeen" to JsonPrimitive((first ?: last ?: LocalTime.MIN).toString()),
            "lastSeen" to JsonPrimitive((last ?: first ?: LocalTime.MIN).toString()))
    }

    private fun strictReading(raw: JsonObject): JsonObject {
        bookId(raw.requiredText("id")); validDateTime(raw.requiredText("lastReadTime")); raw.requiredText("lastReadChapterTitle")
        requireData(raw.requiredInt("totalReadTime") >= -1, "累计阅读秒数非法")
        progress(raw["readingProgress"], false)
        return raw.with("lastReadChapterId" to JsonPrimitive(chapterId(raw.requiredText("lastReadChapterId"), true)),
            "currentChapterReadingProgressMap" to progressMap(raw.requiredObject("currentChapterReadingProgressMap"), false, false),
            "maxChapterReadingProgressMap" to progressMap(raw.requiredObject("maxChapterReadingProgressMap"), true, false))
    }

    private fun incomingReading(raw: JsonObject): JsonObject {
        bookId(raw.requiredText("id"))
        return raw.with("lastReadTime" to JsonPrimitive(dateTimeOrNull(raw.text("lastReadTime"))?.toString() ?: LocalDateTime.MIN.toString()),
            "lastReadChapterId" to JsonPrimitive(attempt { chapterId(raw.text("lastReadChapterId").orEmpty(), true) } ?: ""),
            "lastReadChapterTitle" to JsonPrimitive(raw.text("lastReadChapterTitle").orEmpty()),
            "totalReadTime" to JsonPrimitive(raw.integer("totalReadTime")?.takeIf { it >= -1 } ?: -1),
            "readingProgress" to (attempt { progress(raw["readingProgress"], true) } ?: JsonPrimitive(0.0)),
            "currentChapterReadingProgressMap" to progressMap(raw["currentChapterReadingProgressMap"] as? JsonObject ?: JsonObject(emptyMap()), false, true),
            "maxChapterReadingProgressMap" to progressMap(raw["maxChapterReadingProgressMap"] as? JsonObject ?: JsonObject(emptyMap()), true, true))
    }

    private fun strictUserData(raw: JsonObject): JsonObject {
        listOf("path", "group", "type", "value").forEach { raw.requiredText(it) }
        when (raw.requiredText("path")) {
            MigrationData.READING_BOOKS -> {
                requireData(raw.requiredText("type") == "StringList", "最近阅读数据类型非法")
                commaList(raw.requiredText("value")).forEach(::bookId)
            }
            MigrationData.COMPLETED_DOWNLOADS -> {
                requireData(raw.requiredText("type") == "CompletedDownloadItemList", "下载完成记录类型非法")
                commaList(raw.requiredText("value")).forEach { item ->
                    val pair = item.split('|')
                    requireData(pair.size == 2 && pair[0] in setOf("CACHE", "EPUB_EXPORT"), "下载完成记录无效")
                    bookId(pair[1])
                }
            }
        }
        return raw
    }

    private fun incomingUserData(raw: JsonObject): JsonObject? {
        val path = raw.text("path") ?: return null
        val value = raw.text("value") ?: return null
        val cleaned = when (path) {
            MigrationData.READING_BOOKS -> {
                requireData(raw.text("type") == "StringList", "最近阅读数据类型非法")
                value.split(',').map(String::trim).filter(numericId::matches).distinct().joinToString(",")
            }
            MigrationData.COMPLETED_DOWNLOADS -> {
                requireData(raw.text("type") == "CompletedDownloadItemList", "下载完成记录类型非法")
                value.split(',').map(String::trim).filter { item ->
                    val pair = item.split('|')
                    pair.size == 2 && pair[0] in setOf("CACHE", "EPUB_EXPORT") && numericId.matches(pair[1])
                }.distinct().joinToString(", ")
            }
            else -> return null
        }
        return raw.with("group" to JsonPrimitive(raw.text("group").orEmpty()), "value" to JsonPrimitive(cleaned))
    }

    private fun progressMap(raw: JsonObject, maximum: Boolean, incoming: Boolean): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        val priorities = mutableMapOf<String, Int>()
        raw.forEach { (rawId, rawValue) ->
            val pair = if (incoming) attempt { chapterId(rawId) to progress(rawValue, true) }
                else chapterId(rawId) to progress(rawValue, false)
            pair?.let { (id, value) ->
                val rank = aliasRank(rawId)
                if (maximum && ((result[id] as? JsonPrimitive)?.doubleOrNull ?: -1.0) < value.doubleOrNull!! ||
                    !maximum && rank < (priorities[id] ?: Int.MAX_VALUE)) {
                    result[id] = value; priorities[id] = rank
                }
            }
        }
        return JsonObject(result)
    }

    private fun progress(raw: JsonElement?, incoming: Boolean): JsonPrimitive {
        val primitive = raw as? JsonPrimitive ?: fail("阅读进度不是数值")
        val value = primitive.takeUnless { it.isString }?.doubleOrNull ?: fail("阅读进度不是数值")
        requireData(value.isFinite(), "阅读进度不是有限数值")
        if (!incoming) requireData(value in 0.0..1.0, "阅读进度超出范围")
        return if (value in 0.0..1.0) primitive else JsonPrimitive(value.coerceIn(0.0, 1.0))
    }

    private fun body(raw: JsonObject): JsonObject {
        val content = raw.requiredText("content")
        val parsed = Json.parseToJsonElement(content) as? JsonObject ?: fail("正文不是 JSON 对象")
        val components = normalizedComponents(parsed["components"])
        val safeContent = if (components != null && components != parsed["components"])
            parsed.with("components" to components).toString() else content
        return raw.with("id" to JsonPrimitive(chapterId(raw.requiredText("id"))),
            "title" to JsonPrimitive(raw.text("title").orEmpty()),
            "content" to JsonPrimitive(safeContent),
            "lastChapter" to JsonPrimitive(attempt { chapterId(raw.text("lastChapter").orEmpty(), true) } ?: ""),
            "nextChapter" to JsonPrimitive(attempt { chapterId(raw.text("nextChapter").orEmpty(), true) } ?: ""))
    }

    private fun normalizedComponents(value: JsonElement?): JsonArray? {
        if (value == null) return null
        val components = value as? JsonArray ?: fail("正文组件列表无效")
        return JsonArray(components.map { value ->
            val component = value as? JsonObject ?: fail("正文组件不是对象")
            val id = component.requiredText("id")
            val data = component.requiredObject("data")
            val normalizedData = when (id) {
                "simple_text" -> componentData(data, "text")
                "image" -> componentData(data, "uri")
                else -> data // The host renders an error for an unknown component ID.
            }
            if (normalizedData == data) component else component.with("data" to normalizedData)
        })
    }

    private fun componentData(data: JsonObject, valueField: String): JsonObject {
        data.requiredText(valueField)
        return JsonObject(data.filter { (field, value) ->
            field == valueField || field == "id" && value.stringOrNull() != null
        })
    }

    private fun hasBody(raw: JsonObject): Boolean = ((Json.parseToJsonElement(raw.requiredText("content")) as JsonObject)["components"] as? JsonArray)?.isNotEmpty() == true

    private fun aliases(input: List<JsonObject>, transform: (JsonObject) -> JsonObject, useful: (JsonObject) -> Boolean): List<JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        val priorities = mutableMapOf<String, Int>()
        input.forEach { raw -> attempt { transform(raw) }?.let { row ->
            val id = row.requiredText("id"); val rank = aliasRank(raw.requiredText("id")); val old = result[id]
            if (old == null || useful(row) && !useful(old) || useful(row) == useful(old) && rank < priorities.getValue(id)) {
                result[id] = row; priorities[id] = rank
            }
        } }
        return result.values.toList()
    }

    private fun keyedCaches(input: List<JsonObject>, key: String, transform: (JsonObject) -> JsonObject, useful: (JsonObject) -> Boolean): List<JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        input.forEach { raw -> attempt { transform(raw) }?.let { row ->
            val id = row.requiredText(key); val old = result[id]
            if (old == null || !useful(old) && useful(row)) result[id] = row
        } }
        return result.values.toList()
    }

    private fun volumes(input: List<JsonObject>, chapters: List<JsonObject>): List<JsonObject> {
        val chapterIds = chapters.map { it.requiredText("id") }.toSet()
        val rejected = mutableSetOf<String>()
        val valid = input.mapNotNull { raw ->
            val book = raw.text("bookId")?.takeIf(numericId::matches) ?: return@mapNotNull null
            attempt {
                requireData(raw.requiredText("volumeId").isNotBlank(), "卷 ID 为空")
                requireData(raw.requiredInt("index") >= 0, "卷顺序无效")
                val ids = raw.requiredStringList("chapterIds").map { chapterId(it) }
                requireData(ids.size == ids.distinct().size && chapterIds.containsAll(ids), "目录章节无效")
                raw.with("chapterIds" to ids.strings(), "volumeTitle" to JsonPrimitive(raw.text("volumeTitle").orEmpty()))
            }.also { if (it == null) rejected += book }
        }
        valid.groupBy { it.requiredText("volumeId") }.values.filter { it.size > 1 }.forEach { duplicate ->
            rejected += duplicate.map { it.requiredText("bookId") }
        }
        valid.groupBy { it.requiredText("bookId") }.forEach { (book, group) ->
            val ids = group.flatMap { it.requiredStringList("chapterIds") }
            if (group.map { it.requiredInt("index") }.distinct().size != group.size || ids.distinct().size != ids.size) rejected += book
        }
        val owners = mutableMapOf<String, MutableSet<String>>()
        valid.forEach { volume -> volume.requiredStringList("chapterIds").forEach { id ->
            owners.getOrPut(id) { linkedSetOf() } += volume.requiredText("bookId")
        } }
        owners.values.filter { it.size > 1 }.forEach { rejected += it }
        return valid.filterNot { it.requiredText("bookId") in rejected }
    }

    private fun meaningfulRecord(row: JsonObject): Boolean = row.requiredInt("reads") > 0 || row.requiredInt("seconds") > 0 ||
        row.requiredBoolean("isFinished", false) || row.requiredBoolean("isFavorited", false) ||
        row.requiredText("firstSeen") != LocalTime.MIN.toString() || row.requiredText("lastSeen") != LocalTime.MIN.toString()
    private fun meaningfulReading(row: JsonObject): Boolean = row.requiredInt("totalReadTime") > 0 ||
        row.requiredText("lastReadTime") != LocalDateTime.MIN.toString() || row.requiredText("lastReadChapterId").isNotEmpty() ||
        (row["readingProgress"] as JsonPrimitive).doubleOrNull!! > 0 ||
        row.requiredObject("currentChapterReadingProgressMap").isNotEmpty() || row.requiredObject("maxChapterReadingProgressMap").isNotEmpty()
    private fun meaningfulUserData(row: JsonObject): Boolean = when (row.text("path")) {
        MigrationData.READING_BOOKS -> row.requiredText("value").isNotBlank()
        MigrationData.COMPLETED_DOWNLOADS -> row.requiredText("value").split(',').any { it.trim().startsWith("CACHE|") }
        else -> false
    }

    private fun JsonObject.rows(table: String): List<JsonObject> = (this[table] as? JsonArray ?: fail("缺少数据表：$table")).map {
        it as? JsonObject ?: fail("数据表包含非对象记录：$table")
    }
    private fun JsonElement.stringOrNull() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.text(field: String) = this[field]?.stringOrNull()
    private fun JsonObject.integer(field: String) = (this[field] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.boolean(field: String) = (this[field] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.requiredText(field: String) = text(field) ?: fail("字段不是字符串：$field")
    private fun JsonObject.requiredInt(field: String) = integer(field) ?: fail("字段不是整数：$field")
    private fun JsonObject.requiredBoolean(field: String, default: Boolean? = null): Boolean =
        if (this[field] == null && default != null) default else boolean(field) ?: fail("字段不是布尔值：$field")
    private fun JsonObject.requiredObject(field: String) = this[field] as? JsonObject ?: fail("字段不是对象：$field")
    private fun JsonObject.requiredStringList(field: String) = (this[field] as? JsonArray ?: fail("字段不是列表：$field")).map {
        it.stringOrNull() ?: fail("列表元素不是字符串：$field")
    }
    private fun JsonObject.requiredIntList(field: String) = (this[field] as? JsonArray ?: fail("字段不是列表：$field")).map {
        (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull ?: fail("列表元素不是整数：$field")
    }
    private fun incomingIds(value: JsonElement?) = (value as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.filter(numericId::matches).distinct()
    private fun List<String>.strings() = JsonArray(map(::JsonPrimitive))
    private fun JsonObject.with(vararg fields: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(fields) })
    private fun bookId(id: String) { requireData(numericId.matches(id), "不支持的书籍 ID：$id") }
    private fun chapterId(id: String, allowEmpty: Boolean = false) = MigrationPlanner.normalizeChapterId(id, allowEmpty)
    private fun aliasRank(id: String) = when { id.startsWith("linovelib-v3:") -> 0; id.startsWith("linovelib-v2:") -> 1; else -> 2 }
    private fun commaList(value: String) = if (value.isBlank()) emptyList() else value.split(',').map(String::trim).also {
        requireData(it.all(String::isNotEmpty), "列表包含空记录")
    }
    private fun validDate(value: String): LocalDate {
        val date = dateOrNull(value) ?: fail("日期无效")
        requireData(date.year in 1000..9999, "日期超出宿主支持范围")
        return date
    }
    private fun validDateTime(value: String) = dateTimeOrNull(value) ?: fail("阅读时间无效")
    private fun validTime(value: String) = timeOrNull(value) ?: fail("阅读时刻无效")
    private fun dateOrNull(value: String?) = value?.let { attempt { LocalDate.parse(it) } }
    private fun dateTimeOrNull(value: String?) = value?.let { attempt { LocalDateTime.parse(it) } }
    private fun timeOrNull(value: String?) = value?.let { attempt { LocalTime.parse(it) } }
    // Pure row parsing only. The API 3 host can rename desugared time exceptions, so subclasses are not a stable boundary.
    private inline fun <T> attempt(block: () -> T): T? = try { block() }
        catch (_: Exception) { null }
    private fun requireData(condition: Boolean, message: String) { if (!condition) fail(message) }
    private fun fail(message: String): Nothing = throw MigrationValidationException(message)
}
