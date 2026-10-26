package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.LocalTime

/** Pure local merge. The host adapter owns transactions and the once-only receipt. */
internal object MigrationPlanner {
    private val json = Json
    private val chapterIdPattern = Regex("linovelib-v3:[1-9][0-9]*")
    private val base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun plan(
        current: MigrationSnapshot,
        tw: MigrationSnapshot,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): MigrationPlan {
        val targetState = MigrationNormalizer.current(current)
        val oldState = MigrationNormalizer.tw(tw)
        val target = targetState.data
        val old = excludeProtectedIncoming(oldState.data, targetState)
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
        val chapterOwners = targetVolumes.flatMap { (book, volumes) ->
            volumes.flatMap { it.stringList("chapterIds") }.map { it to book }
        }.toMap().toMutableMap()
        val volumeOwners = targetVolumes.flatMap { (book, volumes) -> volumes.map { it.string("volumeId") to book } }
            .toMap().toMutableMap()
        books.forEachIndexed { index, book ->
            val availableTarget = targetVolumes[book].orEmpty().sortedBy { it.int("index") }
            val useTarget = availableTarget.sumOf { it.stringList("chapterIds").size } > 0
            val selected = if (useTarget) availableTarget else {
                val incoming = oldVolumes[book].orEmpty().sortedBy { it.int("index") }
                val incomingConflicts = incoming.any { volume ->
                    volumeOwners[volume.string("volumeId")]?.let { it != book } == true ||
                        volume.stringList("chapterIds").any { id -> chapterOwners[id]?.let { it != book } == true }
                }
                incoming.takeUnless { incomingConflicts }.orEmpty()
            }
            val chapterMap = if (useTarget) targetChapters else oldChapters
            chosenVolumes += selected
            var count = 0
            selected.forEach { volume ->
                volume.stringList("chapterIds").forEach { chapterId ->
                    chapterOwners[chapterId] = book
                    chosenChapters[chapterId] = chapterMap.getValue(chapterId)
                    count++
                }
            }
            selected.forEach { volumeOwners[it.string("volumeId")] = book }
            catalogSizes[book] = count
            onProgress(index + 1, books.size)
        }
        output["volumeEntities"] = chosenVolumes.asArray()
        // Preserve standalone chapter metadata too. Chosen whole catalogs override its title.
        val chapters = LinkedHashMap(targetChapters)
        oldChapters.forEach { (id, row) -> if (id !in chapters) chapters[id] = row }
        chapters.putAll(chosenChapters)
        output["chapterInformationEntities"] = chapters.values.toList().asArray()
        val bodies = preferRows(target.rows("chapterContentEntities"), old.rows("chapterContentEntities"), "id", ::hasBody)
        output["chapterContentEntities"] = bodies.asArray()
        val completeCacheBooks = if (sequenceOf(target, old).any { data ->
            data.rows("userDataEntities").any { it.string("path") == MigrationData.COMPLETED_DOWNLOADS }
        }) {
            val validBodies = bodies.filter(::hasBody).map { it.string("id") }.toSet()
            chosenVolumes.groupBy { it.string("bookId") }
                .filterValues { group ->
                    val ids = group.flatMap { it.stringList("chapterIds") }
                    ids.isNotEmpty() && validBodies.containsAll(ids)
                }.keys
        } else emptySet()

        val reservedShelfIds = targetState.preservedPersonalRows["bookshelfEntities"].orEmpty()
            .mapNotNull { (it["id"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull }.toSet()
        val shelfResult = mergeShelves(target, old, reservedShelfIds)
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
            val count = catalogSizes.getValue(row.string("id"))
            if (count == 0) row else {
                val sum = row.objectField("maxChapterReadingProgressMap").values.sumOf { it.progress() }
                row.with("readingProgress" to JsonPrimitive((sum / count).coerceIn(0.0, 1.0).toFloat()))
            }
        }
        output["userReadingDataEntities"] = reading.asArray()
        output["userDataEntities"] = mergeUserData(target, old, reading, completeCacheBooks).asArray()
        targetState.preservedPersonalRows.forEach { (table, preserved) ->
            val protected = targetState.protectedKeys.getValue(table)
            val merged = (output[table] as JsonArray).map { it as JsonObject }
                .filterNot { MigrationNormalizer.personalKey(table, it) in protected }
            output[table] = (merged + preserved).asArray()
        }
        return MigrationPlan(MigrationSnapshot(MigrationData.CURRENT_SOURCE_ID, JsonObject(output)), personalFingerprint(tw), books.size,
            MigrationNormalizer.containsRecoverableData(withRecoverableDownloads(old, completeCacheBooks)))
    }

    fun hasRecoverableData(snapshot: MigrationSnapshot): Boolean =
        MigrationNormalizer.containsRecoverableData(MigrationNormalizer.tw(snapshot).data)

    /** Excludes disposable caches and preferences; ordering does not affect identity. */
    fun personalFingerprint(snapshot: MigrationSnapshot): String {
        val fields = listOf("bookshelfEntities", "bookshelfBookMetadataEntities", "bookRecordEntities",
            "dailyCountEntities", "userReadingDataEntities")
        val personal = JsonObject(buildMap {
            fields.forEach { field ->
                val rows = (snapshot.data.getValue(field) as JsonArray)
                    .filterNot { field == "userReadingDataEntities" && (it as JsonObject)["id"] == JsonPrimitive("") }
                put(field, JsonArray(rows.map(::canonical).sortedBy(JsonElement::toString)))
            }
            put("userDataEntities", JsonArray((snapshot.data.getValue("userDataEntities") as JsonArray)
                .filter { row ->
                    val path = ((row as JsonObject)["path"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    path == null || path in setOf(MigrationData.READING_BOOKS, MigrationData.COMPLETED_DOWNLOADS)
                }
                .map(::canonical).sortedBy(JsonElement::toString)))
        })
        return MessageDigest.getInstance("SHA-256").digest(personal.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun normalizeChapterId(id: String, allowEmpty: Boolean = false): String {
        if (allowEmpty && id.isBlank()) return ""
        checkData(chapterIdPattern.matches(id), "不支持的章节 ID：$id")
        return id
    }

    private fun excludeProtectedIncoming(data: JsonObject, target: NormalizedMigrationSnapshot): JsonObject {
        val output = data.toMutableMap()
        target.protectedKeys.forEach { (table, protected) ->
            output[table] = data.rows(table).filterNot { row ->
                MigrationNormalizer.ALL_KEYS in protected || MigrationNormalizer.personalKey(table, row) in protected
            }.asArray()
        }
        val preservedNames = target.preservedPersonalRows["bookshelfEntities"].orEmpty().mapNotNull {
            (it["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        }.toSet()
        output["bookshelfEntities"] = (output["bookshelfEntities"] as JsonArray).map { it as JsonObject }
            .filterNot { it.string("name") in preservedNames }.asArray()
        return JsonObject(output)
    }

    private fun withRecoverableDownloads(data: JsonObject, completeCacheBooks: Set<String>): JsonObject {
        val userData = data.rows("userDataEntities").map { row ->
            if (row.string("path") != MigrationData.COMPLETED_DOWNLOADS) row else {
                val recoverable = downloads(row).filter { (type, book) ->
                    type == "CACHE" && book in completeCacheBooks
                }
                row.with("value" to JsonPrimitive(recoverable.joinToString(", ") { "${it.first}|${it.second}" }))
            }
        }
        return data.with("userDataEntities" to userData.asArray())
    }

    private fun mergeRecord(a: JsonObject, b: JsonObject): JsonObject = a.with(
        "reads" to JsonPrimitive(sumIntOrCurrent(a.int("reads"), b.int("reads"))),
        "seconds" to JsonPrimitive(sumIntOrCurrent(a.int("seconds"), b.int("seconds"))),
        "isFinished" to JsonPrimitive(a.bool("isFinished", false) || b.bool("isFinished", false)),
        "isFavorited" to JsonPrimitive(a.bool("isFavorited", false) || b.bool("isFavorited", false)),
        "firstSeen" to JsonPrimitive(minOf(time(a.string("firstSeen")), time(b.string("firstSeen"))).toString()),
        "lastSeen" to JsonPrimitive(maxOf(time(a.string("lastSeen")), time(b.string("lastSeen"))).toString()),
    )

    private fun mergeReading(a: JsonObject, b: JsonObject): JsonObject {
        val hasTargetPosition = a.string("lastReadChapterId").isNotEmpty()
        val leftTime = a.int("totalReadTime"); val rightTime = b.int("totalReadTime")
        val total = if (leftTime == -1 && rightTime == -1) -1 else sumIntOrCurrent(leftTime.coerceAtLeast(0), rightTime.coerceAtLeast(0))
        val currentProgress = LinkedHashMap(b.objectField("currentChapterReadingProgressMap"))
        currentProgress.putAll(a.objectField("currentChapterReadingProgressMap"))
        val maximum = LinkedHashMap(a.objectField("maxChapterReadingProgressMap"))
        b.objectField("maxChapterReadingProgressMap").forEach { (id, value) ->
            if (value.progress() > (maximum[id]?.progress() ?: -1.0)) maximum[id] = value
        }
        return a.with(
            "lastReadTime" to JsonPrimitive(maxOf(dateTime(a.string("lastReadTime")), dateTime(b.string("lastReadTime"))).toString()),
            "totalReadTime" to JsonPrimitive(total),
            "lastReadChapterId" to (if (hasTargetPosition) a else b).getValue("lastReadChapterId"),
            "lastReadChapterTitle" to (if (hasTargetPosition) a else b).getValue("lastReadChapterTitle"),
            "currentChapterReadingProgressMap" to JsonObject(currentProgress),
            "maxChapterReadingProgressMap" to JsonObject(maximum),
        )
    }

    private fun mergeShelves(target: JsonObject, old: JsonObject, reservedIds: Set<Int>): Pair<List<JsonObject>, List<JsonObject>> {
        val result = target.rows("bookshelfEntities").associateByTo(LinkedHashMap()) { it.int("id") }
        val names = result.values.groupBy { it.string("name") }.mapValues { (_, value) -> value.first().int("id") }.toMutableMap()
        var nextId = 0
        old.rows("bookshelfEntities").sortedBy { it.int("id") }.forEach { shelf ->
            val id = names[shelf.string("name")] ?: shelf.int("id").takeUnless { it in result || it in reservedIds } ?: run {
                while (nextId in result || nextId in reservedIds) {
                    checkData(nextId < Int.MAX_VALUE, "无法分配书架 ID"); nextId++
                }
                nextId
            }
            val existing = result[id]
            result[id] = if (existing != null) existing.with(
                "allBookIds" to (existing.stringList("allBookIds") + shelf.stringList("allBookIds")).distinct().stringArray(),
                "pinnedBookIds" to (existing.stringList("pinnedBookIds") + shelf.stringList("pinnedBookIds")).distinct().stringArray(),
                "updatedBookIds" to (existing.stringList("updatedBookIds") + shelf.stringList("updatedBookIds")).distinct().stringArray(),
            ) else shelf.with(
                "id" to JsonPrimitive(id), "sortType" to JsonPrimitive("default"), "sortReversed" to JsonPrimitive(false),
                "autoCache" to JsonPrimitive(false), "systemUpdateReminder" to JsonPrimitive(false),
            )
            names[shelf.string("name")] = id
        }
        val metadata = mergeKeyed(target.rows("bookshelfBookMetadataEntities"), old.rows("bookshelfBookMetadataEntities"), { it.string("id") }) { a, b ->
            a.with(
                "lastUpdate" to JsonPrimitive(maxOf(dateTime(a.string("lastUpdate")), dateTime(b.string("lastUpdate"))).toString()),
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
        target: JsonObject, old: JsonObject, reading: List<JsonObject>, completeCacheBooks: Set<String>,
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
            val complete = (targetComplete?.let(::downloads).orEmpty() + oldComplete?.let(::downloads).orEmpty().filter { it.first == "CACHE" })
                .distinct().filter { (type, book) -> type != "CACHE" || book in completeCacheBooks }
            result[MigrationData.COMPLETED_DOWNLOADS] = (targetComplete ?: oldComplete!!).with(
                "value" to JsonPrimitive(complete.joinToString(", ") { "${it.first}|${it.second}" }),
            )
        }
        return result.values.toList()
    }

    private fun downloads(row: JsonObject): List<Pair<String, String>> = commaList(row.string("value")).map { item ->
        val pair = item.split('|')
        pair[0] to pair[1]
    }

    private fun body(row: JsonObject): JsonObject = json.parseToJsonElement(row.string("content")) as JsonObject

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

    // Merge helpers only see normalized rows. Uninterpretable current personal rows are
    // restored after merging; their raw fields never pass through these accessors.
    private fun JsonObject.rows(field: String): List<JsonObject> = (getValue(field) as JsonArray).map { it as JsonObject }
    private fun JsonObject.string(field: String): String = (getValue(field) as JsonPrimitive).content
    private fun JsonObject.int(field: String): Int = (getValue(field) as JsonPrimitive).int
    private fun JsonObject.bool(field: String, default: Boolean): Boolean = (this[field] as JsonPrimitive?)?.boolean ?: default
    private fun JsonObject.stringList(field: String): List<String> = (getValue(field) as JsonArray).map { (it as JsonPrimitive).content }
    private fun JsonObject.objectField(field: String): JsonObject = getValue(field) as JsonObject
    private fun JsonElement.progress(): Double = (this as JsonPrimitive).double
    private fun JsonObject.with(vararg fields: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(fields) })
    private fun List<JsonObject>.asArray() = JsonArray(this)
    private fun List<String>.stringArray() = JsonArray(map(::JsonPrimitive))
    private fun commaList(value: String): List<String> = if (value.isBlank()) emptyList() else value.split(',').map(String::trim)
    private fun sumIntOrCurrent(a: Int, b: Int): Int {
        val sum = a.toLong() + b.toLong()
        return if (sum <= Int.MAX_VALUE.toLong()) sum.toInt() else a
    }
    private fun dateTime(value: String): LocalDateTime = LocalDateTime.parse(value)
    private fun time(value: String): LocalTime = LocalTime.parse(value)
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }
    private fun checkData(condition: Boolean, message: String) { if (!condition) fail(message) }
    private fun fail(message: String): Nothing = throw MigrationValidationException(message)
}
