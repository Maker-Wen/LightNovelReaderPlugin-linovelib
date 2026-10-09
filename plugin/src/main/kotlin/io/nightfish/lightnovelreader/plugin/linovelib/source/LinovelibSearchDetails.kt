package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LinovelibSearchDetails(
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val host: String = LinovelibUrls.HOST
) {
    fun load(books: List<ParsedExploreBook>): Flow<ParsedBookInformation> = flow {
        books.forEach { book ->
            emit(parser.cachedBookInformation(book.id)?.takeIf { it.title.isNotBlank() } ?: book.summary())
        }
        books.forEach { book ->
            val information = runCatching {
                parser.parseBookInformation(book.id, htmlLoader(LinovelibUrls.book(host, book.id)))
                    .takeIf { it.title.isNotBlank() }
            }.onFailure {
                it.rethrowIfCancellation()
                diagnostics.error("SEARCH_DETAIL_ERROR", it, mapOf("bookId" to book.id))
            }.getOrNull()
            if (information != null) emit(information)
        }
    }

    private fun ParsedExploreBook.summary() = ParsedBookInformation(
        id = id,
        title = title,
        subtitle = "",
        coverUrl = coverUrl,
        author = author,
        description = "",
        tags = emptyList(),
        publishingHouse = "",
        wordCount = 0,
        lastUpdated = LinovelibDates.unknownLocalDate,
        isComplete = false
    )
}
