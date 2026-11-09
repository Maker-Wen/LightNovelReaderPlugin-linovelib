package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.net.URI

internal class LinovelibSearchProvider(
    private val newSearchLoader: () -> suspend (String) -> LinovelibHtmlResponse,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val metadata: LinovelibMetadataStore,
    private val details: LinovelibBookDetails,
    private val host: String = LinovelibUrls.HOST
) : SearchProvider {
    private val cache = LinovelibSearchCache()

    override val searchTypes: List<SearchType> = listOf(
        SearchType(
            type = "book",
            name = "综合".local(),
            tip = "搜索书名、作者、标签".local()
        )
    )

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> =
        search(searchType, keyword, allowDirectNavigation = true)

    fun searchRelated(keyword: String): Flow<SearchResult> =
        search(searchTypes.first(), keyword, allowDirectNavigation = false)

    private fun search(searchType: SearchType, keyword: String, allowDirectNavigation: Boolean): Flow<SearchResult> = flow {
        val query = keyword.trim()
        val bookId = if (allowDirectNavigation) parser.bookIdFromKeyword(query) else null
        diagnostics.info(
            "SEARCH_START",
            linkedMapOf("type" to searchType.type, "keyword" to query, "directBookId" to bookId)
        )
        if (bookId != null) {
            emit(SearchResult.SingleBook(bookId))
            diagnostics.info("SEARCH_DONE", mapOf("mode" to "direct", "results" to 1, "bookId" to bookId))
            emit(SearchResult.End())
            return@flow
        }

        if (query.isEmpty()) {
            emit(SearchResult.Empty())
            emit(SearchResult.End())
            return@flow
        }

        val searchUrl = LinovelibUrls.search(host, query)
        val cachedBooks = cache.get(searchUrl)
        if (cachedBooks != null) {
            emitBooks(cachedBooks)
            if (cachedBooks.isEmpty()) emit(SearchResult.Empty())
            diagnostics.info("SEARCH_DONE", mapOf("mode" to "cache", "keyword" to query, "results" to cachedBooks.size))
            emit(SearchResult.End())
            return@flow
        }

        val searchHtmlLoader = newSearchLoader()
        val books = mutableListOf<ParsedExploreBook>()
        val visitedPageUrls = mutableSetOf<String>()
        var pagesLoaded = 0
        var declaredLastPage = 1
        var pageUrl: String? = searchUrl
        var pageError: String? = null
        var repeatedPageUrl: String? = null
        while (pageUrl != null) {
            val url = pageUrl
            val requestedPage = pageIdentity(url)
            if (!visitedPageUrls.add(requestedPage)) {
                repeatedPageUrl = url
                break
            }
            val searchResponse = runCatching {
                searchHtmlLoader(url)
            }.onFailure {
                it.rethrowIfCancellation()
                diagnostics.error("SEARCH_REQUEST_ERROR", it, mapOf("keyword" to query, "url" to url))
            }.getOrNull()
            if (searchResponse == null) {
                pageError = "Linovelib 搜索请求失败，请稍后重试"
                break
            }

            val finalPage = pageIdentity(searchResponse.finalUrl)
            if (finalPage != requestedPage && !visitedPageUrls.add(finalPage)) {
                repeatedPageUrl = searchResponse.finalUrl
                break
            }

            val redirectedBookId = parser.bookIdFromKeyword(searchResponse.finalUrl)
            if (redirectedBookId != null) {
                val book = runCatching {
                    details.acceptHtml(redirectedBookId, searchResponse.html)?.toMutableBookInformation()
                }.onFailure {
                    it.rethrowIfCancellation()
                    diagnostics.error("SEARCH_REDIRECT_ERROR", it, mapOf("bookId" to redirectedBookId))
                }.getOrNull()
                when {
                    book != null -> emit(SearchResult.MultipleBook(book))
                    allowDirectNavigation -> emit(SearchResult.SingleBook(redirectedBookId))
                    else -> emit(SearchResult.Error("无法识别 Linovelib 搜索结果，请稍后重试"))
                }
                diagnostics.info(
                    "SEARCH_DONE",
                    mapOf("mode" to "redirect", "results" to 1, "bookId" to redirectedBookId)
                )
                emit(SearchResult.End())
                return@flow
            }

            val page = parser.parseListPage(searchResponse.html, baseUrl = searchResponse.finalUrl)
            if (page == null) {
                pageError = "无法识别 Linovelib 搜索结果，请稍后重试"
                break
            }
            diagnostics.info(
                "SEARCH_SOURCE",
                mapOf("source" to "guarded-search", "keyword" to query, "url" to url, "books" to page.books.size)
            )
            emitBooks(page.books)
            books += page.books
            pagesLoaded++
            declaredLastPage = maxOf(declaredLastPage, page.lastPage)
            pageUrl = page.nextPageUrl
        }
        if (repeatedPageUrl != null) {
            diagnostics.info("SEARCH_PAGINATION_LOOP", mapOf("url" to repeatedPageUrl, "pages" to pagesLoaded))
            // The official API 3 host hides the entire list when it receives an Error.
            if (books.isEmpty()) emit(SearchResult.Error("Linovelib 搜索分页出现循环，请稍后重试"))
        } else if (pageError != null) {
            emit(SearchResult.Error(pageError))
        } else {
            currentCoroutineContext().ensureActive()
            // A missing next link on a page that advertises more results is not cacheable.
            if (pagesLoaded >= declaredLastPage) cache.remember(searchUrl, books)
            if (books.isEmpty()) emit(SearchResult.Empty())
        }
        diagnostics.info(
            "SEARCH_DONE",
            linkedMapOf("mode" to "text", "keyword" to query, "results" to books.size)
        )
        emit(SearchResult.End())
    }

    private fun pageIdentity(url: String): String {
        val address = url.substringBefore('#')
        return runCatching { URI(address).normalize().toASCIIString() }.getOrDefault(address)
    }

    private suspend fun FlowCollector<SearchResult>.emitBooks(books: List<ParsedExploreBook>) {
        metadata.rememberBooks(books)
        books.forEach { book ->
            emit(SearchResult.MultipleBook(metadata.createListCard(book)))
        }
    }
}
