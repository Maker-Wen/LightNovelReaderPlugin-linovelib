package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Official API 3 LocalData JSON, after the host serializer has decoded its CBOR. */
internal data class MigrationSnapshot(val sourceId: Int, val data: JsonObject)

internal data class MigrationPlan(
    val snapshot: MigrationSnapshot,
    val twFingerprint: String,
    val bookCount: Int,
    val hasRecoverableData: Boolean = true,
)

internal class MigrationValidationException(message: String) : IllegalArgumentException(message)

internal object MigrationData {
    const val CURRENT_SOURCE_ID = -1488977864
    const val TW_SOURCE_ID = 282781674
    const val READING_BOOKS = "reading_books"
    const val COMPLETED_DOWNLOADS = "completedDownloadBookList"
    const val SELECTED_SOURCE = "settings.data.web_data_source_id"

    val TABLES = listOf(
        "bookInformationEntities", "bookRecordEntities", "dailyCountEntities",
        "bookshelfEntities", "bookshelfBookMetadataEntities", "chapterContentEntities",
        "chapterInformationEntities", "formattingRuleEntities", "userDataEntities",
        "userReadingDataEntities", "volumeEntities",
    )

    fun emptySnapshot(sourceId: Int): MigrationSnapshot = MigrationSnapshot(
        sourceId,
        JsonObject(buildMap {
            put("webBookDataSourceId", JsonPrimitive(sourceId))
            TABLES.forEach { put(it, JsonArray(emptyList())) }
        }),
    )
}
