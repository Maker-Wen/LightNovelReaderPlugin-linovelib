package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LinovelibSearchDetails(
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val host: String = LinovelibUrls.HOST,
    private val metadata: LinovelibMetadataStore = LinovelibMetadataStore()
) {
    fun load(books: List<ParsedExploreBook>): Flow<ParsedBookInformation> = flow {
        books.forEach { book ->
            emit(metadata.bookInformation(book.id) ?: book.toParsedBookInformation())
        }
        books.forEach { book ->
            val information = runCatching {
                parser.parseBookInformation(book.id, htmlLoader(LinovelibUrls.book(host, book.id)))
                    .also(metadata::rememberBook)
                    .takeIf { it.title.isNotBlank() }
            }.onFailure {
                it.rethrowIfCancellation()
                diagnostics.error("SEARCH_DETAIL_ERROR", it, mapOf("bookId" to book.id))
            }.getOrNull()
            if (information != null) emit(information)
        }
    }

}
