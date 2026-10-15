package io.nightfish.lightnovelreader.plugin.linovelib.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LinovelibBookDetails(
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    private val diagnostics: LinovelibDiagnostics,
    private val host: String = LinovelibUrls.HOST,
    private val metadata: LinovelibMetadataStore
) {
    /** Always fetch fresh HTML; fallback and host mapping belong to the caller. */
    suspend fun fetch(id: String): ParsedBookInformation? =
        acceptHtml(id, htmlLoader(LinovelibUrls.book(host, id)))

    /** Accept an existing response without issuing another request. Exceptions propagate. */
    fun acceptHtml(id: String, html: String): ParsedBookInformation? =
        parser.parseBookInformation(id, html)
            .takeIf { it.title.isNotBlank() }
            ?.also(metadata::rememberBook)

    // Emit every initial book before fetching details. A stored summary must not replace
    // the current list's summary, and cached details must not suppress a fresh request.
    fun enrich(books: List<ParsedExploreBook>): Flow<ParsedBookInformation> = flow {
        books.forEach { book ->
            emit(metadata.bookInformation(book.id) ?: book.toParsedBookInformation())
        }
        books.forEach { book ->
            val information = runCatching {
                fetch(book.id)
            }.onFailure {
                it.rethrowIfCancellation()
                diagnostics.error("SEARCH_DETAIL_ERROR", it, mapOf("bookId" to book.id))
            }.getOrNull()
            if (information != null) emit(information)
        }
    }
}
