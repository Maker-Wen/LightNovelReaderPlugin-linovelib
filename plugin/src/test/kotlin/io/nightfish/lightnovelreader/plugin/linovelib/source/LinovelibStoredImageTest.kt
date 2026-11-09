package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
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
    fun retryAfterHttpDateDelaysTheSecondRequest() = runBlocking {
        val retryAtMillis = AtomicLong()
        val retrySentAtMillis = AtomicLong()
        LinovelibHttpTestSite { exchange ->
            if (retryAtMillis.get() == 0L) {
                val retryAt = Instant.ofEpochSecond(Instant.now().epochSecond + 3)
                retryAtMillis.set(retryAt.toEpochMilli())
                exchange.responseHeaders.set(
                    "Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(retryAt.atZone(ZoneOffset.UTC))
                )
                exchange.respond(429)
            } else {
                retrySentAtMillis.set(System.currentTimeMillis())
                exchange.respond(200, "GIF89a", "image/gif")
            }
        }.use { site ->
            val image = withTimeout(6_000) { LinovelibImageStore.downloadImage("${site.host}/dated.gif") }

            assertEquals(2, site.requests.size)
            assertTrue(retrySentAtMillis.get() + 2 >= retryAtMillis.get())
            assertArrayEquals("GIF89a".toByteArray(), image.bytes)
        }
    }

    @Test
    fun finalRateLimitStillDelaysTheNextImageOnTheSameHost() = runBlocking {
        val attempts = AtomicInteger()
        val lastRateLimitAt = AtomicLong()
        val nextRequestAt = AtomicLong()
        LinovelibHttpTestSite { exchange ->
            if (exchange.requestURI.path == "/limited.gif") {
                val finalAttempt = attempts.incrementAndGet() == 2
                if (finalAttempt) lastRateLimitAt.set(System.nanoTime())
                exchange.responseHeaders.set("Retry-After", if (finalAttempt) "1" else "0")
                exchange.respond(429)
            } else {
                nextRequestAt.set(System.nanoTime())
                exchange.respond(200, "GIF89a", "image/gif")
            }
        }.use { site ->
            val failure = runCatching { LinovelibImageStore.downloadImage("${site.host}/limited.gif") }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(2, attempts.get())

            val image = withTimeout(4_000) { LinovelibImageStore.downloadImage("${site.host}/next.gif") }
            val waitMillis = TimeUnit.NANOSECONDS.toMillis(nextRequestAt.get() - lastRateLimitAt.get())
            assertTrue("Next image waited only $waitMillis ms", waitMillis >= 990)
            assertArrayEquals("GIF89a".toByteArray(), image.bytes)
        }
    }

    @Test
    fun `retries only temporary http errors and stops after two attempts`() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 410, 422, 408, 425, 429, 500, 503, 599)) {
            ImageServer { response(status, retryAfter = if (status == 429) "0" else null) }.use { server ->
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
    fun redirectToCoolingHostWaitsAlongWithTheRetry() = runBlocking {
        withTimeout(5_000) {
            val firstLimitedAt = CompletableDeferred<Long>()
            val targetArrivals = mutableMapOf<String, Long>()
            val targetAttempts = AtomicInteger()
            LinovelibHttpTestSite { exchange ->
                val path = exchange.requestURI.path
                if (path == "/a.gif" && targetAttempts.incrementAndGet() == 1) {
                    exchange.responseHeaders.set("Retry-After", "1")
                    firstLimitedAt.complete(System.nanoTime())
                    exchange.sendResponseHeaders(429, -1)
                } else {
                    synchronized(targetArrivals) { targetArrivals[path] = System.nanoTime() }
                    exchange.respond(200, "GIF89a", "image/gif")
                }
            }.use { target ->
                LinovelibHttpTestSite { exchange ->
                    exchange.responseHeaders.set("Location", "${target.host}/b.gif")
                    exchange.sendResponseHeaders(302, -1)
                }.use { origin ->
                    val aRead429 = CompletableDeferred<Unit>()
                    val a = async(ObserveIoStep(aRead429)) {
                        LinovelibImageStore.downloadImage("${target.host}/a.gif")
                    }
                    aRead429.await()
                    assertTrue(firstLimitedAt.isCompleted)
                    val limitedAt = firstLimitedAt.await()

                    val b = async {
                        val differentHost = origin.host.replace("127.0.0.1", "localhost")
                        LinovelibImageStore.downloadImage("$differentHost/b.gif")
                    }

                    assertArrayEquals("GIF89a".toByteArray(), a.await().bytes)
                    assertArrayEquals("GIF89a".toByteArray(), b.await().bytes)
                    assertEquals(listOf("/b.gif"), origin.requests.map { it.path })
                    assertEquals(2, targetAttempts.get())
                    assertEquals(3, target.requests.size)
                    synchronized(targetArrivals) {
                        for (path in listOf("/a.gif", "/b.gif")) {
                            val elapsed = (targetArrivals.getValue(path) - limitedAt) / 1_000_000
                            assertTrue("$path reached the limited host after only ${elapsed}ms", elapsed >= 990)
                        }
                    }
                }
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

    @Test
    fun overlappingLoadsShareOneDownloadAndLeaveADiskCacheHit() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-image-overlap").toFile()
        try {
            withTimeout(5_000) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val downloads = AtomicInteger()
                val gif = "GIF89a".toByteArray()
                val blocks = listOf(ParsedContentBlock.Image("https://img.readpai.com/overlap.gif"))
                val store = LinovelibImageStore(
                    directory = directory,
                    downloader = {
                        downloads.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        DownloadedLinovelibImage(gif, "image/gif")
                    },
                    diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
                )
                val owner = async { store.localize(blocks) }
                started.await()
                val waiter = startOverlappingLoad(store, blocks)
                assertFalse(waiter.isCompleted)
                assertEquals(1, downloads.get())

                release.complete(Unit)
                val result = owner.await()
                assertEquals(result, waiter.await())
                val localFile = File(URI((result.single() as ParsedContentBlock.Image).url))
                assertArrayEquals(gif, localFile.readBytes())
                assertEquals(listOf(localFile), directory.listFiles().orEmpty().toList())
                assertEquals(result, store.localize(blocks))
                assertEquals(1, downloads.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun overlappingFailureIsSharedAndTheNextLoadCanRetry() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-image-overlap-failure").toFile()
        try {
            withTimeout(5_000) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val downloads = AtomicInteger()
                val blocks = listOf(ParsedContentBlock.Image("https://img.readpai.com/retry.gif"))
                val store = LinovelibImageStore(
                    directory = directory,
                    downloader = {
                        if (downloads.incrementAndGet() == 1) {
                            started.complete(Unit)
                            release.await()
                            throw IOException("Download failed")
                        }
                        DownloadedLinovelibImage("GIF89a".toByteArray(), "image/gif")
                    },
                    diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
                )
                val owner = async { store.localize(blocks) }
                started.await()
                val waiter = startOverlappingLoad(store, blocks)
                assertEquals(1, downloads.get())

                release.complete(Unit)
                assertTrue(owner.await().isEmpty())
                assertTrue(waiter.await().isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                val recovered = store.localize(blocks).single() as ParsedContentBlock.Image
                assertArrayEquals("GIF89a".toByteArray(), File(URI(recovered.url)).readBytes())
                assertEquals(2, downloads.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun waiterCancellationLeavesDownloadRunningAndOwnerCancellationAllowsTakeover() = runBlocking {
        val directory = Files.createTempDirectory("linovelib-image-overlap-cancel").toFile()
        try {
            withTimeout(5_000) {
                val started = CompletableDeferred<Unit>()
                val downloads = AtomicInteger()
                val blocks = listOf(ParsedContentBlock.Image("https://img.readpai.com/cancel.gif"))
                val store = LinovelibImageStore(
                    directory = directory,
                    downloader = {
                        if (downloads.incrementAndGet() == 1) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        DownloadedLinovelibImage("GIF89a".toByteArray(), "image/gif")
                    },
                    diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
                )
                val owner = async { store.localize(blocks) }
                started.await()
                val cancelledWaiter = startOverlappingLoad(store, blocks)
                cancelledWaiter.cancelAndJoin()
                assertTrue(owner.isActive)
                assertEquals(1, downloads.get())

                val activeWaiter = startOverlappingLoad(store, blocks)
                owner.cancelAndJoin()
                val recovered = activeWaiter.await().single() as ParsedContentBlock.Image
                assertArrayEquals("GIF89a".toByteArray(), File(URI(recovered.url)).readBytes())
                assertEquals(2, downloads.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun CoroutineScope.startOverlappingLoad(
        store: LinovelibImageStore,
        blocks: List<ParsedContentBlock>
    ): Deferred<List<ParsedContentBlock>> {
        val ioStepFinished = CompletableDeferred<Unit>()
        val load = async(ObserveIoStep(ioStepFinished)) { store.localize(blocks) }
        ioStepFinished.await()
        return load
    }

    // The marker is inherited by localize's IO child and reports when that child first yields.
    private class ObserveIoStep(
        private val finished: CompletableDeferred<Unit>
    ) : ThreadContextElement<Unit>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ObserveIoStep>

        override fun updateThreadContext(context: CoroutineContext) = Unit

        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
            if (context[ContinuationInterceptor] == Dispatchers.IO) finished.complete(Unit)
        }
    }

    private fun response(status: Int, body: String = "", retryAfter: String? = null): String {
        val retryAfterHeader = retryAfter?.let { "Retry-After: $it\r\n" }.orEmpty()
        return "HTTP/1.1 $status Test\r\nConnection: close\r\nContent-Type: image/gif\r\n" +
            "Content-Length: ${body.length}\r\n$retryAfterHeader\r\n$body"
    }

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
