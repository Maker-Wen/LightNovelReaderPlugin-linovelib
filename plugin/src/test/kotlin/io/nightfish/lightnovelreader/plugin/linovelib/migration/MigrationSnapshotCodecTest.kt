package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
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
