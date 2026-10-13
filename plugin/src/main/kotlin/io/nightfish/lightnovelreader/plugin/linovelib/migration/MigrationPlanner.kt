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
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Pure local merge. The host adapter owns transactions and the once-only receipt. */
internal object MigrationPlanner {
    private val json = Json
    private val numericId = Regex("[1-9][0-9]*")
    private val base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun plan(
        current: MigrationSnapshot,
        tw: MigrationSnapshot,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): MigrationPlan {
        checkData(current.sourceId == MigrationData.CURRENT_SOURCE_ID, "当前源标识不匹配")
        checkData(tw.sourceId == MigrationData.TW_SOURCE_ID, "TW 源标识不匹配")
        val target = normalized(current)
        val old = normalized(tw)
        val output = target.toMutableMap()

        val information = preferRows(target.rows("bookInformationEntities"), old.rows("bookInformationEntities"), "id") {
            it.string("title").isNotBlank()
        }
        output["bookInformationEntities"] = information.asArray()

        val targetVolumes = target.rows("volumeEntities").groupBy { it.string("bookId") }
        val oldVolumes = old.rows("volumeEntities").groupBy { it.string("bookId") }
        val targetChapters = target.rows("chapterInformationEntities").associateBy { it.string("id") }
        val oldChapters = old.rows("chapterInformationEntities").associateBy { it.string("id") }
        val chosenVolumes = mutableListOf<JsonObject>()
        val chosenChapters = LinkedHashMap<String, JsonObject>()
        val catalogSizes = LinkedHashMap<String, Int>()
        val books = linkedSetOf<String>().apply {
            addAll(information.map { it.string("id") })
            addAll(targetVolumes.keys); addAll(oldVolumes.keys)
            addAll(target.rows("userReadingDataEntities").map { it.string("id") })
            addAll(old.rows("userReadingDataEntities").map { it.string("id") })
            addAll(target.rows("bookRecordEntities").map { it.string("bookId") })
            addAll(old.rows("bookRecordEntities").map { it.string("bookId") })
            addAll(target.rows("bookshelfEntities").flatMap { it.stringList("allBookIds") })
            addAll(old.rows("bookshelfEntities").flatMap { it.stringList("allBookIds") })
        }
        val chapterOwners = mutableMapOf<String, String>()
        books.forEachIndexed { index, book ->
            val availableTarget = targetVolumes[book].orEmpty().sortedBy { it.int("index") }
            val useTarget = availableTarget.sumOf { it.stringList("chapterIds").size } > 0
            val selected = if (useTarget) availableTarget else oldVolumes[book].orEmpty().sortedBy { it.int("index") }
            val chapterMap = if (useTarget) targetChapters else oldChapters
            chosenVolumes += selected
            var count = 0
            selected.forEach { volume ->
                volume.stringList("chapterIds").forEach { chapterId ->
                    val previousOwner = chapterOwners.putIfAbsent(chapterId, book)
                    checkData(previousOwner == null || previousOwner == book, "章节属于多个书籍：$chapterId")
                    chosenChapters[chapterId] = chapterMap.getValue(chapterId)
                    count++
                }
            }
            catalogSizes[book] = count
            onProgress(index + 1, books.size)
        }
        checkUnique(chosenVolumes, "volumeId")
        output["volumeEntities"] = chosenVolumes.asArray()
        // Preserve standalone chapter metadata too. Chosen whole catalogs override its title.
        val chapters = preferRows(targetChapters.values.toList(), oldChapters.values.toList(), "id") { true }
            .associateByTo(LinkedHashMap()) { it.string("id") }
        chapters.putAll(chosenChapters)
        output["chapterInformationEntities"] = chapters.values.toList().asArray()
        val bodies = preferRows(target.rows("chapterContentEntities"), old.rows("chapterContentEntities"), "id", ::hasBody)
        output["chapterContentEntities"] = bodies.asArray()

        val shelfResult = mergeShelves(target, old)
        output["bookshelfEntities"] = shelfResult.first.asArray()
        output["bookshelfBookMetadataEntities"] = shelfResult.second.asArray()
        output["bookRecordEntities"] = mergeKeyed(target.rows("bookRecordEntities"), old.rows("bookRecordEntities"),
            { it.string("bookId") + "\u0000" + it.string("date") }, ::mergeRecord).asArray()
        output["dailyCountEntities"] = mergeKeyed(target.rows("dailyCountEntities"), old.rows("dailyCountEntities"),
            { it.string("date") }) { a, b ->
            val left = decodeHours(a.string("timeCount")); val right = decodeHours(b.string("timeCount"))
            a.with("timeCount" to JsonPrimitive(encodeHours(IntArray(24) { (left[it] + right[it]).coerceAtMost(60) })))
        }.asArray()
        val reading = mergeKeyed(target.rows("userReadingDataEntities"), old.rows("userReadingDataEntities"),
            { it.string("id") }, ::mergeReading).map { row ->
            val count = catalogSizes[row.string("id")] ?: 0
            if (count == 0) row else {
                val sum = row.objectField("maxChapterReadingProgressMap").values.sumOf { it.progress() }
                row.with("readingProgress" to JsonPrimitive((sum / count).coerceIn(0.0, 1.0).toFloat()))
            }
        }
        output["userReadingDataEntities"] = reading.asArray()
        output["userDataEntities"] = mergeUserData(target, old, reading, chosenVolumes, bodies).asArray()
        output["webBookDataSourceId"] = JsonPrimitive(MigrationData.CURRENT_SOURCE_ID)
        return MigrationPlan(MigrationSnapshot(MigrationData.CURRENT_SOURCE_ID, JsonObject(output)), personalFingerprint(tw), books.size)
    }

    fun validate(snapshot: MigrationSnapshot) {
        normalized(snapshot)
    }

    /** Excludes disposable caches and preferences; ordering does not affect identity. */
    fun personalFingerprint(snapshot: MigrationSnapshot): String {
        val fields = listOf("bookshelfEntities", "bookshelfBookMetadataEntities", "bookRecordEntities",
            "dailyCountEntities", "userReadingDataEntities")
        val personal = JsonObject(buildMap {
            fields.forEach { field ->
                put(field, JsonArray(snapshot.data.rows(field).map(::canonical).sortedBy(JsonElement::toString)))
            }
            put("userDataEntities", JsonArray(snapshot.data.rows("userDataEntities")
                .filter { it.string("path") in setOf(MigrationData.READING_BOOKS, MigrationData.COMPLETED_DOWNLOADS) }
                .map(::canonical).sortedBy(JsonElement::toString)))
        })
        return MessageDigest.getInstance("SHA-256").digest(personal.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun normalizeChapterId(id: String, allowEmpty: Boolean = false): String {
        if (allowEmpty && id.isBlank()) return ""
        val website = when {
            id.startsWith("linovelib-v3:") -> id.removePrefix("linovelib-v3:")
            id.startsWith("linovelib-v2:") -> id.removePrefix("linovelib-v2:")
            else -> id
        }
        checkData(numericId.matches(website), "不支持的章节 ID：$id")
        return "linovelib-v3:$website"
    }

    private fun normalized(snapshot: MigrationSnapshot): JsonObject {
        val data = snapshot.data
        checkData(data.int("webBookDataSourceId") == snapshot.sourceId, "快照源标识不匹配")
        MigrationData.TABLES.forEach { field -> data.rows(field) }
        val output = data.toMutableMap()
        val information = data.rows("bookInformationEntities")
        information.forEach { row ->
            bookId(row.string("id")); listOf("title", "subtitle", "coverUri", "author", "description", "publishingHouse").forEach { row.string(it) }
            row.stringList("tags"); dateTime(row.string("lastUpdated")); row.bool("isComplete")
            val wordCount = row.objectField("wordCount")
            checkData(wordCount.int("count") >= 0, "书籍字数非法")
            if (wordCount.required("unit") != JsonNull) wordCount.string("unit")
            if (wordCount.required("unitResId") != JsonNull) wordCount.int("unitResId")
        }
        checkUnique(information, "id")
        val records = data.rows("bookRecordEntities")
        records.forEach { row ->
            bookId(row.string("bookId")); date(row.string("date"))
            nonnegative(row.int("reads"), "阅读次数"); nonnegative(row.int("seconds"), "阅读秒数")
            row.bool("isFinished", false); row.bool("isFavorited", false)
            // API 3 may save a buffered start time as lastSeen after firstSeen was created.
            time(row.string("firstSeen")); time(row.string("lastSeen"))
        }
        checkUnique(records) { it.string("bookId") + "\u0000" + it.string("date") }
        val daily = data.rows("dailyCountEntities")
        daily.forEach { row -> date(row.string("date")); decodeHours(row.string("timeCount")) }
        checkUnique(daily, "date")
        val shelves = data.rows("bookshelfEntities")
        shelves.forEach { row ->
            checkData(row.int("id") != -1, "书架 ID 无效")
            row.string("name"); row.string("sortType"); row.bool("sortReversed", false)
            row.bool("autoCache"); row.bool("systemUpdateReminder")
            listOf("allBookIds", "pinnedBookIds", "updatedBookIds").forEach { field ->
                row.stringList(field).forEach(::bookId)
            }
            val members = row.stringList("allBookIds").toSet()
            checkData(members.containsAll(row.stringList("pinnedBookIds")), "置顶书籍不在书架内")
            checkData(members.containsAll(row.stringList("updatedBookIds")), "更新书籍不在书架内")
        }
        checkUnique(shelves, "id")
        val shelfIds = shelves.map { it.int("id") }.toSet()
        val metadata = data.rows("bookshelfBookMetadataEntities")
        metadata.forEach { row ->
            bookId(row.string("id")); dateTime(row.string("lastUpdate"))
            checkData(shelfIds.containsAll(row.intList("bookShelfIds")), "书架反向引用无效")
        }
        checkUnique(metadata, "id")
        val chapters = normalizeAliases(data.rows("chapterInformationEntities"), { row ->
            row.string("title"); row.with("id" to JsonPrimitive(normalizeChapterId(row.string("id"))))
        }, { true })
        output["chapterInformationEntities"] = chapters.asArray()
        val chapterIds = chapters.map { it.string("id") }.toSet()
        val volumes = data.rows("volumeEntities").map { row ->
            bookId(row.string("bookId")); checkData(row.string("volumeId").isNotBlank(), "卷 ID 为空")
            row.string("volumeTitle"); nonnegative(row.int("index"), "卷顺序")
            val ids = row.stringList("chapterIds").map { normalizeChapterId(it) }
            checkData(ids.distinct().size == ids.size, "卷内章节重复")
            checkData(chapterIds.containsAll(ids), "目录缺少章节资料")
            row.with("chapterIds" to ids.stringArray())
        }
        checkUnique(volumes, "volumeId")
        volumes.groupBy { it.string("bookId") }.values.forEach { group ->
            val ids = group.flatMap { it.stringList("chapterIds") }
            checkData(ids.distinct().size == ids.size, "整本目录包含重复章节")
            checkData(group.map { it.int("index") }.distinct().size == group.size, "卷顺序重复")
        }
        output["volumeEntities"] = volumes.asArray()
        output["chapterContentEntities"] = normalizeAliases(data.rows("chapterContentEntities"), { row ->
            row.string("title"); body(row)
            row.with(
                "id" to JsonPrimitive(normalizeChapterId(row.string("id"))),
                "lastChapter" to JsonPrimitive(normalizeChapterId(row.string("lastChapter"), true)),
                "nextChapter" to JsonPrimitive(normalizeChapterId(row.string("nextChapter"), true)),
            )
        }, ::hasBody).asArray()
        val reading = data.rows("userReadingDataEntities").map { row ->
            bookId(row.string("id")); dateTime(row.string("lastReadTime")); row.string("lastReadChapterTitle")
            checkData(row.int("totalReadTime") >= -1, "累计阅读秒数非法")
            row.required("readingProgress").progress()
            row.with(
                "lastReadChapterId" to JsonPrimitive(normalizeChapterId(row.string("lastReadChapterId"), true)),
                "currentChapterReadingProgressMap" to normalizeProgress(row.objectField("currentChapterReadingProgressMap"), false),
                "maxChapterReadingProgressMap" to normalizeProgress(row.objectField("maxChapterReadingProgressMap"), true),
            )
        }
        checkUnique(reading, "id")
        output["userReadingDataEntities"] = reading.asArray()
        val userData = data.rows("userDataEntities")
        userData.forEach { row ->
            listOf("path", "group", "type", "value").forEach { row.string(it) }
            when (row.string("path")) {
                MigrationData.READING_BOOKS -> {
                    checkData(row.string("type") == "StringList", "最近阅读数据类型非法")
                    commaList(row.string("value")).forEach(::bookId)
                }
                MigrationData.COMPLETED_DOWNLOADS -> downloads(row)
                MigrationData.SELECTED_SOURCE -> {
                    checkData(row.string("type") == "Int", "快照选中源字段类型非法")
                    checkData(row.string("value").toIntOrNull() == snapshot.sourceId, "快照选中源与身份不匹配")
                }
            }
        }
        checkUnique(userData, "path")
        return JsonObject(output)
    }

    private fun mergeRecord(a: JsonObject, b: JsonObject): JsonObject = a.with(
        "reads" to JsonPrimitive(sumInt(a.int("reads"), b.int("reads"), "阅读次数")),
        "seconds" to JsonPrimitive(sumInt(a.int("seconds"), b.int("seconds"), "阅读秒数")),
        "isFinished" to JsonPrimitive(a.bool("isFinished", false) || b.bool("isFinished", false)),
        "isFavorited" to JsonPrimitive(a.bool("isFavorited", false) || b.bool("isFavorited", false)),
        "firstSeen" to JsonPrimitive(minOf(time(a.string("firstSeen")), time(b.string("firstSeen"))).toString()),
        "lastSeen" to JsonPrimitive(maxOf(time(a.string("lastSeen")), time(b.string("lastSeen"))).toString()),
    )

    private fun mergeReading(a: JsonObject, b: JsonObject): JsonObject {
        val hasTargetPosition = a.string("lastReadChapterId").isNotBlank()
        val leftTime = a.int("totalReadTime"); val rightTime = b.int("totalReadTime")
        val total = if (leftTime == -1 && rightTime == -1) -1 else sumInt(leftTime.coerceAtLeast(0), rightTime.coerceAtLeast(0), "累计阅读秒数")
        val currentProgress = LinkedHashMap(b.objectField("currentChapterReadingProgressMap"))
        currentProgress.putAll(a.objectField("currentChapterReadingProgressMap"))
        val maximum = LinkedHashMap(a.objectField("maxChapterReadingProgressMap"))
        b.objectField("maxChapterReadingProgressMap").forEach { (id, value) ->
            if (value.progress() > (maximum[id]?.progress() ?: -1.0)) maximum[id] = value
        }
        return a.with(
            "lastReadTime" to JsonPrimitive(maxOf(dateTime(a.string("lastReadTime")), dateTime(b.string("lastReadTime"))).toString()),
            "totalReadTime" to JsonPrimitive(total),
            "lastReadChapterId" to (if (hasTargetPosition) a else b).required("lastReadChapterId"),
            "lastReadChapterTitle" to (if (hasTargetPosition) a else b).required("lastReadChapterTitle"),
            "currentChapterReadingProgressMap" to JsonObject(currentProgress),
            "maxChapterReadingProgressMap" to JsonObject(maximum),
        )
    }

    private fun mergeShelves(target: JsonObject, old: JsonObject): Pair<List<JsonObject>, List<JsonObject>> {
        val result = target.rows("bookshelfEntities").associateByTo(LinkedHashMap()) { it.int("id") }
        val names = result.values.groupBy { it.string("name") }.mapValues { (_, value) -> value.first().int("id") }.toMutableMap()
        val mapping = mutableMapOf<Int, Int>()
        var nextId = 0
        old.rows("bookshelfEntities").sortedBy { it.int("id") }.forEach { shelf ->
            val id = names[shelf.string("name")] ?: shelf.int("id").takeUnless { it in result } ?: run {
                while (nextId in result || nextId == -1) {
                    checkData(nextId < Int.MAX_VALUE, "无法分配书架 ID"); nextId++
                }
                nextId
            }
            mapping[shelf.int("id")] = id
            val existing = result[id]
            result[id] = if (existing != null) existing.with(
                "allBookIds" to (existing.stringList("allBookIds") + shelf.stringList("allBookIds")).distinct().stringArray(),
                "pinnedBookIds" to (existing.stringList("pinnedBookIds") + shelf.stringList("pinnedBookIds")).distinct().stringArray(),
                "updatedBookIds" to (existing.stringList("updatedBookIds") + shelf.stringList("updatedBookIds")).distinct().stringArray(),
            ) else shelf.with(
                "id" to JsonPrimitive(id), "sortType" to JsonPrimitive("default"), "sortReversed" to JsonPrimitive(false),
                "autoCache" to JsonPrimitive(false), "systemUpdateReminder" to JsonPrimitive(false),
                "allBookIds" to shelf.stringList("allBookIds").distinct().stringArray(),
                "pinnedBookIds" to shelf.stringList("pinnedBookIds").distinct().stringArray(),
                "updatedBookIds" to shelf.stringList("updatedBookIds").distinct().stringArray(),
            )
            names[shelf.string("name")] = id
        }
        val oldMetadata = old.rows("bookshelfBookMetadataEntities").map { row ->
            row.with("bookShelfIds" to JsonArray(row.intList("bookShelfIds").map { JsonPrimitive(mapping.getValue(it)) }.distinct()))
        }
        val metadata = mergeKeyed(target.rows("bookshelfBookMetadataEntities"), oldMetadata, { it.string("id") }) { a, b ->
            a.with(
                "lastUpdate" to JsonPrimitive(maxOf(dateTime(a.string("lastUpdate")), dateTime(b.string("lastUpdate"))).toString()),
                "bookShelfIds" to JsonArray((a.intList("bookShelfIds") + b.intList("bookShelfIds")).distinct().map(::JsonPrimitive)),
            )
        }.map { row ->
            // Recompute reverse membership from the merged forward lists, preserving its timestamp.
            row.with("bookShelfIds" to JsonArray(result.values.filter { row.string("id") in it.stringList("allBookIds") }.map { JsonPrimitive(it.int("id")) }))
        }.toMutableList()
        val existingMetadata = metadata.map { it.string("id") }.toSet()
        result.values.flatMap { it.stringList("allBookIds") }.distinct().filterNot { it in existingMetadata }.forEach { book ->
            val info = (target.rows("bookInformationEntities") + old.rows("bookInformationEntities")).firstOrNull { it.string("id") == book }
            metadata += JsonObject(mapOf(
                "id" to JsonPrimitive(book), "lastUpdate" to (info?.get("lastUpdated") ?: JsonPrimitive(LocalDateTime.MIN.toString())),
                "bookShelfIds" to JsonArray(result.values.filter { book in it.stringList("allBookIds") }.map { JsonPrimitive(it.int("id")) }),
            ))
        }
        return result.values.toList() to metadata
    }

    private fun mergeUserData(
        target: JsonObject, old: JsonObject, reading: List<JsonObject>, volumes: List<JsonObject>, bodies: List<JsonObject>,
    ): List<JsonObject> {
        val result = target.rows("userDataEntities").associateByTo(LinkedHashMap()) { it.string("path") }
        val oldRows = old.rows("userDataEntities").associateBy { it.string("path") }
        val recentTarget = result[MigrationData.READING_BOOKS]
        val recentOld = oldRows[MigrationData.READING_BOOKS]
        if (recentTarget != null || recentOld != null) {
            val times = reading.associate { it.string("id") to dateTime(it.string("lastReadTime")) }
            // API 3 reverses the stored list for the home screen. Keep newest IDs last,
            // with the stable target/TW union order unchanged when timestamps tie.
            val ids = (recentTarget?.let { commaList(it.string("value")) }.orEmpty() + recentOld?.let { commaList(it.string("value")) }.orEmpty())
                .distinct().sortedWith(compareBy { times[it] ?: LocalDateTime.MIN })
            result[MigrationData.READING_BOOKS] = (recentTarget ?: recentOld!!).with("value" to JsonPrimitive(ids.joinToString(",")))
        }
        val targetComplete = result[MigrationData.COMPLETED_DOWNLOADS]
        val oldComplete = oldRows[MigrationData.COMPLETED_DOWNLOADS]
        if (targetComplete != null || oldComplete != null) {
            val validBodies = bodies.filter(::hasBody).map { it.string("id") }.toSet()
            val availableChapters = volumes.groupBy { it.string("bookId") }.mapValues { (_, group) -> group.flatMap { it.stringList("chapterIds") } }
            val complete = (targetComplete?.let(::downloads).orEmpty() + oldComplete?.let(::downloads).orEmpty().filter { it.first == "CACHE" })
                .distinct().filter { (type, book) -> type != "CACHE" || availableChapters[book].orEmpty().let { it.isNotEmpty() && validBodies.containsAll(it) } }
            result[MigrationData.COMPLETED_DOWNLOADS] = (targetComplete ?: oldComplete!!).with(
                "value" to JsonPrimitive(complete.joinToString(", ") { "${it.first}|${it.second}" }),
            )
        }
        result[MigrationData.SELECTED_SOURCE]?.let { row ->
            result[MigrationData.SELECTED_SOURCE] = row.with("value" to JsonPrimitive(MigrationData.CURRENT_SOURCE_ID.toString()))
        }
        return result.values.toList()
    }

    private fun downloads(row: JsonObject): List<Pair<String, String>> {
        checkData(row.string("type") == "CompletedDownloadItemList", "下载完成记录类型非法")
        return commaList(row.string("value")).map { item ->
            val pair = item.split('|')
            checkData(pair.size == 2 && pair[0] in setOf("CACHE", "EPUB_EXPORT"), "下载完成记录无效")
            bookId(pair[1]); pair[0] to pair[1]
        }
    }

    private fun normalizeProgress(map: JsonObject, maximum: Boolean): JsonObject {
        val output = LinkedHashMap<String, JsonElement>()
        val rank = mutableMapOf<String, Int>()
        map.forEach { (rawId, value) ->
            val id = normalizeChapterId(rawId); val progress = value.progress(); val priority = aliasRank(rawId)
            if (maximum) {
                if (progress > (output[id]?.progress() ?: -1.0)) output[id] = value
            } else if (priority < (rank[id] ?: Int.MAX_VALUE)) {
                output[id] = value; rank[id] = priority
            }
        }
        return JsonObject(output)
    }

    private fun normalizeAliases(rows: List<JsonObject>, normalize: (JsonObject) -> JsonObject, valid: (JsonObject) -> Boolean): List<JsonObject> {
        checkUnique(rows, "id")
        val output = LinkedHashMap<String, JsonObject>()
        val ranks = mutableMapOf<String, Int>()
        rows.forEach { raw ->
            val row = normalize(raw); val id = row.string("id"); val rank = aliasRank(raw.string("id"))
            val existing = output[id]
            if (existing == null || (valid(row) && !valid(existing)) || (valid(row) == valid(existing) && rank < ranks.getValue(id))) {
                output[id] = row; ranks[id] = rank
            }
        }
        return output.values.toList()
    }

    private fun aliasRank(id: String) = when {
        id.startsWith("linovelib-v3:") -> 0
        id.startsWith("linovelib-v2:") -> 1
        else -> 2
    }

    private fun body(row: JsonObject): JsonObject {
        val raw = row.string("content")
        val parsed = try { json.parseToJsonElement(raw) } catch (_: IllegalArgumentException) { fail("正文 JSON 无效") }
        checkData(parsed is JsonObject, "正文不是 JSON 对象")
        val components = (parsed as JsonObject)["components"]
        checkData(components == null || components is JsonArray, "正文组件列表无效")
        return parsed
    }

    private fun hasBody(row: JsonObject): Boolean = (body(row)["components"] as? JsonArray)?.isNotEmpty() == true

    private fun preferRows(a: List<JsonObject>, b: List<JsonObject>, key: String, valid: (JsonObject) -> Boolean): List<JsonObject> {
        val output = a.associateByTo(LinkedHashMap()) { it.string(key) }
        b.forEach { row ->
            val id = row.string(key); val existing = output[id]
            if (existing == null || (!valid(existing) && valid(row))) output[id] = row
        }
        return output.values.toList()
    }

    private fun mergeKeyed(a: List<JsonObject>, b: List<JsonObject>, key: (JsonObject) -> String, merge: (JsonObject, JsonObject) -> JsonObject): List<JsonObject> {
        val output = a.associateByTo(LinkedHashMap(), key)
        b.forEach { row ->
            val id = key(row)
            output[id] = output[id]?.let { merge(it, row) } ?: row
        }
        return output.values.toList()
    }

    internal fun decodeHours(value: String): IntArray {
        // Count serializes exactly 18 bytes: each hour is a six-bit big-endian minute value.
        checkData(value.length == 24 && value.all { it in base64Alphabet }, "小时统计编码无效")
        val bytes = ByteArray(18)
        for (i in 0 until 6) {
            val offset = i * 4
            val packed = (base64Alphabet.indexOf(value[offset]) shl 18) or
                (base64Alphabet.indexOf(value[offset + 1]) shl 12) or
                (base64Alphabet.indexOf(value[offset + 2]) shl 6) or base64Alphabet.indexOf(value[offset + 3])
            bytes[i * 3] = (packed ushr 16).toByte(); bytes[i * 3 + 1] = (packed ushr 8).toByte(); bytes[i * 3 + 2] = packed.toByte()
        }
        return IntArray(24) { hour ->
            var minute = 0
            for (bit in 0 until 6) {
                val index = hour * 6 + bit
                minute = (minute shl 1) or ((bytes[index / 8].toInt() ushr (7 - index % 8)) and 1)
            }
            checkData(minute <= 60, "单源小时分钟数超过 60")
            minute
        }
    }

    internal fun encodeHours(hours: IntArray): String {
        checkData(hours.size == 24 && hours.all { it in 0..60 }, "小时统计数值无效")
        val bytes = ByteArray(18)
        hours.forEachIndexed { hour, minutes ->
            for (bit in 0 until 6) {
                if ((minutes ushr (5 - bit)) and 1 == 1) {
                    val index = hour * 6 + bit
                    bytes[index / 8] = (bytes[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
                }
            }
        }
        return buildString(24) {
            for (offset in bytes.indices step 3) {
                val packed = ((bytes[offset].toInt() and 255) shl 16) or ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
                append(base64Alphabet[(packed ushr 18) and 63]); append(base64Alphabet[(packed ushr 12) and 63])
                append(base64Alphabet[(packed ushr 6) and 63]); append(base64Alphabet[packed and 63])
            }
        }
    }

    private fun JsonObject.rows(field: String): List<JsonObject> = (required(field) as? JsonArray
        ?: fail("缺少数据表：$field")).map { it as? JsonObject ?: fail("数据表包含非对象记录：$field") }
    private fun JsonObject.required(field: String): JsonElement = this[field] ?: fail("缺少字段：$field")
    private fun JsonObject.string(field: String): String {
        val value = required(field) as? JsonPrimitive ?: fail("字段不是字符串：$field")
        checkData(value.isString, "字段不是字符串：$field")
        return value.content
    }
    private fun JsonObject.int(field: String): Int {
        val value = required(field) as? JsonPrimitive ?: fail("字段不是整数：$field")
        checkData(!value.isString, "字段不是整数：$field")
        return value.intOrNull ?: fail("整数字段超出范围：$field")
    }
    private fun JsonObject.bool(field: String, default: Boolean? = null): Boolean {
        val value = this[field] ?: return default ?: fail("缺少字段：$field")
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail("字段不是布尔值：$field")
    }
    private fun JsonObject.stringList(field: String): List<String> = (required(field) as? JsonArray ?: fail("字段不是列表：$field")).map {
        val value = it as? JsonPrimitive ?: fail("列表元素不是字符串：$field")
        checkData(value.isString, "列表元素不是字符串：$field"); value.content
    }
    private fun JsonObject.intList(field: String): List<Int> = (required(field) as? JsonArray ?: fail("字段不是列表：$field")).map {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.intOrNull ?: fail("列表元素不是整数：$field")
    }
    private fun JsonObject.objectField(field: String): JsonObject = required(field) as? JsonObject ?: fail("字段不是对象：$field")
    private fun JsonElement.progress(): Double {
        val value = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail("阅读进度不是数值")
        checkData(value.isFinite() && value in 0.0..1.0, "阅读进度超出范围")
        return value
    }
    private fun JsonObject.with(vararg fields: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(fields) })
    private fun List<JsonObject>.asArray() = JsonArray(this)
    private fun List<String>.stringArray() = JsonArray(map(::JsonPrimitive))
    private fun commaList(value: String): List<String> = if (value.isBlank()) emptyList() else value.split(',').map { it.trim() }.also {
        checkData(it.all(String::isNotEmpty), "列表包含空记录")
    }
    private fun bookId(id: String) { checkData(numericId.matches(id), "不支持的书籍 ID：$id") }
    private fun nonnegative(value: Int, name: String) { checkData(value >= 0, "$name 不能为负数") }
    private fun sumInt(a: Int, b: Int, name: String): Int {
        val sum = a.toLong() + b.toLong()
        checkData(sum in 0..Int.MAX_VALUE.toLong(), "$name 超出宿主整数范围")
        return sum.toInt()
    }
    private fun date(value: String): LocalDate = try { LocalDate.parse(value) } catch (_: Exception) { fail("日期无效") }
    private fun dateTime(value: String): LocalDateTime = try { LocalDateTime.parse(value) } catch (_: Exception) { fail("阅读时间无效") }
    private fun time(value: String): LocalTime = try { LocalTime.parse(value) } catch (_: Exception) { fail("阅读时刻无效") }
    private fun checkUnique(rows: List<JsonObject>, field: String) = checkUnique(rows) { it.required(field).toString() }
    private fun checkUnique(rows: List<JsonObject>, key: (JsonObject) -> String) {
        checkData(rows.map(key).distinct().size == rows.size, "数据表包含重复主键")
    }
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        JsonNull -> JsonNull
        else -> element
    }
    private fun checkData(condition: Boolean, message: String) { if (!condition) fail(message) }
    private fun fail(message: String): Nothing = throw MigrationValidationException(message)
}
