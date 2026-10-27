package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LinovelibSearchProvider(
    private val searchHtmlLoader: suspend (String) -> LinovelibHtmlResponse,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val metadata: LinovelibMetadataStore,
    private val details: LinovelibBookDetails,
    private val host: String = LinovelibUrls.HOST
) : SearchProvider {

    override val searchTypes: List<SearchType> = listOf(
        SearchType(
            type = "book",
            name = "综合".local(),
            tip = "搜索书名、作者、标签".local()
        )
    )

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> =
        search(searchType, keyword, loadDetails = true)

    fun searchBookIds(keyword: String): Flow<SearchResult> =
        search(searchTypes.first(), keyword, loadDetails = false)

    private fun search(searchType: SearchType, keyword: String, loadDetails: Boolean): Flow<SearchResult> = flow {
        val query = keyword.trim()
        val bookId = parser.bookIdFromKeyword(query)
        diagnostics.info(
            "SEARCH_START",
            linkedMapOf("type" to searchType.type, "keyword" to query, "directBookId" to bookId)
        )
        if (bookId != null) {
            if (loadDetails) {
                runCatching {
                    details.fetch(bookId)?.toMutableBookInformation()
                }.onFailure {
                    it.rethrowIfCancellation()
                    diagnostics.error("SEARCH_DIRECT_ERROR", it, mapOf("bookId" to bookId))
                }.getOrNull()
                    ?.let { emit(SearchResult.MultipleBook(it)) }
                    ?: emit(SearchResult.SingleBook(bookId))
            } else {
                emit(SearchResult.SingleBook(bookId))
            }
            diagnostics.info("SEARCH_DONE", mapOf("mode" to "direct", "results" to 1, "bookId" to bookId))
            emit(SearchResult.End())
            return@flow
        }

        if (query.isEmpty()) {
            emit(SearchResult.Empty())
            emit(SearchResult.End())
            return@flow
        }

        val books = mutableListOf<ParsedExploreBook>()
        val displayedBooks = mutableMapOf<String, MutableList<MutableBookInformation>>()
        var pageUrl: String? = LinovelibUrls.search(host, query)
        var pageError: String? = null
        while (pageUrl != null) {
            val url = pageUrl
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

            val redirectedBookId = parser.bookIdFromKeyword(searchResponse.finalUrl)
            if (redirectedBookId != null) {
                if (loadDetails) {
                    val book = runCatching {
                        details.acceptHtml(redirectedBookId, searchResponse.html)?.toMutableBookInformation()
                    }.onFailure {
                        it.rethrowIfCancellation()
                        diagnostics.error("SEARCH_REDIRECT_ERROR", it, mapOf("bookId" to redirectedBookId))
                    }.getOrNull()
                    if (book != null) {
                        emit(SearchResult.MultipleBook(book))
                    } else {
                        emit(SearchResult.SingleBook(redirectedBookId))
                    }
                } else {
                    emit(SearchResult.SingleBook(redirectedBookId))
                }
                diagnostics.info(
                    "SEARCH_DONE",
                    mapOf("mode" to "redirect", "results" to 1, "bookId" to redirectedBookId)
                )
                emit(SearchResult.End())
                return@flow
            }

            val page = parser.parseListPage(searchResponse.html)
            if (page == null) {
                pageError = "无法识别 Linovelib 搜索结果，请稍后重试"
                break
            }
            metadata.rememberBooks(page.books)
            diagnostics.info(
                "SEARCH_SOURCE",
                mapOf("source" to "guarded-search", "keyword" to query, "url" to url, "books" to page.books.size)
            )
            for (book in page.books) {
                books += book
                if (loadDetails) {
                    val information = (metadata.bookInformation(book.id) ?: book.toParsedBookInformation())
                        .toMutableBookInformation()
                    displayedBooks.getOrPut(book.id) { mutableListOf() }.add(information)
                    emit(SearchResult.MultipleBook(information))
                } else {
                    emit(SearchResult.SingleBook(book.id))
                }
            }
            pageUrl = page.nextPageUrl
        }
        if (pageError != null) {
            emit(SearchResult.Error(pageError))
        } else if (books.isEmpty()) {
            emit(SearchResult.Empty())
        } else if (loadDetails) {
            // Finish the result pages before detail requests can delay the next page.
            details.enrich(books).collect { parsedBook ->
                val information = parsedBook.toMutableBookInformation()
                displayedBooks.getValue(parsedBook.id).forEach { it.update(information) }
            }
        }
        diagnostics.info(
            "SEARCH_DONE",
            linkedMapOf("mode" to "text", "keyword" to query, "results" to books.size)
        )
        emit(SearchResult.End())
    }
}
