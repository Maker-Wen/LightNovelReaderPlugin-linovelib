package io.nightfish.lightnovelreader.plugin.linovelib.migration

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Base64
import android.util.Log
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.UUID

internal enum class MigrationTransactionExit { NotStarted, RolledBack, Committed, Unknown }
internal enum class MigrationCompletion { Present, Absent, Unknown }

internal fun migrationResultAfterFailure(
    exit: MigrationTransactionExit,
    completion: MigrationCompletion,
    failure: MigrationAttemptResult.NotCommitted,
): MigrationAttemptResult = when {
    exit == MigrationTransactionExit.Committed -> MigrationAttemptResult.Completed
    exit == MigrationTransactionExit.NotStarted || exit == MigrationTransactionExit.RolledBack -> failure
    completion == MigrationCompletion.Present -> MigrationAttemptResult.Completed
    else -> MigrationAttemptResult.RestartRequired
}

/** All host-private access is kept here. This class never opens or copies a database file. */
internal class Api3HostAdapter(
    private val context: Context,
    private val userData: UserDataRepositoryApi,
) : MigrationHost {
    private data class Preparation(
        val activeSourceId: Int,
        val current: MigrationSnapshot,
        val tw: MigrationSnapshot,
        val snapshotStamps: Map<File, String?>,
    )

    private sealed interface Prepared {
        data class Ready(val input: Preparation) : Prepared
        data class Finished(val result: MigrationAttemptResult) : Prepared
    }

    private class InputChanged : IllegalStateException()
    private class SourceChanged : IllegalStateException()
    private val codec = MigrationSnapshotCodec()
    private val planner = MigrationPlanner
    private lateinit var database: SQLiteDatabase
    private lateinit var room: Any
    private lateinit var localDataManager: Any
    private lateinit var sourcePaths: Set<String>

    /** Enable writes only when the host exposes the verified shared API 3 storage contract. */
    private fun initialize(): Boolean {
        if (context.packageName != HOST_PACKAGE) return false
        // Read the host field: linking the API const directly would inline the plugin SDK's 3.
        val apiVersion = runCatching {
            Class.forName("io.nightfish.lightnovelreader.api.ApiMetadata", false, context.classLoader)
                .getField("API_VERSION").getInt(null)
        }.getOrDefault(-1)
        if (!supportsHost(context.packageName, apiVersion)) return false
        val dao = field(userData, "userDataDao")
        room = fields(dao.javaClass).mapNotNull { f -> f.getAccessible(dao) }.single {
            inherits(it.javaClass, HOST + "data.local.room.LightNovelReaderDatabase")
        }
        val helper = room.javaClass.getMethod("getOpenHelper").invoke(room)
        // R8 renames the framework helper methods; select the precise database-returning contract.
        val wrappers = helper.javaClass.methods.filter { method ->
            method.parameterCount == 0 && method.returnType != Void.TYPE && method.returnType.interfaces.isNotEmpty() &&
                method.returnType.declaredFields.any { it.type == SQLiteDatabase::class.java }
        }
        val supportDb = checkNotNull(when {
            wrappers.size == 1 -> wrappers.single().invoke(helper)
            else -> {
                // The declared return type is the shared SupportSQLiteDatabase interface.
                val writable = helper.javaClass.methods.filter { it.parameterCount == 0 && it.name in setOf("getWritableDatabase", "u") }
                require(writable.size == 1) { "无法识别宿主 Room 连接" }
                writable.single().invoke(helper)
            }
        })
        database = fields(supportDb.javaClass).filter { it.type == SQLiteDatabase::class.java }
            .map { it.getAccessible(supportDb) as SQLiteDatabase }.single()
        require(database.isOpen && !database.isReadOnly && database.version == 17) { "不支持的宿主数据库" }
        require(File(database.path).canonicalFile == context.getDatabasePath("light_novel_reader_database").canonicalFile) {
            "宿主连接身份不一致"
        }
        localDataManager = actualLocalDataManager()
        val managerDao = field(localDataManager, "userDataDao")
        require(managerDao === dao) { "宿主源数据管理器不是注入图中的实例" }
        localDataManager.javaClass.getMethod("registerWebDataSourceUserData", String::class.java)
            .invoke(localDataManager, RECEIPT_PATH)
        @Suppress("UNCHECKED_CAST")
        sourcePaths = (localDataManager.javaClass.getMethod("getWebDataSourceUserDataPathSet").invoke(localDataManager) as Set<String>).toSet()
        require(RECEIPT_PATH in sourcePaths && SOURCE_PATH in sourcePaths)
        validateSchema()
        return true
    }

    override fun inspectOnLoad(): MigrationLoadResult = try {
        if (!initialize()) MigrationLoadResult.Disabled else inTransaction {
            val source = selectedSource()
            when {
                source !in setOf(MigrationData.TW_SOURCE_ID, MigrationData.CURRENT_SOURCE_ID) -> MigrationLoadResult.Disabled
                source == MigrationData.CURRENT_SOURCE_ID && activeReceipt() != null -> MigrationLoadResult.Disabled
                source == MigrationData.TW_SOURCE_ID || snapshotFile(MigrationData.TW_SOURCE_ID).isFile || snapshotFile(-1).isFile ->
                    MigrationLoadResult.Candidate
                else -> MigrationLoadResult.Disabled
            }
        }
    } catch (failure: Exception) {
        Log.w("LinovelibMigration", "Migration adapter unavailable; no migration writes enabled", failure)
        MigrationLoadResult.Disabled
    }

    override fun executeAttempt(admissionCheck: () -> Unit, onProgress: (MigrationProgress) -> Unit): MigrationAttemptResult {
        val prepared = try {
            assertStorageIdentity()
            check(!database.inTransaction()) { "迁入线程已有未结算事务" }
            onProgress(MigrationProgress(MigrationStage.Reading))
            prepare()
        } catch (failure: Exception) {
            Log.e("LinovelibMigration", "Migration preflight did not complete", failure)
            return notCommitted(failure)
        }
        if (prepared is Prepared.Finished) return prepared.result
        return commit((prepared as Prepared.Ready).input, admissionCheck, onProgress)
    }

    private fun prepare(): Prepared {
        val source = selectedSource()
        if (source !in setOf(MigrationData.TW_SOURCE_ID, MigrationData.CURRENT_SOURCE_ID)) {
            return Prepared.Finished(MigrationAttemptResult.NoWork(MigrationReason.SourceChanged))
        }
        val active = inTransaction {
            if (selectedSource() != source) throw SourceChanged()
            readActive(source)
        }
        if (source == MigrationData.CURRENT_SOURCE_ID && receipt(active) != null) {
            return Prepared.Finished(MigrationAttemptResult.Completed)
        }
        val stamps = listOf(MigrationData.TW_SOURCE_ID, MigrationData.CURRENT_SOURCE_ID, -1).associate { id ->
            snapshotFile(id).let { it to if (it.isFile) MigrationSnapshotCodec.digest(it) else null }
        }
        val inactiveId = if (source == MigrationData.CURRENT_SOURCE_ID) MigrationData.TW_SOURCE_ID else MigrationData.CURRENT_SOURCE_ID
        val inactiveFile = snapshotFile(inactiveId)
        val inactive = if (inactiveFile.exists()) codec.read(inactiveFile, inactiveId).snapshot else MigrationData.emptySnapshot(inactiveId)
        if (inactiveId == MigrationData.CURRENT_SOURCE_ID && receipt(inactive) != null) {
            return Prepared.Finished(MigrationAttemptResult.NoWork(MigrationReason.TargetAlreadyMigratedInactive))
        }
        val current = if (source == MigrationData.CURRENT_SOURCE_ID) active else inactive
        var tw = if (source == MigrationData.TW_SOURCE_ID) active else inactive
        if (source == MigrationData.CURRENT_SOURCE_ID && snapshotFile(-1).exists()) {
            val alias = codec.read(snapshotFile(-1), -1).snapshot
            if (inactiveFile.exists()) require(planner.personalFingerprint(tw) == planner.personalFingerprint(alias)) {
                "TW 与 -1 快照无法证明属于同一份个人数据"
            } else tw = alias
        }
        if (MigrationData.TABLES.all { tw.data.getValue(it).jsonArray.isEmpty() }) {
            return Prepared.Finished(MigrationAttemptResult.NoWork(MigrationReason.NoData))
        }
        planner.validate(current)
        planner.validate(tw)
        planner.plan(current, tw)
        checkSnapshotStamps(stamps)
        return Prepared.Ready(Preparation(source, current, tw, stamps))
    }

    /** The caller reads source and receipt within the same transaction. */
    private fun activeReceipt(): JsonObject? {
        val row = database.rawQuery("SELECT path,`group`,type,value FROM user_data WHERE path=?", arrayOf(RECEIPT_PATH)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            JsonObject(listOf("path", "group", "type", "value").associateWith { name ->
                JsonPrimitive(cursor.getString(cursor.getColumnIndexOrThrow(name)))
            })
        }
        val empty = MigrationData.emptySnapshot(MigrationData.CURRENT_SOURCE_ID)
        return receipt(MigrationSnapshot(empty.sourceId, JsonObject(empty.data + ("userDataEntities" to JsonArray(listOf(row))))))
    }

    private fun commit(prepared: Preparation, admissionCheck: () -> Unit, onProgress: (MigrationProgress) -> Unit): MigrationAttemptResult {
        var began = false
        var markedSuccessful = false
        var ended = false
        var failure: Exception? = null
        try {
            assertStorageIdentity()
            check(!database.inTransaction()) { "迁入线程已有未结算事务" }
            admissionCheck()
            database.beginTransaction()
            began = true
            val source = selectedSource()
            if (source == MigrationData.CURRENT_SOURCE_ID && activeReceipt() != null) {
                // No second write when a receipt was committed since preflight.
                database.setTransactionSuccessful()
                markedSuccessful = true
            } else {
                if (source != prepared.activeSourceId) throw SourceChanged()
                val active = readActive(prepared.activeSourceId)
                checkSnapshotStamps(prepared.snapshotStamps)
                // Re-read live activity data under the write transaction; never commit the stale preflight copy.
                val current = if (prepared.activeSourceId == MigrationData.CURRENT_SOURCE_ID) active else prepared.current
                val tw = if (prepared.activeSourceId == MigrationData.TW_SOURCE_ID) active else prepared.tw
                val plan = planner.plan(current, tw) { done, count ->
                    onProgress(MigrationProgress(if (count > 0 && done == count) MigrationStage.Saving else MigrationStage.Merging, done, count))
                }
                onProgress(MigrationProgress(MigrationStage.Saving, plan.bookCount, plan.bookCount))
                writeSnapshot(plan.snapshot)
                writeUserData(SOURCE_PATH, "Int", MigrationData.CURRENT_SOURCE_ID.toString())
                writeUserData(RECEIPT_PATH, "String", buildJsonObject {
                    put("kind", "linovelib_tw_once")
                    put("ruleVersion", 1)
                    put("sourceId", MigrationData.TW_SOURCE_ID)
                    put("targetId", MigrationData.CURRENT_SOURCE_ID)
                    put("personalFingerprint", plan.twFingerprint)
                    put("commitId", UUID.randomUUID().toString())
                }.toString())
                // Never wait for the main thread while holding the transaction. No callbacks or
                // extra business work may be inserted between this check and marking success.
                admissionCheck()
                database.setTransactionSuccessful()
                markedSuccessful = true
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (began) {
                try {
                    database.endTransaction()
                    ended = true
                } catch (error: Exception) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
        }
        val error = failure ?: return MigrationAttemptResult.Completed
        Log.e("LinovelibMigration", "Migration transaction did not return normally", error)
        val exit = when {
            !began -> MigrationTransactionExit.NotStarted
            !ended -> MigrationTransactionExit.Unknown
            markedSuccessful -> MigrationTransactionExit.Committed
            else -> MigrationTransactionExit.RolledBack
        }
        // Never inspect our own uncommitted receipt. Unknown exit remains non-retryable even
        // when an independent, stable lookup reports no receipt.
        val completion = if (exit == MigrationTransactionExit.Unknown) {
            onProgress(MigrationProgress(MigrationStage.Verifying))
            inspectCompletion(prepared.activeSourceId)
        } else MigrationCompletion.Unknown
        return migrationResultAfterFailure(exit, completion, notCommitted(error))
    }

    private fun inspectCompletion(originalSource: Int): MigrationCompletion = try {
        assertStorageIdentity()
        if (database.inTransaction()) MigrationCompletion.Unknown else inTransaction {
            val source = selectedSource()
            when {
                source == MigrationData.CURRENT_SOURCE_ID && activeReceipt() != null -> MigrationCompletion.Present
                source == originalSource && activeReceipt() == null -> MigrationCompletion.Absent
                else -> MigrationCompletion.Unknown
            }
        }
    } catch (failure: Exception) {
        Log.w("LinovelibMigration", "Migration completion could not be verified", failure)
        MigrationCompletion.Unknown
    }

    private fun notCommitted(failure: Exception): MigrationAttemptResult.NotCommitted = when (failure) {
        is MigrationAdmissionException -> MigrationAttemptResult.NotCommitted(failure.reason, failure.retryable)
        is InputChanged -> MigrationAttemptResult.NotCommitted(MigrationReason.InputChanged, true)
        is SourceChanged -> MigrationAttemptResult.NotCommitted(MigrationReason.SourceChanged, false)
        is SQLiteDatabaseCorruptException -> MigrationAttemptResult.NotCommitted(MigrationReason.InvalidData, false)
        is SQLiteException -> MigrationAttemptResult.NotCommitted(MigrationReason.StorageFailure, true)
        is IllegalArgumentException -> MigrationAttemptResult.NotCommitted(MigrationReason.InvalidData, false)
        else -> MigrationAttemptResult.NotCommitted(MigrationReason.UnsupportedHost, false)
    }

    private fun assertStorageIdentity() {
        require(::database.isInitialized && database.isOpen && !database.isReadOnly && database.version == 17)
        require(File(database.path).canonicalFile == context.getDatabasePath("light_novel_reader_database").canonicalFile)
        require(field(localDataManager, "userDataDao") === field(userData, "userDataDao"))
    }

    private fun actualLocalDataManager(): Any {
        val application = context.applicationContext
        // In current official API 3 APKs b() is GeneratedComponentManager.generatedComponent().
        val componentMethod = application.javaClass.methods.singleOrNull { it.name == "generatedComponent" && it.parameterCount == 0 }
            ?: application.javaClass.declaredMethods.single { it.name == "b" && it.parameterCount == 0 && it.returnType == Any::class.java }
        componentMethod.isAccessible = true
        val component = checkNotNull(componentMethod.invoke(application))
        val managerClass = Class.forName(HOST + "data.local.LocalDataManager", false, context.classLoader)
        component.javaClass.methods.singleOrNull { it.parameterCount == 0 && it.returnType == managerClass }
            ?.let { it.isAccessible = true; return checkNotNull(it.invoke(component)) }
        // G is the shared official API 3 scoped LocalDataManager binding. No provider scanning.
        val providerField = fields(component.javaClass).singleOrNull { it.name == "localDataManagerProvider" || it.name == "G" }
            ?: error("宿主 Hilt 图中未找到源数据管理器")
        val provider = providerField.getAccessible(component) ?: error("宿主源数据管理器未初始化")
        val getter = provider.javaClass.getMethod("get").apply { isAccessible = true }
        val manager = checkNotNull(getter.invoke(provider))
        require(managerClass.isInstance(manager)) { "宿主源数据管理器类型不一致" }
        return manager
    }

    private fun selectedSource(): Int = database.rawQuery("SELECT value,type FROM user_data WHERE path=?", arrayOf(SOURCE_PATH)).use { cursor ->
        require(cursor.moveToFirst() && cursor.getString(1) == "Int") { "缺少持久化活动源归属" }
        cursor.getString(0).toInt()
    }

    private fun readActive(sourceId: Int): MigrationSnapshot {
        val result = linkedMapOf<String, JsonElement>("webBookDataSourceId" to JsonPrimitive(sourceId))
        TABLES.forEach { table ->
            val rows = mutableListOf<JsonElement>()
            database.rawQuery("SELECT * FROM ${table.name}", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (table.name == "user_data" && cursor.getString(cursor.getColumnIndexOrThrow("path")) !in sourcePaths) continue
                    rows += JsonObject(table.columns.associate { column -> column.field to readValue(cursor, column) })
                }
            }
            result[table.field] = JsonArray(rows)
        }
        return MigrationSnapshot(sourceId, JsonObject(result))
    }

    private fun writeSnapshot(snapshot: MigrationSnapshot) {
        TABLES.filter { it.name != "user_data" }.forEach { table ->
            database.delete(table.name, null, null)
            snapshot.data.getValue(table.field).jsonArray.forEach { item ->
                val row = item.jsonObject
                val values = ContentValues()
                table.columns.forEach { column -> writeValue(values, column, row[column.field]) }
                insert(table.name, values)
            }
        }
        sourcePaths.forEach { database.delete("user_data", "path=?", arrayOf(it)) }
        snapshot.data.getValue("userDataEntities").jsonArray.forEach { item ->
            val row = item.jsonObject
            val path = row.getValue("path").jsonPrimitive.content
            require(path in sourcePaths) { "快照含未登记的源偏好：$path" }
            if (path == SOURCE_PATH || path == RECEIPT_PATH) return@forEach
            insert("user_data", ContentValues().apply {
                listOf("path", "group", "type", "value").forEach { put(it, row.getValue(it).jsonPrimitive.content) }
            })
        }
    }

    private fun receipt(snapshot: MigrationSnapshot): JsonObject? {
        val rows = snapshot.data.getValue("userDataEntities").jsonArray.map { it.jsonObject }.filter {
            it["path"]?.jsonPrimitive?.content == RECEIPT_PATH
        }
        require(rows.size <= 1) { "迁入完成凭据重复" }
        if (rows.isEmpty()) return null
        val row = rows.single()
        require(row.getValue("type").jsonPrimitive.content == "String") { "迁入完成凭据类型无效" }
        val value = Json.parseToJsonElement(row.getValue("value").jsonPrimitive.content).jsonObject
        require(value["kind"]?.jsonPrimitive?.content == "linovelib_tw_once" &&
            value["ruleVersion"]?.jsonPrimitive?.int == 1 && value["sourceId"]?.jsonPrimitive?.int == MigrationData.TW_SOURCE_ID &&
            value["targetId"]?.jsonPrimitive?.int == MigrationData.CURRENT_SOURCE_ID &&
            value["personalFingerprint"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) == true &&
            !value["commitId"]?.jsonPrimitive?.content.isNullOrBlank()) { "迁入完成凭据无效" }
        return value
    }

    private fun writeUserData(path: String, type: String, value: String) {
        insert("user_data", ContentValues().apply {
            put("path", path); put("group", path.substringBeforeLast('.', "")); put("type", type); put("value", value)
        })
    }

    private fun insert(table: String, values: ContentValues) {
        // `group` is a SQL keyword. Android's ContentValues insertion does not quote keys.
        // Android's ArrayMap EntrySet does not support toArray; copy key/value pairs while
        // iterating, as its iterator can also reuse the same Entry object for each position.
        val columns = ArrayList<String>(values.size())
        val arguments = ArrayList<Any?>(values.size())
        for (entry in values.valueSet()) {
            columns.add(entry.key)
            arguments.add(entry.value)
        }
        database.execSQL(
            "INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) VALUES (${columns.joinToString(",") { "?" }})",
            arguments.toTypedArray(),
        )
    }

    private fun validateSchema() {
        TABLES.forEach { table ->
            val actual = database.rawQuery("PRAGMA table_info(${table.name})", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            require(actual == table.columns.map { it.column }.toSet()) { "宿主表结构不匹配：${table.name}" }
        }
    }

    private inline fun <T> inTransaction(block: () -> T): T {
        database.beginTransaction()
        return try { block().also { database.setTransactionSuccessful() } } finally { database.endTransaction() }
    }

    private fun snapshotFile(sourceId: Int) = File(context.dataDir, "local_data/$sourceId")

    private fun checkSnapshotStamps(stamps: Map<File, String?>) {
        stamps.forEach { (file, digest) ->
            if ((if (file.isFile) MigrationSnapshotCodec.digest(file) else null) != digest) throw InputChanged()
        }
    }

    private fun readValue(cursor: Cursor, column: Column): JsonElement {
        val index = cursor.getColumnIndexOrThrow(column.column)
        require(!cursor.isNull(index)) { "宿主字段为空：${column.column}" }
        val expectedTypes = when (column.kind) {
            Kind.Integer, Kind.Boolean, Kind.Date, Kind.Time -> setOf(Cursor.FIELD_TYPE_INTEGER)
            Kind.Float -> setOf(Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_INTEGER)
            Kind.Count, Kind.Progress -> setOf(Cursor.FIELD_TYPE_BLOB)
            else -> setOf(Cursor.FIELD_TYPE_STRING)
        }
        require(cursor.getType(index) in expectedTypes) { "宿主字段类型无效：${column.column}" }
        if (column.kind in setOf(Kind.Boolean, Kind.Date, Kind.Time)) {
            require(cursor.getLong(index) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "宿主整数字段溢出：${column.column}" }
        }
        return when (column.kind) {
            Kind.String -> JsonPrimitive(cursor.getString(index))
            Kind.Integer -> JsonPrimitive(cursor.getLong(index))
            Kind.Float -> JsonPrimitive(cursor.getDouble(index))
            Kind.Boolean -> { require(cursor.getInt(index) in 0..1); JsonPrimitive(cursor.getInt(index) == 1) }
            Kind.StringList -> JsonArray(splitList(cursor.getString(index)).map(::JsonPrimitive))
            Kind.IntList -> JsonArray(splitList(cursor.getString(index)).map { JsonPrimitive(it.toInt()) })
            Kind.Date -> JsonPrimitive(dateFromSql(cursor.getInt(index)))
            Kind.Time -> JsonPrimitive(timeFromSql(cursor.getInt(index)))
            Kind.Count -> JsonPrimitive(Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
            Kind.Progress -> JsonObject(progressFromBytes(cursor.getBlob(index)).mapValues { JsonPrimitive(it.value) })
            Kind.WordCount -> {
                val values = cursor.getString(index).split("|.:.|")
                require(values.size == 3)
                buildJsonObject {
                    put("count", values[0].toInt())
                    put("unit", if (values[1] == "null") JsonNull else JsonPrimitive(values[1]))
                    put("unitResId", if (values[2] == "null") JsonNull else JsonPrimitive(values[2].toInt()))
                }
            }
        }
    }

    private fun writeValue(values: ContentValues, column: Column, value: JsonElement?) {
        val actual = value ?: missingFieldValue(column.field)
        when (column.kind) {
            Kind.String -> values.put(column.column, actual.jsonPrimitive.content)
            Kind.Integer -> values.put(column.column, actual.jsonPrimitive.long)
            Kind.Float -> values.put(column.column, actual.jsonPrimitive.double)
            Kind.Boolean -> values.put(column.column, if (actual.jsonPrimitive.boolean) 1 else 0)
            Kind.StringList, Kind.IntList -> values.put(column.column, actual.jsonArray.joinToString(",") { it.jsonPrimitive.content })
            Kind.Date -> values.put(column.column, dateToSql(actual.jsonPrimitive.content))
            Kind.Time -> values.put(column.column, timeToSql(actual.jsonPrimitive.content))
            Kind.Count -> values.put(column.column, Base64.decode(actual.jsonPrimitive.content, Base64.DEFAULT))
            Kind.Progress -> values.put(column.column, progressToBytes(actual.jsonObject.mapValues { it.value.jsonPrimitive.float }))
            Kind.WordCount -> values.put(column.column, actual.jsonObject.let {
                "${it.getValue("count").jsonPrimitive.int}|.:.|${it["unit"]?.jsonPrimitive?.content ?: "null"}|.:.|${it["unitResId"]?.jsonPrimitive?.content ?: "null"}"
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun progressFromBytes(bytes: ByteArray): Map<String, Float> {
        val converter = Class.forName(HOST + "data.local.room.converter.ChapterReadingProgressMapConverter", true, context.classLoader)
        return converter.getMethod("byteArrayToMap", ByteArray::class.java).invoke(converter.getField("INSTANCE").get(null), bytes) as Map<String, Float>
    }

    private fun progressToBytes(map: Map<String, Float>): ByteArray {
        val converter = Class.forName(HOST + "data.local.room.converter.ChapterReadingProgressMapConverter", true, context.classLoader)
        return converter.getMethod("mapToByteArray", Map::class.java).invoke(converter.getField("INSTANCE").get(null), map) as ByteArray
    }

    private enum class Kind { String, Integer, Float, Boolean, StringList, IntList, Date, Time, Count, Progress, WordCount }
    private data class Column(val field: String, val column: String, val kind: Kind)
    private data class Table(val field: String, val name: String, val columns: List<Column>)

    companion object {
        const val RECEIPT_PATH = "linovelib.migration.tw.v1.receipt"
        fun supportsHost(packageName: String, apiVersion: Int): Boolean =
            packageName == HOST_PACKAGE && apiVersion == 3
        /** Only fields with declared API 3 entity defaults may be omitted on the CBOR wire. */
        fun missingFieldValue(field: String): JsonElement = when (field) {
            "sortReversed", "isFinished", "isFavorited" -> JsonPrimitive(false)
            else -> error("缺少宿主字段：$field")
        }
        private const val HOST = "indi.dmzz_yyhyy.lightnovelreader."
        private const val HOST_PACKAGE = "indi.dmzz_yyhyy.lightnovelreader"
        private const val SOURCE_PATH = MigrationSnapshotCodec.SOURCE_PATH
        // The released host contains a shrunk j$.time library. Avoid new time overloads here.
        fun dateFromSql(value: Int): String {
            require(value in 10000101..99991231) { "宿主日期字段无效" }
            val digits = value.toString()
            return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
        }
        fun dateToSql(value: String): Int {
            require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "宿主日期格式无效" }
            return value.replace("-", "").toInt().also { require(it in 10000101..99991231) { "宿主日期字段无效" } }
        }
        fun timeFromSql(value: Int): String {
            require(value in 0..235959 && value / 100 % 100 <= 59 && value % 100 <= 59) { "宿主时刻字段无效" }
            val digits = value.toString().padStart(6, '0')
            return "${digits.substring(0, 2)}:${digits.substring(2, 4)}:${digits.substring(4, 6)}"
        }
        fun timeToSql(value: String): Int {
            val match = Regex("([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.[0-9]{1,9})?)?").matchEntire(value)
                ?: error("宿主时刻格式无效")
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            val second = match.groupValues[3].ifEmpty { "0" }.toInt()
            require(hour in 0..23 && minute in 0..59 && second in 0..59) { "宿主时刻字段无效" }
            return hour * 10000 + minute * 100 + second
        }
        private fun splitList(value: String) = if (value.isEmpty()) emptyList() else value.split(',')
        private fun fields(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }.toList()
        private fun Field.getAccessible(instance: Any): Any? { isAccessible = true; return get(instance) }
        private fun field(instance: Any, name: String): Any = fields(instance.javaClass).single { it.name == name }.getAccessible(instance) ?: error("缺少宿主字段：$name")
        private fun inherits(type: Class<*>, name: String) = generateSequence(type) { it.superclass }.any { it.name == name }
        private fun c(field: String, column: String = field, kind: Kind = Kind.String) = Column(field, column, kind)
        private val TABLES = listOf(
            Table("bookInformationEntities", "book_information", listOf(c("id"), c("title"), c("subtitle"), c("coverUri", "cover_uri"), c("author"), c("description"), c("tags", kind = Kind.StringList), c("publishingHouse", "publishing_house"), c("wordCount", "word_count", Kind.WordCount), c("lastUpdated", "last_update"), c("isComplete", "is_complete", Kind.Boolean))),
            Table("bookRecordEntities", "book_records", listOf(c("bookId", "book_id"), c("date", kind = Kind.Date), c("reads", kind = Kind.Integer), c("seconds", kind = Kind.Integer), c("isFinished", "is_finished", Kind.Boolean), c("isFavorited", "is_favorited", Kind.Boolean), c("firstSeen", "first_seen", Kind.Time), c("lastSeen", "last_seen", Kind.Time))),
            Table("dailyCountEntities", "daily_count", listOf(c("date", kind = Kind.Date), c("timeCount", "time_count", Kind.Count))),
            Table("bookshelfEntities", "book_shelf", listOf(c("id", kind = Kind.Integer), c("name"), c("sortType", "sort_type"), c("sortReversed", "sort_reversed", Kind.Boolean), c("autoCache", "auto_cache", Kind.Boolean), c("systemUpdateReminder", "system_update_reminder", Kind.Boolean), c("allBookIds", "all_book_ids", Kind.StringList), c("pinnedBookIds", "pinned_book_ids", Kind.StringList), c("updatedBookIds", "updated_book_ids", Kind.StringList))),
            Table("bookshelfBookMetadataEntities", "book_shelf_book_metadata", listOf(c("id"), c("lastUpdate", "last_update"), c("bookShelfIds", "book_shelf_ids", Kind.IntList))),
            Table("chapterContentEntities", "chapter_content", listOf(c("id"), c("title"), c("content"), c("lastChapter"), c("nextChapter"))),
            Table("chapterInformationEntities", "chapter_information", listOf(c("id"), c("title"))),
            Table("formattingRuleEntities", "formatting_rule", listOf(c("id", kind = Kind.Integer), c("bookId", "book_id"), c("name"), c("isRegex", "is_regex", Kind.Boolean), c("match"), c("replacement"), c("isEnabled", "is_enabled", Kind.Boolean))),
            Table("userDataEntities", "user_data", listOf(c("path"), c("group"), c("type"), c("value"))),
            Table("userReadingDataEntities", "user_reading_data", listOf(c("id"), c("lastReadTime", "last_read_time"), c("totalReadTime", "total_read_time", Kind.Integer), c("readingProgress", "reading_progress", Kind.Float), c("lastReadChapterId", "last_read_chapter_id"), c("lastReadChapterTitle", "last_read_chapter_title"), c("currentChapterReadingProgressMap", "current_chapter_reading_progress_map", Kind.Progress), c("maxChapterReadingProgressMap", "max_chapter_reading_progress_map", Kind.Progress))),
            Table("volumeEntities", "volume", listOf(c("bookId", "book_id"), c("volumeId", "volume_id"), c("volumeTitle", "volume_title"), c("chapterIds", "chapter_id_list", Kind.StringList), c("index", "volume_index", Kind.Integer))),
        )
    }
}
