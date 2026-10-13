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

internal class LinovelibHttpStatusException(val statusCode: Int, val url: String) :
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

    suspend fun checkOffline(): Boolean = withContext(Dispatchers.IO) {
        try {
            executeRequest(
                url = host,
                userAgent = USER_AGENT,
                referrer = host,
                acceptLanguage = ACCEPT_LANGUAGE,
                availabilityCheck = true
            )
            false
        } catch (_: IOException) {
            true
        }
    }

    suspend fun loadHtml(url: String): String = executeRequest(
        url = url,
        userAgent = USER_AGENT,
        referrer = host,
        acceptLanguage = ACCEPT_LANGUAGE
    ).body()

    suspend fun loadChapterPage(url: String): LinovelibHtmlResponse = executeRequest(
        url = url,
        userAgent = LINOVELIB_CONTENT_USER_AGENT,
        referrer = "${host}/",
        acceptLanguage = ACCEPT_LANGUAGE,
        cookies = mapOf("night" to "0"),
        cacheControl = true
    ).let { LinovelibHtmlResponse(it.url().toString(), it.body()) }

    suspend fun search(keyword: String): LinovelibHtmlResponse {
        val guardJs = executeRequest(
            url = "${host}/search.html?search_guard=js",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cacheControl = true,
            includeSessionCookies = false
        )
        val jsToken = LinovelibSearchGuard.extractCookieValue(guardJs.body(), "jieqiSearchJs")
        require(jsToken.isNotEmpty()) { "Missing jieqiSearchJs search guard cookie" }

        val guardCss = executeRequest(
            url = "${host}/search.html?search_guard=css",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cacheControl = true,
            includeSessionCookies = false
        )
        val cssToken = guardCss.cookies()["jieqiSearchCss"].orEmpty()
        require(cssToken.isNotEmpty()) { "Missing jieqiSearchCss search guard cookie" }

        val redeem = executeRequest(
            url = "${host}/search.html?search_guard=redeem&r=${System.currentTimeMillis()}",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cookies = LinovelibSearchGuard.guardCookies(jsToken, cssToken),
            cacheControl = true,
            includeSessionCookies = false
        )
        val ticketToken = redeem.cookies()["jieqiSearchTicket"].orEmpty()
        require(ticketToken.isNotEmpty()) { "Missing jieqiSearchTicket search cookie" }
        diagnostics.info(
            "SEARCH_GUARD_OK",
            mapOf("keyword" to keyword)
        )

        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val response = executeRequest(
            url = "${host}/search.html?searchkey=$encodedKeyword",
            userAgent = LINOVELIB_CONTENT_USER_AGENT,
            referrer = "${host}/",
            acceptLanguage = ACCEPT_LANGUAGE,
            cookies = LinovelibSearchGuard.ticketCookies(ticketToken),
            cacheControl = true,
            includeSessionCookies = false
        )
        return LinovelibHtmlResponse(
            finalUrl = response.url().toString(),
            html = response.body()
        )
    }

    private suspend fun executeRequest(
        url: String,
        userAgent: String,
        referrer: String,
        acceptLanguage: String,
        cookies: Map<String, String> = emptyMap(),
        cacheControl: Boolean = false,
        includeSessionCookies: Boolean = true,
        availabilityCheck: Boolean = false
    ): Connection.Response {
        val startedAt = System.nanoTime()
        return try {
            var lastNetworkError: Throwable? = null
            for (attempt in 0 until LinovelibRequestPolicy.maxAttempts) {
                val response = try {
                    executeSingleRequest(
                        url = url,
                        userAgent = userAgent,
                        referrer = referrer,
                        acceptLanguage = acceptLanguage,
                        cookies = cookies,
                        cacheControl = cacheControl,
                        includeSessionCookies = includeSessionCookies,
                        attempt = attempt,
                        availabilityCheck = availabilityCheck
                    )
                } catch (throwable: Throwable) {
                    throwable.rethrowIfCancellation()
                    lastNetworkError = throwable
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
                    continue
                }

                val html = response.body()
                val inspection = diagnostics.inspectHtml(html)
                val successful = diagnostics.isSuccessfulHttpStatus(response.statusCode())
                diagnostics.info(
                    if (successful) "HTTP_OK" else "HTTP_STATUS_ERROR",
                    linkedMapOf(
                        "requested" to url,
                        "final" to response.url().toString(),
                        "status" to response.statusCode(),
                        "chars" to inspection.characters,
                        "elapsedMs" to elapsedMilliseconds(startedAt),
                        "loadFailure" to inspection.hasLoadFailure
                    )
                )
                if (successful) {
                    return response
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
            }
            throw lastNetworkError ?: IOException("Request attempts exhausted for $url")
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
        acceptLanguage: String,
        cookies: Map<String, String>,
        cacheControl: Boolean,
        includeSessionCookies: Boolean,
        attempt: Int,
        availabilityCheck: Boolean
    ): Connection.Response = requestCoordinator.execute(
        cooldownAfter = { result ->
            if (attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                null
            } else {
                result.fold(
                    onSuccess = { response ->
                        if (diagnostics.isSuccessfulHttpStatus(response.statusCode())) {
                            null
                        } else {
                            LinovelibRequestPolicy.retryDelayMillis(
                                statusCode = response.statusCode(),
                                retryAfter = response.header("Retry-After"),
                                attempt = attempt
                            )
                        }
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
                .header("Accept-Language", acceptLanguage)
                .referrer(referrer)
                .cookies(requestCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .acceptLinovelibContentTypes()
                .timeout(12_000)
            if (cacheControl) connection.header("Cache-Control", "no-cache")
            val response = withContext(Dispatchers.IO) { connection.execute() }
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

    private fun elapsedMilliseconds(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.7"
    }
}
