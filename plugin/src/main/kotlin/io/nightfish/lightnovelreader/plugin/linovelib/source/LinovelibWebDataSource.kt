package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.Context
import android.net.Uri
import androidx.navigation.NavController
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI

internal val LINOVELIB_SOURCE_ID = "linovelib".hashCode()

@Suppress("unused")
@WebDataSource(
    name = "Linovelib",
    provider = "linovelib.com"
)
class LinovelibWebDataSource internal constructor(
    private val context: Context,
    private val metadata: LinovelibMetadataStore,
    private val diagnostics: LinovelibDiagnostics,
    detailHtmlLoader: (suspend (String) -> String)?
) : WebBookDataSource {
    // Keep the host's public injection constructor unchanged.
    constructor(context: Context) : this(context, LinovelibMetadataStore(), LinovelibDiagnostics(), null)

    private val parser = LinovelibHtmlParser(host = LinovelibUrls.HOST)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = LinovelibHttpClient(diagnostics = diagnostics)
    private val details = LinovelibBookDetails(
        detailHtmlLoader ?: httpClient::loadHtml, parser, diagnostics, metadata = metadata
    )
    private var offlineMonitorJob: Job? = null
    override val permits: Int = 1
    override val cache: Cache = Cache(
        maxCountEachType = LinovelibDataSourceConfiguration.cacheEntriesPerType,
        timeout = LinovelibDataSourceConfiguration.cacheTimeoutMillis
    )
    override val id: Int = LINOVELIB_SOURCE_ID
    override val offLine: Boolean get() = httpClient.isOffLineFlow.value
    override val isOffLineFlow: StateFlow<Boolean> = httpClient.isOffLineFlow
    private val linovelibSearchProvider = LinovelibSearchProvider(
        httpClient::loadHtml,
        { keyword -> httpClient.search(keyword).let { LinovelibSearchResponse(it.finalUrl, it.html) } },
        parser,
        diagnostics,
        LinovelibUrls.HOST,
        metadata,
        details
    )
    override val searchProvider: SearchProvider = linovelibSearchProvider
    private val linovelibExplorePageProvider = LinovelibExplorePageProvider(
        httpClient::loadHtml,
        parser,
        linovelibSearchProvider::searchBookIds,
        LinovelibUrls.HOST,
        metadata
    )
    override val explorePageProvider: ExplorePageProvider = linovelibExplorePageProvider
    private val imageStore = LinovelibImageStore(
        directory = File(context.filesDir, "linovelib/chapter-images"),
        diagnostics = diagnostics,
        referer = LinovelibUrls.HOST
    )
    override val imageHeader: Map<String, String> = mapOf(
        "User-Agent" to LINOVELIB_CONTENT_USER_AGENT,
        "Referer" to LinovelibUrls.HOST,
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    )

    @Synchronized
    override fun onLoad() {
        if (!LinovelibDataSourceConfiguration.shouldStartOfflineMonitor(offlineMonitorJob?.isActive == true)) return
        offlineMonitorJob = scope.launch {
            httpClient.checkOffline()
        }
    }

    override suspend fun isOffLine(): Boolean = httpClient.checkOffline()

    override suspend fun getBookInformation(id: String): BookInformation = withContext(Dispatchers.IO) {
        runCatching {
            details.fetch(id)?.toBookInformation()
                ?.takeUnless(BookInformation::isEmpty)
                ?: fallbackBookInformation(id)
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            diagnostics.error("BOOK_ERROR", error, mapOf("bookId" to id))
            fallbackBookInformation(id)
        }
    }

    override suspend fun getBookVolumes(id: String): BookVolumes = withContext(Dispatchers.IO) {
        runCatching {
            parser.parseCatalog(id, httpClient.loadHtml(LinovelibUrls.catalog(LinovelibUrls.HOST, id))).toBookVolumes()
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            diagnostics.error("CATALOG_ERROR", error, mapOf("bookId" to id))
            BookVolumes.empty(id)
        }
    }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ): ChapterContent = withContext(Dispatchers.IO) {
        val currentChapterId = LinovelibChapterIds.forApp(chapterId)
        runCatching {
            getChapterContentPages(
                chapterId = currentChapterId,
                bookId = bookId,
                parser = parser,
                diagnostics = diagnostics,
                loadPage = httpClient::loadChapterPage
            ).toChapterContent()
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            diagnostics.error(
                "CHAPTER_ERROR",
                error,
                mapOf("bookId" to bookId, "chapterId" to chapterId)
            )
            ChapterContent.empty(currentChapterId)
        }
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

    private fun fallbackBookInformation(id: String): BookInformation =
        metadata.fallbackBookInformation(id)?.toBookInformation() ?: BookInformation.empty(id)

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

        return MutableChapterContent(
            id = id,
            title = title,
            content = content,
            lastChapter = previousChapterId,
            nextChapter = nextChapterId
        )
    }
}

private const val MAX_CHAPTER_PAGES = 100

internal suspend fun getChapterContentPages(
    chapterId: String,
    bookId: String,
    parser: LinovelibHtmlParser,
    diagnostics: LinovelibDiagnostics,
    loadPage: suspend (String) -> LinovelibHtmlResponse,
    host: String = LinovelibUrls.HOST
): ParsedChapterContent {
    val websiteChapterId = LinovelibChapterIds.forWebsite(chapterId)
    val pages = mutableListOf<ParsedChapterContent>()
    val visitedUrls = mutableSetOf<String>()
    var nextUrl = LinovelibUrls.fullChapter(host, bookId, websiteChapterId)
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
        val response = loadPage(nextUrl)
        val finalUrl = response.finalUrl
        val page = parser.parseChapterContent(
            chapterId = websiteChapterId,
            html = response.html,
            baseUrl = finalUrl,
            restoreParagraphOrder = true
        )
        pages.add(page)
        val candidate = page.nextPageUrl.takeIf(String::isNotBlank)
            ?.let { URI(finalUrl).resolve(it).toString() }.orEmpty()
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
            .takeIf { it["id"]?.jsonPrimitive?.content == ImageComponentData.ID }
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
