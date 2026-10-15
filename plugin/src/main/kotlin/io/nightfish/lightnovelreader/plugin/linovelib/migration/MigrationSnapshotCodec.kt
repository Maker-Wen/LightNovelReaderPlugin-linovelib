package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Reads the official API 3 LocalData format without adding a conversion or CBOR dependency. */
internal class MigrationSnapshotCodec {
    data class Input(val snapshot: MigrationSnapshot, val file: File, val digest: String)

    fun readTwSource(standard: File, alias: File): Input? {
        val primary = if (standard.isFile) readable { read(standard, MigrationData.TW_SOURCE_ID) } else null
        if (primary != null) return primary
        return if (alias.isFile) readable { readTwAlias(alias) } else null
    }

    private inline fun readable(read: () -> Input?): Input? = try { read() } catch (_: Exception) { null }

    /** A legacy -1 file is a TW candidate only when its persisted owner is TW. */
    fun readTwAlias(file: File): Input? = resolveTwAlias(readSnapshot(file, -1))

    fun read(file: File, expectedSourceId: Int): Input {
        val input = readSnapshot(file, expectedSourceId)
        return if (expectedSourceId == -1) requireNotNull(resolveTwAlias(input)) {
            "无法确认 -1 快照属于 TW"
        } else input
    }

    private fun resolveTwAlias(input: Input): Input? {
        val data = input.snapshot.data
        val selected = data.getValue("userDataEntities").jsonArray.map { it.jsonObject }
            .filter { it["path"] == JsonPrimitive(SOURCE_PATH) }.singleOrNull()
        val value = selected?.get("value") as? JsonPrimitive
        val owner = value?.takeIf { it.isString }?.content?.toIntOrNull()
        require(selected?.get("type") == JsonPrimitive("Int") && owner != null && owner != -1) {
            "无法确认 -1 快照属于 TW"
        }
        if (owner != MigrationData.TW_SOURCE_ID) return null
        val resolved = JsonObject(data + ("webBookDataSourceId" to JsonPrimitive(MigrationData.TW_SOURCE_ID)))
        return input.copy(snapshot = MigrationSnapshot(MigrationData.TW_SOURCE_ID, resolved))
    }

    private fun readSnapshot(file: File, expectedSourceId: Int): Input {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "源快照不存在或过大" }
        val digestBefore = digest(file)
        val bytes = file.inputStream().use { input ->
            val prefix = ByteArray(4)
            val count = input.read(prefix)
            if (count == 4 && prefix.contentEquals(byteArrayOf(80, 75, 3, 4))) {
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence().filter { it.name == "data" }.toList()
                    require(entries.size == 1 && !entries.single().isDirectory) { "源快照 ZIP 缺少唯一 data 条目" }
                    val entry = entries.single()
                    require(entry.size <= MAX_BYTES) { "源快照解包后过大" }
                    zip.getInputStream(entry).use(::readBounded)
                }
            } else {
                file.inputStream().use(::readBounded)
            }
        }
        require(digestBefore == digest(file)) { "源快照在读取期间发生变化" }
        val data = decode(bytes)
        val encodedId = data.getValue("webBookDataSourceId").jsonPrimitive.int
        require(encodedId == expectedSourceId) { "源快照身份与文件名不一致" }
        if (expectedSourceId == MigrationData.TW_SOURCE_ID) {
            val owners = data.getValue("userDataEntities").jsonArray.map { it.jsonObject }
                .filter { it["path"] == JsonPrimitive(SOURCE_PATH) }
            if (owners.isNotEmpty()) require(owners.size == 1 && owners.single()["type"] == JsonPrimitive("Int") &&
                owners.single()["value"] == JsonPrimitive(MigrationData.TW_SOURCE_ID.toString())) { "TW 快照归属不明确" }
        }
        return Input(MigrationSnapshot(expectedSourceId, data), file, digestBefore)
    }

    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024
        const val SOURCE_PATH = "settings.data.web_data_source_id"

        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    sha.update(buffer, 0, count)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }

        fun decode(bytes: ByteArray): JsonObject {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "无效快照大小" }
            val decoder = Decoder(bytes)
            val data = decoder.decode().jsonObject
            val id = data["webBookDataSourceId"] as? JsonPrimitive
            require(id != null && !id.isString && id.intOrNull != null) { "源快照缺少合法 ID" }
            if (id.int == MigrationData.CURRENT_SOURCE_ID) {
                // An inactive target must never lose original rows before the planner sees them.
                require(!decoder.hasUnavailableFloats && data.keys == (MigrationData.TABLES + "webBookDataSourceId").toSet()) {
                    "当前源快照无法完整装载"
                }
                MigrationData.TABLES.forEach { name ->
                    require(data[name] is JsonArray && data.getValue(name).jsonArray.all { it is JsonObject }) {
                        "当前源快照实体列表无法完整装载：$name"
                    }
                }
                return data
            }
            return JsonObject(buildMap {
                put("webBookDataSourceId", id)
                MigrationData.TABLES.forEach { name ->
                    put(name, JsonArray((data[name] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()))
                }
            })
        }

        private fun readBounded(input: InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= MAX_BYTES) { "源快照解包后过大" }
                output.write(buffer, 0, size)
            }
            return output.toByteArray()
        }
    }

    /** Deliberately accepts only the types emitted by the official LocalData serializers. */
    private class Decoder(private val bytes: ByteArray) {
        private var offset = 0
        private var nodes = 0
        var hasUnavailableFloats = false
            private set
        fun decode(): JsonElement = value(0).also { require(offset == bytes.size) { "源快照含尾随数据" } }
        private fun byte(): Int {
            require(offset < bytes.size) { "源快照被截断" }
            return bytes[offset++].toInt() and 255
        }
        private fun argument(info: Int): Long = when (info) {
            in 0..23 -> info.toLong()
            24 -> byte().toLong()
            25 -> (byte().toLong() shl 8) or byte().toLong()
            26 -> (0 until 4).fold(0L) { n, _ -> (n shl 8) or byte().toLong() }
            27 -> (0 until 8).fold(0L) { n, _ -> (n shl 8) or byte().toLong() }.also { require(it >= 0) }
            31 -> -1
            else -> error("不支持的 CBOR 长度")
        }
        private fun value(depth: Int): JsonElement {
            require(depth <= 64 && ++nodes <= 2_000_000) { "源快照结构过深或过大" }
            val initial = byte()
            val major = initial ushr 5
            val info = initial and 31
            if (major == 7) return when (info) {
                20 -> JsonPrimitive(false)
                21 -> JsonPrimitive(true)
                22 -> JsonNull
                26 -> Float.fromBits(argument(26).toInt()).let {
                    if (it.isFinite()) JsonPrimitive(it) else { hasUnavailableFloats = true; JsonNull }
                }
                27 -> {
                    require(offset + 8 <= bytes.size)
                    val number = ByteBuffer.wrap(bytes, offset, 8).double
                    offset += 8
                    if (number.isFinite()) JsonPrimitive(number) else { hasUnavailableFloats = true; JsonNull }
                }
                else -> error("不支持的 CBOR 值")
            }
            val size = argument(info)
            return when (major) {
                0 -> { require(size >= 0); JsonPrimitive(size) }
                1 -> { require(size >= 0); JsonPrimitive(-1L - size) }
                3 -> {
                    require(size in 0..MAX_BYTES.toLong() && size <= bytes.size - offset)
                    val count = size.toInt()
                    val decoder = Charsets.UTF_8.newDecoder()
                    val text = decoder.decode(ByteBuffer.wrap(bytes, offset, count)).toString()
                    offset += count
                    JsonPrimitive(text)
                }
                4 -> {
                    require(size in -1..2_000_000)
                    val list = mutableListOf<JsonElement>()
                    if (size == -1L) while (!breakMarker()) list += value(depth + 1)
                    else repeat(size.toInt()) { list += value(depth + 1) }
                    JsonArray(list)
                }
                5 -> {
                    require(size in -1..2_000_000)
                    val map = linkedMapOf<String, JsonElement>()
                    fun pair() {
                        val key = value(depth + 1)
                        require(key is JsonPrimitive && key.isString) { "CBOR 字段名无效" }
                        require(!map.containsKey(key.content)) { "源快照含重复字段" }
                        map[key.content] = value(depth + 1)
                    }
                    if (size == -1L) while (!breakMarker()) pair() else repeat(size.toInt()) { pair() }
                    JsonObject(map)
                }
                else -> error("不支持的 CBOR 类型")
            }
        }
        private fun breakMarker(): Boolean {
            require(offset < bytes.size) { "源快照被截断" }
            return if ((bytes[offset].toInt() and 255) == 255) { offset++; true } else false
        }
    }
}
