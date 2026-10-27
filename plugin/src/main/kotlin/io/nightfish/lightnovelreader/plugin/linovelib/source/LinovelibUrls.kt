package io.nightfish.lightnovelreader.plugin.linovelib.source

internal object LinovelibUrls {
    const val HOST = "https://www.bilinovel.net"

    fun search(host: String, keyword: String): String =
        "$host/search.html?searchkey=${java.net.URLEncoder.encode(keyword, "UTF-8")}"

    fun top(host: String): String = "$host/top.html"

    fun complete(host: String): String = "$host/topfull/postdate/1.html"

    fun wenku(host: String, order: String, page: Int): String =
        wenku(host, order, page, "0", "0", "0", "0", "0")

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
        if (page == 1) return targetUrl
        if ("/wenku/" in targetUrl) {
            if (targetUrl.endsWith("/wenku/")) {
                return wenku(targetUrl.removeSuffix("/wenku/"), "lastupdate", page)
            }
            WENKU_PAGE.find(targetUrl)?.let { match ->
                return targetUrl.replaceRange(match.range, "_${page}_0${match.groupValues[1]}")
            }
        }
        if (listOf("/wenku/", "/top/", "/topfull/").none { it in targetUrl }) return null
        return PATH_PAGE.find(targetUrl)?.let { match ->
            targetUrl.replaceRange(match.range, "/$page${match.groupValues[1]}")
        }
    }

    fun book(host: String, bookId: String): String = "$host/novel/$bookId.html"

    fun catalog(host: String, bookId: String): String = "$host/novel/$bookId/catalog"

    fun chapter(host: String, bookId: String, chapterId: String): String =
        "$host/novel/$bookId/$chapterId.html"

    fun cover(host: String, bookId: String): String {
        val directory = bookId.toIntOrNull()?.div(1_000) ?: 0
        return "$host/files/article/image/$directory/$bookId/${bookId}s.jpg"
    }

    private val WENKU_PAGE = Regex("""_\d+_0(\.html(?:\?.*)?)$""")
    private val PATH_PAGE = Regex("""/\d+(\.html(?:\?.*)?)$""")
}
