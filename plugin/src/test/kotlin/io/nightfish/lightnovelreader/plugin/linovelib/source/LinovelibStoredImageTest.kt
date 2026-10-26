package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

// Signature fixtures exercise HTTP transport, size/MIME checks and file caching.
// They are not complete images and do not test decoding, display or EPUB export.
class LinovelibStoredImageTest {
    @Test
    fun downloadsWithTheContentSiteReferer() = runBlocking {
        LinovelibHttpTestSite { exchange -> exchange.respond(200, "GIF89a", "image/gif") }.use { site ->
            val image = LinovelibImageStore.downloadImage("${site.host}/image.gif")

            assertEquals("${LinovelibUrls.HOST}/", site.requests.single().headers["referer"])
            assertArrayEquals("GIF89a".toByteArray(), image.bytes)
            assertEquals("image/gif", image.mimeType)
        }
    }

    @Test
    fun `retries only temporary http errors and stops after two attempts`() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 410, 422, 408, 425, 429, 500, 503, 599)) {
            ImageServer { response(status) }.use { server ->
                val failure = runCatching {
                    LinovelibImageStore.downloadImage(server.url)
                }.exceptionOrNull()

                assertTrue("HTTP $status should fail", failure is IOException)
                assertEquals("Image server returned HTTP $status", failure?.message)
                val expectedAttempts = if (status in listOf(408, 425, 429) || status in 500..599) 2 else 1
                assertEquals("HTTP $status attempts", expectedAttempts, server.requests.get())
            }
        }
    }

    @Test
    fun `recovers from temporary http and io failures while keeping the attempt limit`() = runBlocking {
        val brokenBody = "HTTP/1.1 200 OK\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\ninvalid-chunk\r\n"
        for (initialResponse in listOf(response(503), brokenBody)) {
            ImageServer { attempt ->
                if (attempt == 1) initialResponse else response(200, "GIF89a")
            }.use { server ->
                val image = LinovelibImageStore.downloadImage(server.url)

                assertArrayEquals("GIF89a".toByteArray(), image.bytes)
                assertEquals("image/gif", image.mimeType)
                assertEquals(2, server.requests.get())
            }
        }
        ImageServer { brokenBody }.use { server ->
            val failure = runCatching {
                LinovelibImageStore.downloadImage(server.url)
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(2, server.requests.get())
        }
    }

    @Test
    fun `does not wait to retry non io programming errors`() = runBlocking(Dispatchers.IO) {
        ImageServer { response(200, "GIF89a") }.use { server ->
            val download = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    LinovelibImageStore.downloadImage(server.url.replaceFirst("http:", "file:"))
                }
            }
            try {
                assertTrue("Programming errors should fail without suspending for a retry", download.isCompleted)
                val failure = download.await().exceptionOrNull()
                assertTrue("Expected the original connection type error, got $failure", failure is ClassCastException)
                assertEquals(0, server.requests.get())
            } finally {
                download.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancelling a download propagates cancellation without retrying`() = runBlocking {
        val firstRequest = CompletableDeferred<Unit>()
        ImageServer {
            firstRequest.complete(Unit)
            response(503)
        }.use { server ->
            val download = async { LinovelibImageStore.downloadImage(server.url) }
            withTimeout(5_000) { firstRequest.await() }
            download.cancel()
            val failure = runCatching { download.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun downloadAcceptsSignaturePayloadAtFifteenMiBLimit() = runBlocking {
        val limit = 15 * 1024 * 1024
        val body = "GIF89a".padEnd(limit, 'x')
        ImageServer { response(200, body) }.use { server ->
            val image = LinovelibImageStore.downloadImage(server.url)

            assertEquals(limit, image.bytes.size)
            assertTrue(LinovelibImageValidation.isValid(image.bytes, image.mimeType))
            assertEquals('x'.code.toByte(), image.bytes.last())
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun oversizedDownloadIsRejectedBeforeCachingAndLaterRequestRecovers() = runBlocking {
        val body = "GIF89a".padEnd(15 * 1024 * 1024 + 1, 'x')
        val directory = Files.createTempDirectory("linovelib-image-limit").toFile()
        val failures = mutableListOf<Throwable>()
        try {
            ImageServer { attempt -> response(200, if (attempt <= 2) body else "GIF89a") }.use { server ->
                val store = LinovelibImageStore(
                    directory = directory,
                    downloader = { LinovelibImageStore.downloadImage(server.url) },
                    diagnostics = LinovelibDiagnostics { _, _, _, failure -> failure?.let(failures::add) }
                )
                val blocks = listOf(ParsedContentBlock.Image("https://img.readpai.com/size-limit.gif"))

                assertTrue(store.localize(blocks).isEmpty())
                assertEquals(2, server.requests.get())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertTrue(failures.single() is IOException)
                assertEquals("Image response was too large", failures.single().message)

                val recovered = store.localize(blocks).single() as ParsedContentBlock.Image
                assertArrayEquals("GIF89a".toByteArray(), File(URI(recovered.url)).readBytes())
                assertEquals(3, server.requests.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stores signature bytes reuses file uri and replaces invalid cache`() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-images").toFile()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)
        var downloads = 0
        val store = LinovelibImageStore(
            directory = directory,
            downloader = {
                downloads++
                DownloadedLinovelibImage(jpeg, " \tIMAGE/JPEG; charset=binary \t")
            },
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        val remote = "https://img.readpai.com/cover.jpg"
        val blocks = listOf(ParsedContentBlock.Image(remote), ParsedContentBlock.Image(remote))

        val first = store.localize(blocks)
        val second = store.localize(blocks)

        val firstUri = (first.first() as ParsedContentBlock.Image).url
        assertTrue(firstUri.startsWith("file:"))
        assertEquals(firstUri, (first.last() as ParsedContentBlock.Image).url)
        assertEquals(firstUri, (second.first() as ParsedContentBlock.Image).url)
        val cachedFile = File(URI(firstUri))
        assertArrayEquals(jpeg, cachedFile.readBytes())
        assertEquals(1, downloads)

        cachedFile.writeBytes(byteArrayOf(0))
        val recovered = store.localize(blocks)
        assertEquals(first, recovered)
        assertArrayEquals(jpeg, cachedFile.readBytes())
        assertEquals(2, downloads)
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `omits protected image when downloader fails`() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-images").toFile()
        val store = LinovelibImageStore(
            directory = directory,
            downloader = { error("blocked") },
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        val remote = "https://img.readpai.com/illustration.webp"

        val text = ParsedContentBlock.Text("正文")
        val publicImage = ParsedContentBlock.Image("https://example.com/illustration.webp")
        val result = store.localize(listOf(text, ParsedContentBlock.Image(remote), publicImage, ParsedContentBlock.Image(remote)))

        assertEquals(listOf(text, publicImage), result)
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `unavailable directory omits only protected images without downloading`() = runBlocking {
        val directory = Files.createTempFile("linovelib-image-directory", ".file").toFile()
        try {
            val events = mutableListOf<String>()
            val store = LinovelibImageStore(
                directory = directory,
                downloader = { error("Directory failure must stop before downloading") },
                diagnostics = LinovelibDiagnostics { _, _, message, _ -> events += message }
            )
            val text = ParsedContentBlock.Text("正文")
            val publicImage = ParsedContentBlock.Image("https://example.com/illustration.webp")
            val protectedImage = ParsedContentBlock.Image("https://img.readpai.com/illustration.webp")

            assertEquals(listOf(text, publicImage), store.localize(listOf(text, protectedImage, publicImage)))
            assertTrue(events.single().startsWith("IMAGE_DIRECTORY_ERROR"))
        } finally {
            directory.delete()
        }
    }

    @Test
    fun rejectsHtmlPayloadFromDownloaderWithoutCreatingCache() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-images").toFile()
        val store = LinovelibImageStore(
            directory = directory,
            downloader = {
                DownloadedLinovelibImage("<html>blocked</html>".toByteArray(), "text/html")
            },
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )

        val result = store.localize(
            listOf(ParsedContentBlock.Image("https://img.readpai.com/intercept.jpg"))
        )

        assertTrue(result.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.deleteRecursively()
        Unit
    }

    private fun response(status: Int, body: String = ""): String =
        "HTTP/1.1 $status Test\r\nConnection: close\r\nContent-Type: image/gif\r\nContent-Length: ${body.length}\r\n\r\n$body"

    private class ImageServer(respond: (Int) -> String) : Closeable {
        private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${server.localPort}/image.gif"
        private val worker = executor.submit {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (failure: SocketException) {
                    if (server.isClosed) break else throw failure
                }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                    it.getOutputStream().write(respond(requests.incrementAndGet()).toByteArray(Charsets.US_ASCII))
                }
            }
        }

        override fun close() {
            server.close()
            executor.shutdown()
            worker.get(5, TimeUnit.SECONDS)
        }
    }
}
