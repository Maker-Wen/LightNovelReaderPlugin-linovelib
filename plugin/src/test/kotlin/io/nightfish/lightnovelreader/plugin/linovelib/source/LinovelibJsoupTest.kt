package io.nightfish.lightnovelreader.plugin.linovelib.source

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class LinovelibJsoupTest {
    @Test
    fun clientAcceptsJavascriptSearchGuardResponse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "jieqiSearchJs=test-token".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/javascript")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val host = "http://127.0.0.1:${server.address.port}"
            val client = LinovelibHttpClient(
                host = host,
                diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
            )

            assertEquals("jieqiSearchJs=test-token", client.loadHtml("$host/"))
        } finally {
            server.stop(0)
        }
    }
}
