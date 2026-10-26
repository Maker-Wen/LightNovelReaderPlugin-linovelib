package io.nightfish.lightnovelreader.plugin.linovelib.source

/** Assemble the same shared detail/metadata dependencies as the production source. */
internal fun testSearchProvider(
    htmlLoader: suspend (String) -> String,
    searchHtmlLoader: suspend (String) -> LinovelibHtmlResponse,
    parser: LinovelibHtmlParser = LinovelibHtmlParser(),
    diagnostics: LinovelibDiagnostics = LinovelibDiagnostics { _, _, _, _ -> },
    host: String = LinovelibUrls.HOST,
    metadata: LinovelibMetadataStore = LinovelibMetadataStore()
): LinovelibSearchProvider = LinovelibSearchProvider(
    searchHtmlLoader,
    parser,
    diagnostics,
    metadata,
    LinovelibBookDetails(htmlLoader, parser, diagnostics, host, metadata)
)
