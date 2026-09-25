package io.nightfish.lightnovelreader.plugin.linovelib.source

object LinovelibUrls {
    const val HOST = "https://www.bilinovel.net"

    fun top(host: String): String = "$host/top.html"

    fun complete(host: String): String = "$host/topfull/postdate/1.html"

    fun wenku(order: String, page: Int): String =
        wenku(HOST, order, page)

    fun wenku(host: String, order: String, page: Int): String =
        "$host/wenku/${order}_0_0_0_0_0_0_0_${page}_0.html"

    fun wenku(
        host: String,
        order: String,
        page: Int,
        theme: String,
        status: String,
        animation: String,
        region: String,
        words: String
    ): String = "$host/wenku/${order}_${theme}_${status}_${animation}_${region}_0_0_${words}_${page}_0.html"

    internal fun listPage(targetUrl: String, page: Int): String? {
        require(page > 0)
        if (page == 1) return targetUrl
        if ("/wenku/" in targetUrl) {
            if (targetUrl.endsWith("/wenku/")) {
                return wenku(targetUrl.removeSuffix("/wenku/"), "lastupdate", page)
            }
            val segment = Regex("_\\d+_0(\\.html(?:\\?.*)?)$")
            if (segment.containsMatchIn(targetUrl)) {
                return segment.replace(targetUrl) { "_${page}_0${it.groupValues[1]}" }
            }
        }
        if (listOf("/wenku/", "/top/", "/topfull/").none { it in targetUrl }) return null
        val path = Regex("/\\d+(\\.html(?:\\?.*)?)$")
        return if (path.containsMatchIn(targetUrl)) {
            path.replace(targetUrl) { "/$page${it.groupValues[1]}" }
        } else null
    }

    fun book(bookId: String): String = book(HOST, bookId)

    fun book(host: String, bookId: String): String = "$host/novel/$bookId.html"

    fun catalog(bookId: String): String = catalog(HOST, bookId)

    fun catalog(host: String, bookId: String): String = "$host/novel/$bookId/catalog"

    fun chapter(bookId: String, chapterId: String): String = chapter(HOST, bookId, chapterId)

    fun chapter(host: String, bookId: String, chapterId: String): String =
        "$host/novel/$bookId/$chapterId.html"

    fun fullChapter(bookId: String, chapterId: String): String =
        chapter(HOST, bookId, chapterId)

    fun fullChapter(host: String, bookId: String, chapterId: String): String =
        chapter(host, bookId, chapterId)

    // Cached covers may still use the former simplified or traditional domain.
    internal fun currentCoverUrl(url: String): String = url.replace(
        Regex("^https?://(?:www\\.bilinovel\\.com|tw\\.linovelib\\.com)(?=/files/article/image/)"), HOST
    )

    fun cover(bookId: String): String = cover(HOST, bookId)

    fun cover(host: String, bookId: String): String {
        val directory = bookId.toIntOrNull()?.div(1_000) ?: 0
        return "$host/files/article/image/$directory/$bookId/${bookId}s.jpg"
    }
}
