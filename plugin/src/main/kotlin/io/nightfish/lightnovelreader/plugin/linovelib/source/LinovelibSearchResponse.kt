package io.nightfish.lightnovelreader.plugin.linovelib.source

internal data class LinovelibSearchResponse(
    val finalUrl: String,
    val html: String
) {
    fun directBookId(parser: LinovelibHtmlParser): String? = parser.bookIdFromKeyword(finalUrl)
}
