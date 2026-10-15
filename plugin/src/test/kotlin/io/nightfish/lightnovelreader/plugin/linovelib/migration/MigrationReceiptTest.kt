package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationReceiptTest {
    @Test fun absentReceiptIsAllowedAndValidReceiptIsCompleted() {
        assertEquals(MigrationReceiptStatus.Absent, migrationReceiptStatus(MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID)))
        assertEquals(MigrationReceiptStatus.Present, migrationReceiptStatus(snapshot(row(value()))))
    }

    @Test fun malformedOrDuplicateReceiptPausesMigrationInsteadOfThrowingOrCountingAgain() {
        listOf(snapshot(row("broken")), snapshot(row(value(), "Int")), snapshot(row(value()), row(value())),
            snapshot(row(value().replace("linovelib_tw_once", "unknown"))),
            snapshot(row(value().replace("282781674", "123")))).forEach {
            assertEquals(MigrationReceiptStatus.Untrusted, migrationReceiptStatus(it))
        }
    }

    private fun value() = buildJsonObject {
        put("kind", "linovelib_tw_once"); put("ruleVersion", 1)
        put("sourceId", MigrationData.TW_SOURCE_ID); put("targetId", MigrationData.CURRENT_SOURCE_ID)
        put("personalFingerprint", "a".repeat(64)); put("commitId", "test")
    }.toString()
    private fun row(value: String, type: String = "String") = buildJsonObject {
        put("path", "linovelib.migration.tw.v1.receipt"); put("group", "linovelib.migration.tw.v1")
        put("type", type); put("value", value)
    }
    private fun snapshot(vararg rows: JsonObject): MigrationSnapshot {
        val base = MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID)
        return base.copy(data = JsonObject(base.data + ("userDataEntities" to JsonArray(rows.toList()))))
    }
}
