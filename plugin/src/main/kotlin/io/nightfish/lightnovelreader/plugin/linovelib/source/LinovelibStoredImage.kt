package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class DownloadedLinovelibImage(
    val bytes: ByteArray,
    val mimeType: String
)

internal class LinovelibImageStore(
    private val directory: File,
    private val downloader: (suspend (String) -> DownloadedLinovelibImage)? = null,
    private val diagnostics: LinovelibDiagnostics
) {
    suspend fun localize(blocks: List<ParsedContentBlock>): List<ParsedContentBlock> {
        val protectedUrls = blocks.filterIsInstance<ParsedContentBlock.Image>()
            .map(ParsedContentBlock.Image::url)
            .filter(LinovelibImageRules::isProtectedRemoteUrl)
            .toSet()
        if (protectedUrls.isEmpty()) return blocks
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            diagnostics.error(
                "IMAGE_DIRECTORY_ERROR",
                IOException("Unable to create image directory"),
                mapOf("directory" to directory.absolutePath)
            )
            return blocks.filterNot { it is ParsedContentBlock.Image && it.url in protectedUrls }
        }

        val localUrls = mutableMapOf<String, String>()
        protectedUrls.chunked(MAX_CONCURRENT_DOWNLOADS).forEach { chunk ->
            val results = coroutineScope {
                chunk.map { url ->
                    async(Dispatchers.IO) { url to runCatching { localUri(url) } }
                }.awaitAll()
            }
            results.forEach { (url, result) ->
                result.onSuccess { localUrls[url] = it }
                    .onFailure { throwable ->
                        throwable.rethrowIfCancellation()
                        diagnostics.error("IMAGE_STORE_ERROR", throwable, mapOf("url" to url))
                    }
            }
        }

        return blocks.mapNotNull { block ->
            if (block is ParsedContentBlock.Image) {
                localUrls[block.url]?.let { ParsedContentBlock.Image(it) }
                    ?: block.takeUnless { it.url in protectedUrls }
            } else {
                block
            }
        }
    }

    private suspend fun localUri(url: String): String {
        val target = directory.resolve(cacheKey(url))
        if (isValidStoredFile(target)) return target.toURI().toString()
        target.delete()

        val image = downloader?.invoke(url) ?: downloadImage(url)
        require(LinovelibImageValidation.isValid(image.bytes, image.mimeType)) {
            "Image response did not contain a supported image"
        }

        val temporary = File.createTempFile("download-", ".tmp", directory)
        try {
            temporary.writeBytes(image.bytes)
            if (!temporary.renameTo(target)) {
                if (!isValidStoredFile(target)) {
                    throw IOException("Unable to atomically store image")
                }
                temporary.delete()
            }
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
        return target.toURI().toString()
    }

    private fun isValidStoredFile(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_IMAGE_BYTES) return@runCatching false
        val header = file.inputStream().use { input ->
            ByteArray(16).let { buffer -> buffer.copyOf(input.read(buffer).coerceAtLeast(0)) }
        }
        LinovelibImageValidation.hasSupportedSignature(header)
    }.getOrDefault(false)

    private fun cacheKey(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> HEX_DIGITS[(byte.toInt() and 0xff) ushr 4].toString() + HEX_DIGITS[byte.toInt() and 0x0f] }

    internal companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 2
        private const val MAX_ATTEMPTS = 2
        private const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        private class ImageHttpException(val statusCode: Int) :
            IOException("Image server returned HTTP $statusCode")

        internal suspend fun downloadImage(url: String): DownloadedLinovelibImage {
            var attempt = 0
            while (true) {
                try {
                    return withContext(Dispatchers.IO) { executeDownload(url) }
                } catch (throwable: IOException) {
                    if (throwable is ImageHttpException &&
                        LinovelibRequestPolicy.retryDelayMillis(throwable.statusCode, null, attempt) == null
                    ) throw throwable
                    if (attempt == MAX_ATTEMPTS - 1) throw throwable
                    delay(500L)
                    attempt++
                }
            }
        }

        private fun executeDownload(url: String): DownloadedLinovelibImage {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Referer", "${LinovelibUrls.HOST}/")
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                val status = connection.responseCode
                if (status !in 200..299) throw ImageHttpException(status)
                val bytes = connection.inputStream.buffered().use(::readLimited)
                return DownloadedLinovelibImage(bytes, connection.contentType.orEmpty())
            } finally {
                connection.disconnect()
            }
        }

        private fun readLimited(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_IMAGE_BYTES) {
                    throw IOException("Image response was too large")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

internal object LinovelibImageValidation {
    fun isValid(bytes: ByteArray, mimeType: String): Boolean =
        mimeType.trim().startsWith("image/", ignoreCase = true) &&
            hasSupportedSignature(bytes)

    fun hasSupportedSignature(bytes: ByteArray): Boolean =
        bytes.startsWith(0xff, 0xd8, 0xff) ||
            bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
            bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") ||
            (bytes.startsWithAscii("RIFF") && bytes.hasAsciiAt(8, "WEBP")) ||
            bytes.startsWithAscii("BM") ||
            (bytes.hasAsciiAt(4, "ftyp") && (bytes.hasAsciiAt(8, "avif") || bytes.hasAsciiAt(8, "avis")))

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { index -> this[index].toInt() and 0xff == expected[index] }

    private fun ByteArray.startsWithAscii(expected: String): Boolean = hasAsciiAt(0, expected)

    private fun ByteArray.hasAsciiAt(offset: Int, expected: String): Boolean =
        size >= offset + expected.length && expected.indices.all { index ->
            this[offset + index].toInt() and 0xff == expected[index].code
        }
}

internal object LinovelibImageRules {
    fun isProtectedRemoteUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI.create(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme.equals("https", ignoreCase = true) && (
            host == "readpai.com" || host.endsWith(".readpai.com") ||
                host == "linovelib.com" || host.endsWith(".linovelib.com") ||
                host == "bilinovel.com" || host.endsWith(".bilinovel.com") ||
                host == "bilinovel.net" || host.endsWith(".bilinovel.net")
            )
    }.getOrDefault(false)
}
