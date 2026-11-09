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
    { searchHtmlLoader },
    parser,
    diagnostics,
    metadata,
    LinovelibBookDetails(htmlLoader, parser, host, metadata),
    host
)

internal fun searchPage(ids: List<String>, next: String? = null): String = """
    <ol class="book-ol">
      ${ids.joinToString("") { "<li class=\"book-li\"><a class=\"book-layout\" href=\"/novel/$it.html\"><h4 class=\"book-title\">Book $it</h4></a></li>" }}
    </ol>
    ${next?.let { "<div id=\"pagelink\"><a class=\"next\" href=\"$it\">下一页</a></div>" }.orEmpty()}
""".trimIndent()
