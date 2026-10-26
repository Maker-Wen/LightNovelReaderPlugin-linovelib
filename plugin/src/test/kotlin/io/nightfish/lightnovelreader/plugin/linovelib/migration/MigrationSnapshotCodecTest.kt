package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.LocalTime

class MigrationSnapshotCodecTest {
    private val codec = MigrationSnapshotCodec()

    @Test fun decodesZipDataProducedByOfficialHostSerializer() {
        val file = fixture("tw-snapshot.zip")
        val input = codec.read(file, MigrationData.TW_SOURCE_ID)
        val expected = Json.parseToJsonElement(fixture("tw-snapshot.json").readText()).jsonObject
        // Host temporal serializers normalize redundant :00 seconds when encoding JSON.
        assertEquals(expected, canonicalDates(input.snapshot.data))
        assertEquals(MigrationData.TW_SOURCE_ID, input.snapshot.sourceId)
    }

    @Test fun bareCborUsesSameOfficialSchema() {
        val raw = codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID)
        val zip = codec.read(fixture("tw-snapshot.zip"), MigrationData.TW_SOURCE_ID)
        assertEquals(zip.snapshot, raw.snapshot)
    }

    @Test fun definiteAndIndefiniteCollectionsPreserveTheSameSnapshot() {
        val data = Json.parseToJsonElement(fixture("tw-snapshot.json").readText()).jsonObject
        for (indefinite in listOf(false, true)) {
            assertEquals(data, MigrationSnapshotCodec.decode(cborBytes(data, indefinite)))
        }
    }

    @Test fun wrongFileIdentityIsRejectedWithoutTouchingInput() {
        val file = fixture("tw-snapshot.zip")
        val before = MigrationSnapshotCodec.digest(file)
        assertThrows(IllegalArgumentException::class.java) { codec.read(file, MigrationData.CURRENT_SOURCE_ID) }
        assertEquals(before, MigrationSnapshotCodec.digest(file))
    }

    @Test fun truncationAndTrailingBytesAreRejected() {
        val bytes = fixture("tw-snapshot.cbor").readBytes()
        assertThrows(IllegalArgumentException::class.java) { MigrationSnapshotCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { MigrationSnapshotCodec.decode(bytes + 0) }
    }

    @Test fun declaredCborStringLengthsBeyondAvailablePayloadOrIntegerRangeAreRejected() {
        listOf(MigrationSnapshotCodec.MAX_BYTES.toLong() + 1, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE).forEach { length ->
            // map(1), followed by a string key with an eight-byte length and no payload.
            val bytes = byteArrayOf(0xa1.toByte(), 0x7b) + ByteBuffer.allocate(8).putLong(length).array()
            assertThrows(IllegalArgumentException::class.java) { MigrationSnapshotCodec.decode(bytes) }
        }
    }

    @Test fun oversizedDeclaredCollectionsAreRejectedBeforeReadingTheirPayload() {
        for (major in listOf(4, 5)) {
            for (length in listOf(2_000_001L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
                val declaration = byteArrayOf(((major shl 5) or 27).toByte()) +
                    ByteBuffer.allocate(8).putLong(length).array()
                val error = assertThrows(IllegalArgumentException::class.java) {
                    MigrationSnapshotCodec.decode(snapshotWithExtraField(declaration))
                }
                // With the length check removed this reaches a truncated/invalid payload,
                // not this requirement. Merely asserting any exception would hide that.
                assertEquals("Failed requirement.", error.message)
            }
        }
    }

    @Test fun unsignedOverflowCollectionLengthsCannotBecomeEmptyCollections() {
        for (major in listOf(4, 5)) {
            for (length in listOf(Long.MIN_VALUE, -1L)) {
                // These Long bit patterns are unsigned CBOR lengths above Long.MAX_VALUE.
                // Without the unsigned check, MIN_VALUE truncates to zero and -1 would
                // be mistaken for the indefinite-collection sentinel.
                val declaration = byteArrayOf(((major shl 5) or 27).toByte()) +
                    ByteBuffer.allocate(8).putLong(length).array() +
                    if (length == -1L) byteArrayOf(0xff.toByte()) else byteArrayOf()
                val error = assertThrows(IllegalArgumentException::class.java) {
                    MigrationSnapshotCodec.decode(snapshotWithExtraField(declaration))
                }
                assertEquals("Failed requirement.", error.message)
            }
        }
    }

    @Test fun duplicateCborFieldsAreRejected() {
        val base = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).data
        val encoded = cborBytes(base, indefiniteCollections = true)
        // A complete valid snapshot plus the same ID again: accepting the second key
        // would otherwise produce a valid snapshot, so no later schema check can mask it.
        val duplicate = encoded.dropLast(1).toByteArray() +
            cborBytes(JsonPrimitive("webBookDataSourceId")) +
            cborBytes(base.getValue("webBookDataSourceId")) + 0xff.toByte()
        val error = assertThrows(IllegalArgumentException::class.java) {
            MigrationSnapshotCodec.decode(duplicate)
        }
        assertEquals("源快照含重复字段", error.message)
    }

    @Test fun excessiveNestingIsRejected() {
        val base = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).data
        // The extra field is legal and ignored after decoding; its leaf is at depth 64.
        assertEquals(base, MigrationSnapshotCodec.decode(snapshotWithExtraField(
            ByteArray(63) { 0x81.toByte() } + 0xf6.toByte()
        )))
        val error = assertThrows(IllegalArgumentException::class.java) {
            MigrationSnapshotCodec.decode(snapshotWithExtraField(
                ByteArray(64) { 0x81.toByte() } + 0xf6.toByte()
            ))
        }
        assertEquals("源快照结构过深或过大", error.message)
    }

    @Test fun minusOneSnapshotRequiresPersistedTwProvenance() {
        val raw = fixture("tw-snapshot.cbor").readBytes()
        val needle = "webBookDataSourceId".toByteArray() + 0x1a.toByte() + ByteBuffer.allocate(4).putInt(MigrationData.TW_SOURCE_ID).array()
        val index = raw.indices.first { offset -> offset + needle.size <= raw.size && raw.copyOfRange(offset, offset + needle.size).contentEquals(needle) }
        val valueOffset = index + "webBookDataSourceId".length
        val aliasBytes = raw.copyOfRange(0, valueOffset) + 0x20.toByte() + raw.copyOfRange(valueOffset + 5, raw.size)
        withTemp(aliasBytes) { file ->
            assertEquals(codec.read(fixture("tw-snapshot.zip"), MigrationData.TW_SOURCE_ID).snapshot, requireNotNull(codec.readTwAlias(file)).snapshot)
        }
        val selected = MigrationData.TW_SOURCE_ID.toString().toByteArray()
        val selectedIndex = aliasBytes.indices.first { offset -> offset + selected.size <= aliasBytes.size && aliasBytes.copyOfRange(offset, offset + selected.size).contentEquals(selected) }
        "123456789".toByteArray().copyInto(aliasBytes, selectedIndex)
        withTemp(aliasBytes) { file ->
            assertNull(codec.readTwAlias(file))
        }
    }

    @Test fun aliasFromAnotherPersistedSourceIsExcludedWithoutChangingInput() {
        withTemp(aliasBytes("-345580097")) { file ->
            val before = MigrationSnapshotCodec.digest(file)
            assertNull(codec.readTwAlias(file))
            assertEquals(before, MigrationSnapshotCodec.digest(file))
        }
    }

    @Test fun aliasReadAcceptsTwAndResolvesItsSourceIdentity() {
        withTemp(aliasBytes(MigrationData.TW_SOURCE_ID.toString())) { file ->
            val before = MigrationSnapshotCodec.digest(file)
            val input = requireNotNull(codec.readTwAlias(file))
            assertEquals(MigrationData.TW_SOURCE_ID, input.snapshot.sourceId)
            assertEquals(MigrationData.TW_SOURCE_ID, input.snapshot.data.getValue("webBookDataSourceId").jsonPrimitive.int)
            assertEquals(before, MigrationSnapshotCodec.digest(file))
        }
    }

    @Test fun uncertainAliasOwnershipStillFailsInsteadOfBeingExcluded() {
        val inputs = listOf(
            aliasBytes(null),
            aliasBytes(MigrationData.TW_SOURCE_ID.toString(), duplicate = true),
            aliasBytes("-345580097", type = "String"),
            aliasBytes("unknown"),
            aliasBytes("2147483648"),
            aliasBytes("-1"),
            aliasBytes("-345580097", encodedId = MigrationData.CURRENT_SOURCE_ID),
            byteArrayOf(0xbf.toByte()),
        )
        inputs.forEach { bytes ->
            withTemp(bytes) { file ->
                val before = MigrationSnapshotCodec.digest(file)
                assertThrows(IllegalArgumentException::class.java) { codec.readTwAlias(file) }
                assertEquals(before, MigrationSnapshotCodec.digest(file))
            }
        }
    }

    @Test fun standardTwWinsWithoutReadingBrokenOrDifferentAlias() {
        withTemp(aliasBytes(null)) { alias ->
            assertEquals(codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID),
                codec.readTwSource(fixture("tw-snapshot.cbor"), alias))
        }
        withTemp(aliasBytes(MigrationData.TW_SOURCE_ID.toString())) { alias ->
            assertEquals(codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID),
                codec.readTwSource(fixture("tw-snapshot.cbor"), alias))
        }
    }

    @Test fun unreadableStandardFallsBackOnlyToExplicitTwAlias() {
        withTemp(byteArrayOf(0xbf.toByte())) { standard ->
            withTemp(aliasBytes(MigrationData.TW_SOURCE_ID.toString())) { alias ->
                assertEquals(codec.readTwAlias(alias), codec.readTwSource(standard, alias))
            }
            listOf(aliasBytes("-345580097"), aliasBytes(null), byteArrayOf(0xbf.toByte())).forEach { bytes ->
                withTemp(bytes) { alias -> assertNull(codec.readTwSource(standard, alias)) }
            }
        }
    }

    @Test fun twMissingAnyRequiredTableIsRejectedDespiteOtherRecoverableData() {
        val base = codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID).snapshot.data
        MigrationData.TABLES.forEach { name ->
            val incomplete = JsonObject(base - name)
            val error = assertThrows(IllegalArgumentException::class.java) {
                MigrationSnapshotCodec.decode(cborBytes(incomplete))
            }
            assertEquals("源快照实体列表缺失或类型非法：$name", error.message)
        }
    }

    @Test fun twNonArrayTablesAreRejectedInsteadOfBecomingEmpty() {
        val base = codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID).snapshot.data
        MigrationData.TABLES.forEach { name ->
            listOf(JsonNull, JsonPrimitive(""), JsonObject(emptyMap())).forEach { invalid ->
                val data = JsonObject(base + (name to invalid))
                val error = assertThrows(IllegalArgumentException::class.java) {
                    MigrationSnapshotCodec.decode(cborBytes(data))
                }
                assertEquals("源快照实体列表缺失或类型非法：$name", error.message)
            }
        }
    }

    @Test fun completeTwSnapshotWithEmptyTablesRemainsValid() {
        val data = MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).data
        assertEquals(data, MigrationSnapshotCodec.decode(cborBytes(data)))
    }

    @Test fun requiredTablesDoNotRequireOfficialDefaultFieldsInsideRows() {
        val base = codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID).snapshot.data
        val records = base.getValue("bookRecordEntities").jsonArray.map { row ->
            JsonObject(row.jsonObject - "isFinished" - "isFavorited")
        }
        val shelves = base.getValue("bookshelfEntities").jsonArray.map { row ->
            JsonObject(row.jsonObject - "sortReversed")
        }
        val data = JsonObject(base + mapOf(
            "bookRecordEntities" to JsonArray(records),
            "bookshelfEntities" to JsonArray(shelves),
        ))
        val decoded = MigrationSnapshotCodec.decode(cborBytes(data))
        assertEquals(data, decoded)
        val normalized = MigrationNormalizer.tw(MigrationSnapshot(MigrationData.TW_SOURCE_ID, decoded)).data
        assertEquals(records.size, normalized.getValue("bookRecordEntities").jsonArray.size)
        normalized.getValue("bookRecordEntities").jsonArray.forEach { row ->
            assertFalse(row.jsonObject.containsKey("isFinished"))
            assertFalse(row.jsonObject.containsKey("isFavorited"))
        }
        val merged = MigrationPlanner.plan(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID),
            MigrationSnapshot(MigrationData.TW_SOURCE_ID, decoded)).snapshot.data
        assertEquals(JsonPrimitive(false), merged.getValue("bookshelfEntities").jsonArray.single().jsonObject["sortReversed"])
    }

    @Test fun twDecodeFiltersNonObjectRowsAndIgnoresUnknownTopLevelFields() {
        val data = JsonObject(MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).data + mapOf(
            "userReadingDataEntities" to JsonArray(listOf(JsonNull, buildJsonObject { put("id", "1") })),
            "unknownTable" to JsonArray(emptyList()),
        ))
        val decoded = MigrationSnapshotCodec.decode(cborBytes(data))
        assertEquals(MigrationData.TABLES.toSet() + "webBookDataSourceId", decoded.keys)
        assertEquals(JsonArray(listOf(buildJsonObject { put("id", "1") })), decoded.getValue("userReadingDataEntities"))
        assertEquals(0, decoded.getValue("chapterContentEntities").jsonArray.size)
    }

    @Test fun nonfiniteFieldBecomesUnavailableWithoutLosingTheSnapshot() {
        val row = buildJsonObject { put("id", "1"); put("readingProgress", Float.NaN) }
        val data = JsonObject(MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).data +
            ("userReadingDataEntities" to JsonArray(listOf(row))))
        val decoded = MigrationSnapshotCodec.decode(cborBytes(data))
        assertEquals(JsonNull, decoded.getValue("userReadingDataEntities").jsonArray.single().jsonObject["readingProgress"])
    }

    @Test fun inactiveCurrentCannotSilentlyDropUnsupportedOriginalRowsOrTables() {
        val base = MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID).data
        listOf(
            JsonObject(base - "userReadingDataEntities"),
            JsonObject(base + ("userReadingDataEntities" to JsonArray(listOf(JsonNull)))),
            JsonObject(base + ("userReadingDataEntities" to JsonArray(listOf(buildJsonObject {
                put("id", "1"); put("readingProgress", Float.NaN)
            })))),
        ).forEach { data ->
            assertThrows(IllegalArgumentException::class.java) { MigrationSnapshotCodec.decode(cborBytes(data)) }
        }
    }

    /** Minimal official-schema CBOR, containing only source ownership and empty tables. */
    private fun aliasBytes(selected: String?, type: String = "Int", duplicate: Boolean = false, encodedId: Int = -1): ByteArray {
        val rows = if (selected == null) emptyList() else listOf(buildJsonObject {
            put("path", MigrationSnapshotCodec.SOURCE_PATH)
            put("group", "settings.data")
            put("type", type)
            put("value", selected)
        }).let { if (duplicate) it + it else it }
        val data = JsonObject(MigrationData.emptySnapshot(encodedId).data + ("userDataEntities" to JsonArray(rows)))
        return cborBytes(data)
    }

    private fun snapshotWithExtraField(value: ByteArray): ByteArray {
        val base = cborBytes(MigrationData.emptySnapshot(MigrationData.TW_SOURCE_ID).data,
            indefiniteCollections = true)
        return base.dropLast(1).toByteArray() + cborBytes(JsonPrimitive("extra")) + value + 0xff.toByte()
    }

    private fun cborBytes(data: JsonElement, indefiniteCollections: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        fun head(major: Int, value: Long) {
            when {
                value < 24 -> output.write((major shl 5) or value.toInt())
                value <= 255 -> { output.write((major shl 5) or 24); output.write(value.toInt()) }
                value <= 65535 -> {
                    output.write((major shl 5) or 25)
                    output.write(ByteBuffer.allocate(2).putShort(value.toShort()).array())
                }
                else -> {
                    output.write((major shl 5) or 26)
                    output.write(ByteBuffer.allocate(4).putInt(value.toInt()).array())
                }
            }
        }
        fun encode(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if (indefiniteCollections) output.write(0xbf) else head(5, element.size.toLong())
                    element.forEach { (key, value) -> encode(JsonPrimitive(key)); encode(value) }
                    if (indefiniteCollections) output.write(0xff)
                }
                is JsonArray -> {
                    if (indefiniteCollections) output.write(0x9f) else head(4, element.size.toLong())
                    element.forEach(::encode)
                    if (indefiniteCollections) output.write(0xff)
                }
                is JsonPrimitive -> if (element == JsonNull) output.write(0xf6) else if (element.isString) {
                    val bytes = element.content.toByteArray(Charsets.UTF_8)
                    head(3, bytes.size.toLong()); output.write(bytes)
                } else if (element.booleanOrNull != null) {
                    output.write(if (element.boolean) 0xf5 else 0xf4)
                } else {
                    val number = element.longOrNull
                    if (number == null) {
                        output.write(0xfb)
                        output.write(ByteBuffer.allocate(8).putDouble(element.double).array())
                    } else head(if (number < 0) 1 else 0, if (number < 0) -1 - number else number)
                }
            }
        }
        encode(data)
        return output.toByteArray()
    }

    private inline fun withTemp(bytes: ByteArray, body: (File) -> Unit) {
        val file = File.createTempFile("linovelib-codec", ".cbor")
        try { file.writeBytes(bytes); body(file) } finally { file.delete() }
    }

    private fun canonicalDates(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (name, element) ->
            when (name) {
                "lastUpdated", "lastUpdate", "lastReadTime" -> JsonPrimitive(LocalDateTime.parse(element.jsonPrimitive.content).toString())
                "firstSeen", "lastSeen" -> JsonPrimitive(LocalTime.parse(element.jsonPrimitive.content).toString())
                else -> canonicalDates(element)
            }
        })
        is JsonArray -> JsonArray(value.map(::canonicalDates))
        else -> value
    }

    private fun fixture(name: String): File = File(requireNotNull(javaClass.getResource("/migration/$name")).toURI())
}
