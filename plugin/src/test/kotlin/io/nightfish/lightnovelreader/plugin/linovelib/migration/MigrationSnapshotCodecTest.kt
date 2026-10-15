package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.*
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
        assertEquals(64, input.digest.length)
    }

    @Test fun bareCborUsesSameOfficialSchema() {
        val raw = codec.read(fixture("tw-snapshot.cbor"), MigrationData.TW_SOURCE_ID)
        val zip = codec.read(fixture("tw-snapshot.zip"), MigrationData.TW_SOURCE_ID)
        assertEquals(zip.snapshot, raw.snapshot)
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

    @Test fun duplicateCborFieldsAreRejected() {
        // map(2) { "x": 1, "x": 2 } must not silently take the last value.
        assertThrows(IllegalArgumentException::class.java) {
            MigrationSnapshotCodec.decode(byteArrayOf(0xa2.toByte(), 0x61, 0x78, 0x01, 0x61, 0x78, 0x02))
        }
    }

    @Test fun excessiveNestingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationSnapshotCodec.decode(ByteArray(66) { 0x81.toByte() } + 0xf6.toByte())
        }
    }

    @Test fun minusOneSnapshotRequiresPersistedTwProvenance() {
        val raw = fixture("tw-snapshot.cbor").readBytes()
        val needle = "webBookDataSourceId".toByteArray() + 0x1a.toByte() + ByteBuffer.allocate(4).putInt(MigrationData.TW_SOURCE_ID).array()
        val index = raw.indices.first { offset -> offset + needle.size <= raw.size && raw.copyOfRange(offset, offset + needle.size).contentEquals(needle) }
        val valueOffset = index + "webBookDataSourceId".length
        val aliasBytes = raw.copyOfRange(0, valueOffset) + 0x20.toByte() + raw.copyOfRange(valueOffset + 5, raw.size)
        withTemp(aliasBytes) { file ->
            assertEquals(codec.read(fixture("tw-snapshot.zip"), MigrationData.TW_SOURCE_ID).snapshot, codec.read(file, -1).snapshot)
        }
        val selected = MigrationData.TW_SOURCE_ID.toString().toByteArray()
        val selectedIndex = aliasBytes.indices.first { offset -> offset + selected.size <= aliasBytes.size && aliasBytes.copyOfRange(offset, offset + selected.size).contentEquals(selected) }
        "123456789".toByteArray().copyInto(aliasBytes, selectedIndex)
        withTemp(aliasBytes) { file ->
            assertThrows(IllegalArgumentException::class.java) { codec.read(file, -1) }
        }
    }

    @Test fun aliasFromAnotherPersistedSourceIsExcludedWithoutChangingInput() {
        withTemp(aliasBytes("-345580097")) { file ->
            val before = MigrationSnapshotCodec.digest(file)
            assertNull(codec.readTwAlias(file))
            // A strict request to read it as TW must still reject the other source.
            assertThrows(IllegalArgumentException::class.java) { codec.read(file, -1) }
            assertEquals(before, MigrationSnapshotCodec.digest(file))
        }
    }

    @Test fun optionalAliasReadStillAcceptsTwAndResolvesItsSourceIdentity() {
        withTemp(aliasBytes(MigrationData.TW_SOURCE_ID.toString())) { file ->
            val before = MigrationSnapshotCodec.digest(file)
            val input = requireNotNull(codec.readTwAlias(file))
            assertEquals(MigrationData.TW_SOURCE_ID, input.snapshot.sourceId)
            assertEquals(MigrationData.TW_SOURCE_ID, input.snapshot.data.getValue("webBookDataSourceId").jsonPrimitive.int)
            assertEquals(codec.read(file, -1), input)
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
                assertEquals(codec.read(alias, -1), codec.readTwSource(standard, alias))
            }
            listOf(aliasBytes("-345580097"), aliasBytes(null), byteArrayOf(0xbf.toByte())).forEach { bytes ->
                withTemp(bytes) { alias -> assertNull(codec.readTwSource(standard, alias)) }
            }
        }
    }

    @Test fun missingTablesAndMalformedRowsDoNotHideOtherRecoverableData() {
        val data = JsonObject(mapOf(
            "webBookDataSourceId" to JsonPrimitive(MigrationData.TW_SOURCE_ID),
            "userReadingDataEntities" to JsonArray(listOf(JsonNull, buildJsonObject { put("id", "1") })),
        ))
        val decoded = MigrationSnapshotCodec.decode(cborBytes(data))
        assertEquals(MigrationData.TABLES.toSet() + "webBookDataSourceId", decoded.keys)
        assertEquals(1, decoded.getValue("userReadingDataEntities").jsonArray.size)
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

    private fun cborBytes(data: JsonElement): ByteArray {
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
                    head(5, element.size.toLong())
                    element.forEach { (key, value) -> encode(JsonPrimitive(key)); encode(value) }
                }
                is JsonArray -> { head(4, element.size.toLong()); element.forEach(::encode) }
                is JsonPrimitive -> if (element == JsonNull) output.write(0xf6) else if (element.isString) {
                    val bytes = element.content.toByteArray(Charsets.UTF_8)
                    head(3, bytes.size.toLong()); output.write(bytes)
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
