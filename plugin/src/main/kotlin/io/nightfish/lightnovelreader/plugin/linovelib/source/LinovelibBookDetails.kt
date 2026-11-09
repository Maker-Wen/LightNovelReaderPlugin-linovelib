package io.nightfish.lightnovelreader.plugin.linovelib.source

internal class LinovelibBookDetails(
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
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
}
