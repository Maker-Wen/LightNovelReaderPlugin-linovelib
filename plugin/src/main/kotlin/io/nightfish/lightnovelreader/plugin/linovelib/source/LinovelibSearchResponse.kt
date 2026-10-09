package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.jsoup.Jsoup

internal data class LinovelibSearchResponse(
    val finalUrl: String,
    val html: String
) {
    fun hasEmptyResultList(): Boolean {
        val list = Jsoup.parse(html).selectFirst("ol.book-ol") ?: return false
        return list.select(".book-li, a[href*=/novel/]").isEmpty()
    }

    fun directBookId(parser: LinovelibHtmlParser): String? {
        parser.bookIdFromKeyword(finalUrl)?.let { return it }
        val canonicalUrl = Jsoup.parse(html)
            .selectFirst("meta[property=og:url]")
            ?.attr("content")
            .orEmpty()
        return parser.bookIdFromKeyword(canonicalUrl)
    }
}
