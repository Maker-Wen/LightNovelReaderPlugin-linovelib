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

class LinovelibStoredImageTest {
    @Test
    fun `retries only temporary http errors and stops after two attempts`() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 410, 422, 408, 425, 429, 500, 503, 599)) {
            ImageServer { response(status) }.use { server ->
                val failure = runCatching {
                    LinovelibImageStore.downloadImage(server.url, LinovelibUrls.HOST)
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
                val image = LinovelibImageStore.downloadImage(server.url, LinovelibUrls.HOST)

                assertArrayEquals("GIF89a".toByteArray(), image.bytes)
                assertEquals("image/gif", image.mimeType)
                assertEquals(2, server.requests.get())
            }
        }
        ImageServer { brokenBody }.use { server ->
            val failure = runCatching {
                LinovelibImageStore.downloadImage(server.url, LinovelibUrls.HOST)
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
                    LinovelibImageStore.downloadImage(server.url, "https://example.com/\ninvalid")
                }
            }
            try {
                assertTrue("Programming errors should fail without suspending for a retry", download.isCompleted)
                val failure = download.await().exceptionOrNull()
                assertTrue("Expected the original header error, got $failure", failure is IllegalArgumentException)
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
            val download = async { LinovelibImageStore.downloadImage(server.url, LinovelibUrls.HOST) }
            withTimeout(5_000) { firstRequest.await() }
            download.cancel()
            val failure = runCatching { download.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun `stores protected image once and reuses local file uri`() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-images").toFile()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)
        var downloads = 0
        val store = LinovelibImageStore(
            directory = directory,
            downloader = {
                downloads++
                DownloadedLinovelibImage(jpeg, "image/jpeg")
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
        assertArrayEquals(jpeg, File(URI(firstUri)).readBytes())
        assertEquals(1, downloads)
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `omits image when local storage fails so export does not retry remote url`() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-images").toFile()
        val store = LinovelibImageStore(
            directory = directory,
            downloader = { error("blocked") },
            diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        val remote = "https://img.readpai.com/illustration.webp"

        val result = store.localize(listOf(ParsedContentBlock.Image(remote)))

        assertTrue(result.isEmpty())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `rejects html interception page returned with http 200`() = runBlocking {
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
