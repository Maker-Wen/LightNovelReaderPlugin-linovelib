package io.nightfish.lightnovelreader.plugin.linovelib.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal data class RecordedLinovelibRequest(
    val path: String,
    val query: String?,
    val headers: Map<String, String>
) {
    val cookies: Map<String, String> get() = headers["cookie"].orEmpty().split(';')
        .mapNotNull { part ->
            val value = part.trim().split('=', limit = 2)
            if (value.size == 2) value[0] to value[1] else null
        }.toMap()
}

internal class LinovelibHttpTestSite(handler: (HttpExchange) -> Unit) : Closeable {
    val requests = CopyOnWriteArrayList<RecordedLinovelibRequest>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += RecordedLinovelibRequest(
                exchange.requestURI.path, exchange.requestURI.rawQuery,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString("; ") }
            )
            exchange.use { handler(it) }
        }
        start()
    }
    val host = "http://127.0.0.1:${server.address.port}"

    fun client(diagnostics: LinovelibDiagnostics = LinovelibDiagnostics { _, _, _, _ -> }): LinovelibHttpClient {
        // Advance the existing coordinator's clock rather than waiting through retry delays.
        // Its timing, cooldown and fairness guarantees have their own behavioural tests.
        val clock = AtomicLong(1_000_000)
        return LinovelibHttpClient(
            host = host, diagnostics = diagnostics,
            requestCoordinator = LinovelibRequestCoordinator(0) { clock.addAndGet(60_001) }
        )
    }

    override fun close() = server.stop(0)
}

internal fun HttpExchange.respond(status: Int, body: String = "Response", contentType: String = "text/html; charset=UTF-8") {
    responseHeaders.add("Content-Type", contentType)
    val bytes = body.toByteArray()
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
