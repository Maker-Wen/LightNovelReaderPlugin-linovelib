package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shares recent HTML for one URL. The first caller owns the fetch. */
internal class LinovelibRecentHtml(
    private val accepts: (String) -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Page(val html: String, val storedAtMillis: Long)

    private val lock = Any()
    private var cached: Page? = null
    private var inFlight: CompletableDeferred<String>? = null

    suspend fun load(allowCached: Boolean = true, fetch: suspend () -> String): String {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (request, ownsFetch) = synchronized(lock) {
                if (allowCached) {
                    cached?.takeIf { nowMillis() - it.storedAtMillis < CACHE_MILLIS }?.let { return it.html }
                }
                val pending = inFlight
                if (pending != null) {
                    pending to false
                } else {
                    cached = null
                    CompletableDeferred<String>().also { inFlight = it } to true
                }
            }
            if (!ownsFetch) {
                try {
                    return request.await()
                } catch (_: CancellationException) {
                    // If only the owner was cancelled, an active waiter can take over.
                    currentCoroutineContext().ensureActive()
                }
                continue
            }

            var page: Page? = null
            val result = runCatching {
                val html = fetch()
                val valid = try {
                    accepts(html)
                } catch (failure: RuntimeException) {
                    failure.rethrowIfCancellation()
                    false
                }
                currentCoroutineContext().ensureActive()
                if (valid) page = Page(html, nowMillis())
                html
            }
            synchronized(lock) {
                cached = page
                inFlight = null
            }
            result.fold(request::complete, request::completeExceptionally)
            return result.getOrThrow()
        }
    }

    private companion object {
        const val CACHE_MILLIS = 15_000L
    }
}
