package io.nightfish.lightnovelreader.plugin.linovelib.source

object LinovelibUrls {
    const val TRADITIONAL_HOST = "https://tw.linovelib.com"
    const val SIMPLIFIED_HOST = "https://www.bilinovel.com"
    const val HOST = TRADITIONAL_HOST
    const val CONTENT_HOST = SIMPLIFIED_HOST
    val SEARCH_HOSTS = listOf(HOST, SIMPLIFIED_HOST)
    const val TOP = "$HOST/top.html"
    const val COMPLETE = "$HOST/topfull/postdate/1.html"
    const val TOP_POSTDATE = "$HOST/top/postdate/1.html"

    fun top(host: String): String = "$host/top.html"

    fun complete(host: String): String = "$host/topfull/postdate/1.html"

    fun topPostdate(host: String): String = "$host/top/postdate/1.html"

    fun wenku(order: String, page: Int): String =
        wenku(HOST, order, page)

    fun wenku(host: String, order: String, page: Int): String =
        "$host/wenku/${order}_0_0_0_0_0_0_0_${page}_0.html"

    fun book(bookId: String): String = book(HOST, bookId)

    fun book(host: String, bookId: String): String = "$host/novel/$bookId.html"

    fun catalog(bookId: String): String = catalog(HOST, bookId)

    fun catalog(host: String, bookId: String): String = "$host/novel/$bookId/catalog"

    fun chapter(bookId: String, chapterId: String): String = chapter(HOST, bookId, chapterId)

    fun chapter(host: String, bookId: String, chapterId: String): String =
        "$host/novel/$bookId/$chapterId.html"

    fun fullChapter(bookId: String, chapterId: String): String =
        chapter(CONTENT_HOST, bookId, chapterId)

    fun fullChapter(host: String, bookId: String, chapterId: String): String =
        chapter(host, bookId, chapterId)

    fun cover(bookId: String): String = cover(HOST, bookId)

    fun cover(host: String, bookId: String): String {
        val directory = bookId.toIntOrNull()?.div(1_000) ?: 0
        return "$host/files/article/image/$directory/$bookId/${bookId}s.jpg"
    }
}

internal enum class LinovelibSite(val host: String) {
    SIMPLIFIED(LinovelibUrls.SIMPLIFIED_HOST),
    TRADITIONAL(LinovelibUrls.TRADITIONAL_HOST);

    val acceptLanguage: String
        get() = if (this == TRADITIONAL) "zh-TW,zh;q=0.9,en;q=0.7" else "zh-CN,zh;q=0.9,en;q=0.7"

    fun searchKeyword(keyword: String): String = when (this) {
        SIMPLIFIED -> LinovelibChineseConverter.toSimplified(keyword)
        TRADITIONAL -> LinovelibChineseConverter.toTraditional(keyword)
    }

    companion object {
        fun fromStoredValue(value: String?): LinovelibSite =
            entries.firstOrNull { it.name == value } ?: SIMPLIFIED
    }
}
