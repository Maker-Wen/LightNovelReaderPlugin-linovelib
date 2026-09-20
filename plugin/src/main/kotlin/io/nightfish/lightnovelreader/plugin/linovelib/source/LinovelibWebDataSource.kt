package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.navigation.NavController
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.Connection
import java.io.File
import java.io.IOException

internal val LINOVELIB_SOURCE_ID = Identifier(
    namespace = "lightnovelreader",
    id = "linovelib"
)

@Suppress("unused")
@WebDataSource(
    name = "Linovelib",
    provider = "linovelib.com"
)
class LinovelibWebDataSource(
    private val context: Context
) : WebBookDataSource {
    private val site = LinovelibDataSourceConfiguration.site(context)
    private val parser = LinovelibHtmlParser(host = site.host)
    private val diagnostics = LinovelibDiagnostics()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val offlineStateFlow = MutableStateFlow(true)
    private val requestCoordinator = LinovelibRequestCoordinator()
    private val sessionCookies = mutableMapOf<String, String>()
    private var offlineMonitorJob: Job? = null
    override val permits: Int = 1
    override val cache: Cache = Cache(
        maxCountEachType = LinovelibDataSourceConfiguration.cacheEntriesPerType,
        timeout = LinovelibDataSourceConfiguration.cacheTimeoutMillis
    )
    override val id: Identifier = LINOVELIB_SOURCE_ID
    override val offLine: Boolean get() = offlineStateFlow.value
    override val isOffLineFlow: StateFlow<Boolean> = offlineStateFlow
    private val linovelibSearchProvider = LinovelibSearchProvider(
        ::getHtml,
        ::getSearchHtml,
        parser,
        diagnostics,
        site.host
    )
    override val searchProvider: SearchProvider = linovelibSearchProvider
    private val linovelibExplorePageProvider = LinovelibExplorePageProvider(
        ::getHtml,
        parser,
        linovelibSearchProvider,
        site.host
    )
    override val explorePageProvider: ExplorePageProvider = linovelibExplorePageProvider
    private val imageStore = LinovelibImageStore(
        directory = File(context.filesDir, "linovelib/chapter-images"),
        diagnostics = diagnostics,
        referer = site.host
    )
    override val imageHeader: Map<String, String> = mapOf(
        "User-Agent" to CONTENT_USER_AGENT,
        "Referer" to site.host,
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    )

    @Synchronized
    override fun onLoad() {
        if (!LinovelibDataSourceConfiguration.shouldStartOfflineMonitor(offlineMonitorJob?.isActive == true)) return
        offlineMonitorJob = scope.launch {
            offlineStateFlow.value = isOffLine()
        }
    }

    override suspend fun isOffLine(): Boolean = withContext(Dispatchers.IO) {
        val offline = runCatching {
            executeRequest(
                url = site.host,
                userAgent = USER_AGENT,
                referrer = site.host,
                acceptLanguage = site.acceptLanguage
            ).statusCode() !in 200..399
        }.getOrElse { true }
        offlineStateFlow.value = offline
        offline
    }

    override suspend fun getBookInformation(
        id: String
    ): Result<BookInformation, WebRequestError> = withContext(Dispatchers.IO) {
        runCatching {
            parser.parseBookInformation(id, getHtml(LinovelibUrls.book(site.host, id))).toBookInformation()
                ?: fallbackBookInformation(id)
                ?: error("Book information is empty")
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = { error ->
                error.rethrowIfCancellation()
                Log.e(TAG, "Failed to get book information: $id", error)
                diagnostics.error("BOOK_ERROR", error, mapOf("bookId" to id))
                Err(WebRequestError("Book request failed", "无法获取书本信息(id=$id)", error))
            }
        )
    }

    override suspend fun getBookVolumes(
        id: String
    ): Result<BookVolumes, WebRequestError> = withContext(Dispatchers.IO) {
        runCatching {
            parser.parseCatalog(id, getHtml(LinovelibUrls.catalog(site.host, id))).toBookVolumes()
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = { error ->
                error.rethrowIfCancellation()
                Log.e(TAG, "Failed to get book volumes: $id", error)
                diagnostics.error("CATALOG_ERROR", error, mapOf("bookId" to id))
                Err(WebRequestError("Catalog request failed", "无法获取书本目录(id=$id)", error))
            }
        )
    }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ): Result<ChapterContent, WebRequestError> = withContext(Dispatchers.IO) {
        runCatching {
            getChapterContentPages(chapterId, bookId).toChapterContent()
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = { error ->
                error.rethrowIfCancellation()
                Log.e(TAG, "Failed to get chapter content: bookId=$bookId, chapterId=$chapterId", error)
                diagnostics.error(
                    "CHAPTER_ERROR",
                    error,
                    mapOf("bookId" to bookId, "chapterId" to chapterId)
                )
                Err(WebRequestError("Chapter request failed", "无法获取章节内容(id=$chapterId)", error))
            }
        )
    }

    override suspend fun getCoverUriInVolume(
        bookId: String,
        volume: Volume,
        volumeChapterContentMap: MutableMap<String, ChapterContent>,
        context: Context
    ): Uri? = findVolumeCoverUri(volume, volumeChapterContentMap)?.let(Uri::parse)

    override fun progressBookTagClick(tag: String, navController: NavController) {
        val pageId = linovelibExplorePageProvider.registerRelatedPage(tag)
        diagnostics.info("RELATED_NAV_START", mapOf("tag" to tag, "pageId" to pageId))
        val route = LinovelibRelatedNavigation.createExpandedRoute(navController.javaClass.classLoader, pageId)
        navController.navigate(route)
        diagnostics.info("RELATED_NAV_DONE", mapOf("mode" to "route", "pageId" to pageId))
    }

    private suspend fun getHtml(url: String): String = executeRequest(
        url = url,
        userAgent = USER_AGENT,
        referrer = site.host,
        acceptLanguage = site.acceptLanguage
    ).body()

    private suspend fun getContentHtml(url: String): String = executeRequest(
        url = url,
        userAgent = CONTENT_USER_AGENT,
        referrer = "${site.host}/",
        acceptLanguage = site.acceptLanguage,
        cookies = mapOf("night" to "0"),
        cacheControl = true
    ).body()

    private suspend fun getSearchHtml(keyword: String): LinovelibSearchResponse {
        val searchKeyword = site.searchKeyword(keyword)
        val guardJs = executeRequest(
            url = "${site.host}/search.html?search_guard=js",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${site.host}/",
            acceptLanguage = site.acceptLanguage,
            cacheControl = true,
            includeSessionCookies = false
        )
        val jsToken = LinovelibSearchGuard.extractCookieValue(guardJs.body(), "jieqiSearchJs")
        require(jsToken.isNotEmpty()) { "Missing jieqiSearchJs search guard cookie" }

        val guardCss = executeRequest(
            url = "${site.host}/search.html?search_guard=css",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${site.host}/",
            acceptLanguage = site.acceptLanguage,
            cacheControl = true,
            includeSessionCookies = false
        )
        val cssToken = guardCss.cookies()["jieqiSearchCss"].orEmpty()
        require(cssToken.isNotEmpty()) { "Missing jieqiSearchCss search guard cookie" }

        val redeem = executeRequest(
            url = "${site.host}/search.html?search_guard=redeem&r=${System.currentTimeMillis()}",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${site.host}/",
            acceptLanguage = site.acceptLanguage,
            cookies = LinovelibSearchGuard.guardCookies(jsToken, cssToken),
            cacheControl = true,
            includeSessionCookies = false
        )
        val ticketToken = redeem.cookies()["jieqiSearchTicket"].orEmpty()
        require(ticketToken.isNotEmpty()) { "Missing jieqiSearchTicket search cookie" }
        diagnostics.info(
            "SEARCH_GUARD_OK",
            mapOf("keyword" to keyword, "convertedKeyword" to searchKeyword)
        )

        val encodedKeyword = java.net.URLEncoder.encode(searchKeyword, "UTF-8")
        val response = executeRequest(
            url = "${site.host}/search.html?searchkey=$encodedKeyword",
            userAgent = CONTENT_USER_AGENT,
            referrer = "${site.host}/",
            acceptLanguage = site.acceptLanguage,
            cookies = LinovelibSearchGuard.ticketCookies(ticketToken),
            cacheControl = true,
            includeSessionCookies = false
        )
        return LinovelibSearchResponse(
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
        includeSessionCookies: Boolean = true
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
                        attempt = attempt
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
                    offlineStateFlow.value = false
                    return response
                }

                val retryDelay = LinovelibRequestPolicy.retryDelayMillis(
                    statusCode = response.statusCode(),
                    retryAfter = response.header("Retry-After"),
                    attempt = attempt
                )
                if (retryDelay == null || attempt == LinovelibRequestPolicy.maxAttempts - 1) {
                    throw IOException("HTTP ${response.statusCode()} for ${response.url()}")
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
            if (throwable is IOException) offlineStateFlow.value = true
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
        attempt: Int
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
        response.also {
            if (includeSessionCookies) sessionCookies.putAll(response.cookies())
        }
    }

    private suspend fun getChapterContentPages(chapterId: String, bookId: String): ParsedChapterContent {
        val websiteChapterId = LinovelibChapterIds.forWebsite(chapterId)
        val pages = mutableListOf<ParsedChapterContent>()
        val visitedUrls = mutableSetOf<String>()
        var nextUrl = LinovelibUrls.fullChapter(site.host, bookId, websiteChapterId)
        var pageCount = 0
        diagnostics.info(
            "CHAPTER_START",
            linkedMapOf(
                "bookId" to bookId,
                "chapterId" to chapterId,
                "websiteChapterId" to websiteChapterId,
                "url" to nextUrl
            )
        )

        while (true) {
            if (nextUrl.isBlank()) {
                diagnostics.info("CHAPTER_STOP", mapOf("reason" to "blank-url", "pages" to pageCount))
                break
            }
            if (pageCount >= MAX_CHAPTER_PAGES) {
                diagnostics.info("CHAPTER_STOP", mapOf("reason" to "page-limit", "pages" to pageCount))
                break
            }
            if (!visitedUrls.add(nextUrl)) {
                diagnostics.info(
                    "CHAPTER_STOP",
                    mapOf("reason" to "repeated-url", "pages" to pageCount, "url" to nextUrl)
                )
                break
            }
            pageCount++
            val page = parser.parseChapterContent(
                chapterId = websiteChapterId,
                html = getContentHtml(nextUrl),
                baseUrl = site.host,
                restoreParagraphOrder = true
            )
            pages.add(page)
            val candidate = absoluteUrl(page.nextPageUrl, site.host)
            val candidateChapterId = parser.chapterIdFromHref(candidate)
            val isSameChapterPage = candidate.isNotBlank() && candidateChapterId == websiteChapterId
            val textBlocks = page.blocks.filterIsInstance<ParsedContentBlock.Text>()
            diagnostics.info(
                "CHAPTER_PAGE",
                linkedMapOf(
                    "page" to pageCount,
                    "url" to nextUrl,
                    "blocks" to page.blocks.size,
                    "textBlocks" to textBlocks.size,
                    "textChars" to textBlocks.sumOf { it.text.length },
                    "images" to page.blocks.count { it is ParsedContentBlock.Image },
                    "nextUrl" to candidate,
                    "nextId" to candidateChapterId,
                    "sameChapterPage" to isSameChapterPage
                )
            )
            if (!isSameChapterPage) {
                diagnostics.info(
                    "CHAPTER_STOP",
                    mapOf(
                        "reason" to if (candidate.isBlank()) "no-next-url" else "next-chapter",
                        "pages" to pageCount,
                        "nextUrl" to candidate,
                        "nextId" to candidateChapterId
                    )
                )
                break
            }
            nextUrl = candidate
        }

        val first = pages.firstOrNull() ?: return ParsedChapterContent(
            id = chapterId,
            title = "",
            previousChapterId = "",
            nextChapterId = "",
            nextPageUrl = "",
            blocks = emptyList()
        )
        val last = pages.last()
        diagnostics.info(
            "CHAPTER_DONE",
            linkedMapOf(
                "bookId" to bookId,
                "chapterId" to chapterId,
                "websiteChapterId" to websiteChapterId,
                "pages" to pages.size,
                "blocks" to pages.sumOf { it.blocks.size },
                "textChars" to pages.sumOf { page ->
                    page.blocks.filterIsInstance<ParsedContentBlock.Text>().sumOf { it.text.length }
                },
                "images" to pages.sumOf { page -> page.blocks.count { it is ParsedContentBlock.Image } },
                "previousId" to first.previousChapterId,
                "nextId" to last.nextChapterId
            )
        )
        return ParsedChapterContent(
            id = chapterId,
            title = first.title,
            previousChapterId = LinovelibChapterIds.forApp(first.previousChapterId),
            nextChapterId = LinovelibChapterIds.forApp(last.nextChapterId),
            nextPageUrl = last.nextPageUrl,
            blocks = pages.flatMap { it.blocks }
        )
    }

    private fun absoluteUrl(url: String, host: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> host + url
            else -> "$host/$url"
        }
    }

    private fun elapsedMilliseconds(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private fun ParsedBookInformation.toBookInformation(): BookInformation? {
        if (title.isEmpty()) return null
        return BookInformation(
            id = id,
            title = title,
            subtitle = subtitle,
            coverUri = coverUrl.takeIf(String::isNotEmpty)?.let(Uri::parse) ?: Uri.EMPTY,
            author = author,
            description = description,
            tags = LinovelibRelatedSearch.displayTags(author, tags, publishingHouse),
            publishingHouse = "",
            wordCount = WordCount(wordCount),
            lastUpdated = lastUpdated.atStartOfDay(),
            isComplete = isComplete
        )
    }

    private fun fallbackBookInformation(id: String): BookInformation? =
        parser.cachedBookInformation(id)?.toBookInformation()
            ?: parser.cachedExploreBook(id)?.toBookInformation()

    private fun ParsedExploreBook.toBookInformation(): BookInformation? {
        if (title.isBlank()) return null
        return BookInformation(
            id = id,
            title = title,
            subtitle = "",
            coverUri = coverUrl.takeIf(String::isNotEmpty)?.let(Uri::parse) ?: Uri.EMPTY,
            author = author,
            description = "",
            tags = emptyList(),
            publishingHouse = "",
            wordCount = WordCount(0),
            lastUpdated = LinovelibDates.unknownDateTime(),
            isComplete = false
        )
    }

    private fun ParsedCatalog.toBookVolumes(): BookVolumes =
        BookVolumes(
            bookId = bookId,
            volumes = volumes.mapIndexed { index, volume ->
                Volume(
                    volumeId = "$bookId-${index + 1}",
                    volumeTitle = volume.title,
                    chapters = volume.chapters.map {
                        ChapterInformation(
                            id = LinovelibChapterIds.forApp(it.id),
                            title = it.title
                        )
                    }
                )
            }
        )

    private suspend fun ParsedChapterContent.toChapterContent(): ChapterContent {
        val localizedBlocks = imageStore.localize(LinovelibContentFormatter.format(blocks))
        val content = ContentBuilder().apply {
            localizedBlocks.forEach { block ->
                when (block) {
                    is ParsedContentBlock.Text -> simpleText(block.text)
                    is ParsedContentBlock.Image -> image(Uri.parse(block.url))
                }
            }
        }.build()

        return ChapterContent(
            id = id,
            title = title,
            content = content,
            prevChapter = previousChapterId,
            nextChapter = nextChapterId
        )
    }

    private companion object {
        const val TAG = "LinovelibWebDataSource"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"
        const val CONTENT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36 EdgA/135.0.0.0"
        const val MAX_CHAPTER_PAGES = 100
    }
}

internal fun findVolumeCoverUri(
    volume: Volume,
    volumeChapterContentMap: Map<String, ChapterContent>
): String? = volume.chapters
    .asSequence()
    .filter { chapter -> chapter.title.trim().lowercase() in ILLUSTRATION_TITLES }
    .mapNotNull { chapter -> volumeChapterContentMap[chapter.id] }
    .flatMap { chapter -> chapter.content["components"]?.jsonArray.orEmpty().asSequence() }
    .mapNotNull { component ->
        component.jsonObject
            .takeIf { it["id"]?.jsonPrimitive?.content == ImageComponentData.id.toString() }
            ?.get("data")
            ?.jsonObject
            ?.get("uri")
            ?.jsonPrimitive
            ?.content
    }
    .firstOrNull()

private val ILLUSTRATION_TITLES = setOf(
    "插图", "插圖", "插画", "插畫", "彩页", "彩頁", "彩图", "彩圖",
    "illustration", "illustrations"
)
