package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LinovelibSearchProvider(
    private val htmlLoader: suspend (String) -> String,
    private val searchHtmlLoader: suspend (String) -> LinovelibSearchResponse,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val host: String = LinovelibUrls.HOST
) : SearchProvider {
    private val searchDetails = LinovelibSearchDetails(htmlLoader, parser, diagnostics, host)

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
        val bookId = parser.bookIdFromKeyword(keyword)
        diagnostics.info(
            "SEARCH_START",
            linkedMapOf("type" to searchType.type, "keyword" to query, "directBookId" to bookId)
        )
        if (bookId != null) {
            if (loadDetails) {
                runCatching {
                    parser.parseBookInformation(bookId, htmlLoader(LinovelibUrls.book(host, bookId))).toMutableBookInformation()
                }.onFailure {
                    it.rethrowIfCancellation()
                    diagnostics.error("SEARCH_DIRECT_ERROR", it, mapOf("bookId" to bookId))
                }.getOrNull()
                    ?.takeUnless { it.isEmpty() }
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

        val searchResponse = runCatching {
            searchHtmlLoader(query)
        }.onFailure {
            it.rethrowIfCancellation()
            diagnostics.error("SEARCH_REQUEST_ERROR", it, mapOf("keyword" to query))
        }.getOrNull()

        if (searchResponse == null) {
            emit(SearchResult.Error("Linovelib 搜索请求失败，请稍后重试"))
            emit(SearchResult.End())
            return@flow
        }

        val redirectedBookId = searchResponse.directBookId(parser)
        if (redirectedBookId != null) {
            if (loadDetails) {
                val book = runCatching {
                    parser.parseBookInformation(redirectedBookId, searchResponse.html).toMutableBookInformation()
                }.onFailure {
                    it.rethrowIfCancellation()
                    diagnostics.error("SEARCH_REDIRECT_ERROR", it, mapOf("bookId" to redirectedBookId))
                }.getOrNull()
                if (book != null && !book.isEmpty()) {
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

        val searchRow = parser.parseListRow("Search", searchResponse.html)

        val books = searchRow.books.distinctBy { it.id }
        diagnostics.info(
            "SEARCH_SOURCE",
            linkedMapOf(
                "source" to "guarded-search",
                "keyword" to query,
                "books" to books.size
            )
        )
        if (books.isEmpty()) {
            emit(
                if (searchResponse.hasEmptyResultList()) SearchResult.Empty()
                else SearchResult.Error("无法识别 Linovelib 搜索结果，请稍后重试")
            )
        } else if (!loadDetails) {
            books.forEach { emit(SearchResult.SingleBook(it.id)) }
        } else {
            val displayedBooks = mutableMapOf<String, MutableBookInformation>()
            searchDetails.load(books).collect { parsedBook ->
                val information = parsedBook.toMutableBookInformation()
                val displayed = displayedBooks[parsedBook.id]
                if (displayed == null) {
                    displayedBooks[parsedBook.id] = information
                    emit(SearchResult.MultipleBook(information))
                } else {
                    displayed.update(information)
                }
            }
        }
        diagnostics.info(
            "SEARCH_DONE",
            linkedMapOf("mode" to "text", "keyword" to query, "results" to books.size)
        )
        emit(SearchResult.End())
    }
}

internal fun ParsedBookInformation.toMutableBookInformation(): MutableBookInformation =
    MutableBookInformation(
        id = id,
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl.takeIf(String::isNotEmpty)?.let(Uri::parse) ?: Uri.EMPTY,
        author = author,
        description = description,
        tags = LinovelibRelatedSearch.displayTags(author, tags),
        publishingHouse = publishingHouse,
        wordCount = WordCount(wordCount),
        lastUpdated = lastUpdated.atStartOfDay(),
        isComplete = isComplete
    )
