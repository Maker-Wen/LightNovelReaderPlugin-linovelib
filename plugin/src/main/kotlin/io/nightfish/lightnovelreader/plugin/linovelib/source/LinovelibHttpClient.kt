package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.IOException

internal const val LINOVELIB_CONTENT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36 EdgA/135.0.0.0"

internal data class LinovelibHtmlResponse(val finalUrl: String, val html: String)

internal class LinovelibHttpStatusException(statusCode: Int, url: String) :
    IOException("HTTP $statusCode for $url")

internal class LinovelibHttpClient(
    private val host: String = LinovelibUrls.HOST,
    private val diagnostics: LinovelibDiagnostics = LinovelibDiagnostics(),
    private val requestCoordinator: LinovelibRequestCoordinator = LinovelibRequestCoordinator()
) {
    // Until a request confirms availability, let API 3 hosts show their loading state.
    private val offlineStateFlow = MutableStateFlow(false)
    val isOffLineFlow: StateFlow<Boolean> = offlineStateFlow.asStateFlow()
    private val sessionCookies = mutableMapOf<String, String>()
    private val parser = LinovelibHtmlParser(host)
    private val defaultWenkuUrl = LinovelibUrls.wenku(host, "lastupdate", 1)
    private val homepage = LinovelibRecentHtml(accepts = { parser.parseExploreRows(it).isNotEmpty() })
    private val defaultWenku = LinovelibRecentHtml(accepts = { parser.parseListPage(it) != null })

    suspend fun checkOffline(): Boolean = checkHomepage(allowCached = false)

    suspend fun checkStartupOffline(): Boolean = checkHomepage(allowCached = !offlineStateFlow.value)

    private suspend fun checkHomepage(allowCached: Boolean): Boolean = withContext(Dispatchers.IO) {
        var performedProbe = false
        try {
            homepage.load(allowCached) {
                performedProbe = true
                loadPlainHtml(host, availabilityCheck = true)
            }
            false
        } catch (_: IOException) {
            if (performedProbe || offlineStateFlow.value) return@withContext true
            // A failed recommendation load did not publish availability. Probe normally so
            // the result is ordered with other network responses by the request coordinator.
            try {
                loadPlainHtml(host, availabilityCheck = true)
                false
            } catch (_: IOException) {
                true
            }
        }
    }

    suspend fun loadHtml(url: String): String = when (url) {
        host -> homepage.load(allowCached = !offlineStateFlow.value) { loadPlainHtml(url) }
        defaultWenkuUrl -> defaultWenku.load { loadPlainHtml(url) }
        else -> loadPlainHtml(url)
    }

    private suspend fun loadPlainHtml(url: String, availabilityCheck: Boolean = false): String = executeRequest(
        url = url,
        userAgent = USER_AGENT,
        referrer = host,
        availabilityCheck = availabilityCheck
    ).html

    suspend fun loadChapterPage(url: String): LinovelibHtmlResponse = executeRequest(
        url = url,
        userAgent = LINOVELIB_CONTENT_USER_AGENT,
        referrer = "${host}/",
        cookies = { mapOf("night" to "0") },
        cacheControl = true
    ).let { LinovelibHtmlResponse(it.raw.url().toString(), it.html) }

    fun newSearchLoader(): suspend (String) -> LinovelibHtmlResponse {
        var searchGuards: Map<String, String>? = null
        return { url ->
            var reusableGuards = searchGuards
            var usedCachedGuards = false
            suspend fun requestPage(): LinovelibHtmlResponse = loadSearchPage(url) {
                val cachedGuards = reusableGuards
                // Only the first attempt may reuse a pair. A retry takes the fresh path.
                reusableGuards = null
                val ticketFromCachedGuards = cachedGuards?.let { redeemSearchTicket(it) }.orEmpty()
                usedCachedGuards = ticketFromCachedGuards.isNotEmpty()
                val ticket = ticketFromCachedGuards.ifEmpty {
                    val freshGuards = loadSearchGuards()
                    redeemSearchTicket(freshGuards).also {
                        require(it.isNotEmpty()) { "Missing jieqiSearchTicket search cookie" }
                        searchGuards = freshGuards
                    }
                }
                diagnostics.info("SEARCH_GUARD_OK", mapOf("url" to url))
                ticket
            }

            val response = requestPage()
            // The site can return HTTP 200 with an empty body when it rejects a ticket.
            if (usedCachedGuards && response.html.isBlank()) requestPage() else response
        }
    }

    private suspend fun loadSearchPage(url: String, ticket: suspend () -> String): LinovelibHtmlResponse = executeRequest(
        url = url,
        userAgent = LINOVELIB_CONTENT_USER_AGENT,
        referrer = "${host}/",
        cookies = { mapOf("jieqiSearchTicket" to ticket()) },
        cacheControl = true,
        includeSessionCookies = false
    ).let { LinovelibHtmlResponse(it.raw.url().toString(), it.html) }

    private suspend fun loadSearchGuards(): Map<String, String> {
        val guardJs = executeRequest(
            url = "${host}/search.html?search_guard=js",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            cacheControl = true,
            includeSessionCookies = false
        )
        val jsToken = LinovelibSearchGuard.extractJsToken(guardJs.html)
        require(jsToken.isNotEmpty()) { "Missing jieqiSearchJs search guard cookie" }

        val guardCss = executeRequest(
            url = "${host}/search.html?search_guard=css",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            cacheControl = true,
            includeSessionCookies = false
        )
        val cssToken = guardCss.raw.cookies()["jieqiSearchCss"].orEmpty()
        require(cssToken.isNotEmpty()) { "Missing jieqiSearchCss search guard cookie" }
        return LinovelibSearchGuard.guardCookies(jsToken, cssToken)
    }

    private suspend fun redeemSearchTicket(guards: Map<String, String>): String {
        val redeem = executeRequest(
            url = "${host}/search.html?search_guard=redeem&r=${System.currentTimeMillis()}",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            cookies = { guards },
            cacheControl = true,
            includeSessionCookies = false
        )
        return redeem.raw.cookies()["jieqiSearchTicket"].orEmpty()
    }

    private suspend fun executeRequest(
        url: String,
        userAgent: String,
        referrer: String,
        cookies: suspend () -> Map<String, String> = { emptyMap() },
        cacheControl: Boolean = false,
        includeSessionCookies: Boolean = true,
        availabilityCheck: Boolean = false
    ): DecodedResponse {
        val startedAt = System.nanoTime()
        try {
            var attempt = 0
            while (true) {
                // Search tickets are consumed by the server, including failed result attempts.
                // Acquire cookies outside the request slot and repeat this for every retry.
                val requestCookies = cookies()
                val response = try {
                    executeSingleRequest(
                        url = url,
                        userAgent = userAgent,
                        referrer = referrer,
                        cookies = requestCookies,
                        cacheControl = cacheControl,
                        includeSessionCookies = includeSessionCookies,
                        attempt = attempt,
                        availabilityCheck = availabilityCheck
                    )
                } catch (throwable: Throwable) {
                    if (throwable !is IOException || attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                        throw throwable
                    }
                    val retryDelay = LinovelibRequestPolicy.networkRetryDelayMillis(attempt)
                    diagnostics.info(
                        "HTTP_RETRY",
                        mapOf(
                            "requested" to url,
                            "attempt" to attempt + 1,
                            "delayMs" to retryDelay,
                            "reason" to throwable.javaClass.simpleName
                        )
                    )
                    attempt++
                    continue
                }

                val html = response.body()
                val successful = diagnostics.isSuccessfulHttpStatus(response.statusCode())
                diagnostics.info(
                    if (successful) "HTTP_OK" else "HTTP_STATUS_ERROR",
                    linkedMapOf(
                        "requested" to url,
                        "final" to response.url().toString(),
                        "status" to response.statusCode(),
                        "chars" to html.length,
                        "elapsedMs" to elapsedMilliseconds(startedAt),
                        "loadFailure" to diagnostics.hasLoadFailure(html)
                    )
                )
                if (successful) {
                    return DecodedResponse(response, html)
                }

                val retryDelay = LinovelibRequestPolicy.retryDelayMillis(
                    statusCode = response.statusCode(),
                    retryAfter = response.header("Retry-After"),
                    attempt = attempt
                )
                if (retryDelay == null || attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                    throw LinovelibHttpStatusException(response.statusCode(), response.url().toString())
                }
                diagnostics.info(
                    "HTTP_RETRY",
                    mapOf(
                        "requested" to url,
                        "status" to response.statusCode(),
                        "attempt" to attempt + 1,
                        "delayMs" to retryDelay
                    )
                )
                attempt++
            }
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            diagnostics.error(
                "HTTP_ERROR",
                throwable,
                linkedMapOf(
                    "requested" to url,
                    "elapsedMs" to elapsedMilliseconds(startedAt)
                )
            )
            throw throwable
        }
    }

    private suspend fun executeSingleRequest(
        url: String,
        userAgent: String,
        referrer: String,
        cookies: Map<String, String>,
        cacheControl: Boolean,
        includeSessionCookies: Boolean,
        attempt: Int,
        availabilityCheck: Boolean
    ): Connection.Response = requestCoordinator.execute(
        cooldownAfter = { result ->
            // A final 429 still throttles subsequent resources after retries are exhausted.
            if (attempt == LinovelibRequestPolicy.maxAttempts - 1 && result.getOrNull()?.statusCode() != 429) {
                null
            } else {
                result.fold(
                    onSuccess = { response ->
                        LinovelibRequestPolicy.retryDelayMillis(
                            statusCode = response.statusCode(),
                            retryAfter = response.header("Retry-After"),
                            attempt = attempt
                        )
                    },
                    onFailure = { throwable ->
                        if (throwable is IOException) {
                            LinovelibRequestPolicy.networkRetryDelayMillis(attempt)
                        } else {
                            null
                        }
                    }
                )
            }
        }
    ) {
        try {
            val requestCookies = if (includeSessionCookies) {
                sessionCookies.toMutableMap().apply { putAll(cookies) }
            } else {
                cookies
            }
            val connection = Jsoup.connect(url)
                .userAgent(userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .referrer(referrer)
                .cookies(requestCookies)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .timeout(12_000)
            if (cacheControl) connection.header("Cache-Control", "no-cache")
            // Buffer within the request slot before publishing cookies or availability.
            val response = withContext(Dispatchers.IO) { connection.execute().readFully() }
            currentCoroutineContext().ensureActive()
            if (includeSessionCookies) sessionCookies.putAll(response.cookies())
            // Publish while this request still owns the slot. A late outer callback
            // must not overwrite the result of a newer request or availability check.
            if (diagnostics.isSuccessfulHttpStatus(response.statusCode())) {
                offlineStateFlow.value = false
            } else if (availabilityCheck) {
                val retryDelay = LinovelibRequestPolicy.retryDelayMillis(
                    response.statusCode(), response.header("Retry-After"), attempt
                )
                if (retryDelay == null || attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                    offlineStateFlow.value = true
                }
            }
            response
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            currentCoroutineContext().ensureActive()
            if (availabilityCheck && throwable is IOException && attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                offlineStateFlow.value = true
            }
            throw throwable
        }
    }

    private class DecodedResponse(val raw: Connection.Response, val html: String)

    private fun elapsedMilliseconds(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.7"
    }
}
