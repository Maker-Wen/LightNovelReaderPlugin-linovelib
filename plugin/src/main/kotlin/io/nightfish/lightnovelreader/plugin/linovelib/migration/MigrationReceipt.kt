package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.*

internal enum class MigrationReceiptStatus { Absent, Present, Untrusted }

internal fun migrationReceiptStatus(snapshot: MigrationSnapshot): MigrationReceiptStatus = try {
    inspectMigrationReceipt(snapshot)
} catch (_: Exception) {
    MigrationReceiptStatus.Untrusted
}

private fun inspectMigrationReceipt(snapshot: MigrationSnapshot): MigrationReceiptStatus {
    val rows = snapshot.data.getValue("userDataEntities").jsonArray.map { it.jsonObject }.filter {
        it["path"] == JsonPrimitive("linovelib.migration.tw.v1.receipt")
    }
    require(rows.size <= 1)
    if (rows.isEmpty()) return MigrationReceiptStatus.Absent
    val row = rows.single()
    require(row["type"] == JsonPrimitive("String"))
    val value = Json.parseToJsonElement(row.getValue("value").jsonPrimitive.content).jsonObject
    require(value["kind"]?.jsonPrimitive?.content == "linovelib_tw_once" &&
        value["ruleVersion"]?.jsonPrimitive?.int == 1 &&
        value["sourceId"]?.jsonPrimitive?.int == MigrationData.TW_SOURCE_ID &&
        value["targetId"]?.jsonPrimitive?.int == MigrationData.CURRENT_SOURCE_ID &&
        value["personalFingerprint"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) == true &&
        !value["commitId"]?.jsonPrimitive?.content.isNullOrBlank())
    return MigrationReceiptStatus.Present
}
