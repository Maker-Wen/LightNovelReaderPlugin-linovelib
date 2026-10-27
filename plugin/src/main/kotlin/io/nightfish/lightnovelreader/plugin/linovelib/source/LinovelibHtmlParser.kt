package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI
import java.time.LocalDate

internal data class ParsedBookInformation(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val publishingHouse: String,
    val wordCount: Int,
    val lastUpdated: LocalDate,
    val isComplete: Boolean,
    val authorUrl: String = "",
    val relatedLinks: Map<String, String> = emptyMap()
)

internal data class ParsedCatalog(
    val bookId: String,
    val volumes: List<ParsedVolume>
)

internal data class ParsedVolume(
    val title: String,
    val chapters: List<ParsedChapter>
)

internal data class ParsedChapter(
    val id: String,
    val title: String
)

internal data class ParsedChapterContent(
    val id: String,
    val title: String,
    val previousChapterId: String,
    val nextChapterId: String,
    val nextPageUrl: String,
    val blocks: List<ParsedContentBlock>
)

internal data class ParsedExploreRow(
    val title: String,
    val books: List<ParsedExploreBook>,
    val expandedUrl: String? = null
)

internal data class ParsedExploreBook(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String
)

internal data class ParsedListPage(
    val books: List<ParsedExploreBook>,
    val lastPage: Int,
    val nextPageUrl: String?
)

internal sealed class ParsedContentBlock {
    data class Text(val text: String) : ParsedContentBlock()
    data class Image(val url: String) : ParsedContentBlock()
}

internal class LinovelibHtmlParser(
    private val host: String = LinovelibUrls.HOST
) {
    fun parseBookInformation(id: String, html: String): ParsedBookInformation {
        val document = Jsoup.parse(html, host)
        val metaText = document.select(".book-detail-info .book-meta.book-layout-inline")
            .joinToString(" ") { it.text() }
        val tagElements = document.select(".tag-small-group .tag-small")
        val relatedTagElements = tagElements.filterNot { it.hasClass("gray") }
        val tags = mutableListOf<String>()
        val publishingHouse = tagElements
            .firstOrNull { it.hasClass("orange") }
            ?.text()
            .orEmpty()

        val authorElement = document.selectFirst(".authorname a")
        val author = authorElement?.text().orEmpty()
        val authorUrl = authorElement?.absUrl("href").orEmpty()
            .let(::normalizeUrl)
        val relatedLinks = buildMap {
            relatedTagElements.forEach { element ->
                val tag = element.text().trim()
                if (tag.isEmpty()) return@forEach
                if (!element.hasClass("orange")) tags += tag
                val link = element.selectFirst("a[href]") ?: return@forEach
                val target = link.absUrl("href").let(::normalizeUrl)
                if (target.isNotEmpty()) put(tag, target)
            }
        }

        return ParsedBookInformation(
            id = id,
            title = document.selectFirst(".book-title")?.text().orEmpty(),
            subtitle = document.selectFirst(".backupname span")?.text().orEmpty(),
            coverUrl = normalizeUrl(document.selectFirst("meta[property=\"og:image\"]")?.attr("content").orEmpty()),
            author = author,
            description = document.selectFirst("#bookSummary content")
                ?.let(::htmlWithBreaksToText)
                .orEmpty(),
            tags = tags,
            publishingHouse = publishingHouse,
            wordCount = parseWordCount(metaText),
            lastUpdated = parseLastUpdated(
                document.selectFirst(".book-status")?.text().orEmpty()
            ),
            isComplete = STATUS_COMPLETE.containsMatchIn(metaText),
            authorUrl = authorUrl,
            relatedLinks = relatedLinks
        )
    }

    fun parseCatalog(bookId: String, html: String): ParsedCatalog {
        val document = Jsoup.parse(html, host)
        val volumes = document.select("#volumes .catalog-volume")
            .mapNotNull { volume ->
                val title = volume.selectFirst(".chapter-bar h3")?.text().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val chapters = volume.select(".jsChapter a")
                    .mapNotNull { chapter ->
                        val href = chapter.attr("href")
                        val chapterId = chapterIdFromHref(href) ?: return@mapNotNull null
                        val chapterTitle = chapter.selectFirst(".chapter-index")?.text().orEmpty()
                        ParsedChapter(chapterId, chapterTitle)
                    }
                ParsedVolume(title, chapters)
            }
        return ParsedCatalog(bookId, volumes)
    }

    fun parseChapterContent(
        chapterId: String,
        html: String,
        baseUrl: String
    ): ParsedChapterContent {
        val document = Jsoup.parse(html, baseUrl)
        val content = requireNotNull(document.selectFirst("#acontent")) {
            "Unrecognized Linovelib chapter page: $baseUrl"
        }
        content.select(".cgo, center, script, style, ins").remove()
        val orderedElements = restoreParagraphs(chapterId, content.children())
        val blocks = orderedElements.flatMap(::parseContentBlocks)
        val readParams = document.select("script")
            .joinToString("\n") { it.data() }
        val previousUrl = readParamUrl(readParams, PREVIOUS_URL)
        val nextUrl = readParamUrl(readParams, NEXT_URL)

        return ParsedChapterContent(
            id = chapterId,
            title = document.selectFirst("#atitle")?.text().orEmpty(),
            previousChapterId = chapterIdFromHref(previousUrl).orEmpty(),
            nextChapterId = chapterIdFromHref(nextUrl).orEmpty(),
            nextPageUrl = nextUrl,
            blocks = blocks
        )
    }

    private fun restoreParagraphs(chapterId: String, elements: List<Element>): List<Element> {
        val positions = elements.indices.filter { index ->
            val element = elements[index]
            element.normalName() == "p" && NON_WHITESPACE.containsMatchIn(element.html())
        }
        val restoredParagraphs = LinovelibParagraphOrder.restore(
            chapterId,
            positions.map(elements::get)
        )
        return elements.toMutableList().apply {
            positions.forEachIndexed { paragraphIndex, elementIndex ->
                this[elementIndex] = restoredParagraphs[paragraphIndex]
            }
        }
    }

    fun parseExploreRows(html: String): List<ParsedExploreRow> {
        val document = Jsoup.parse(html, host)
        return document.select(".module")
            .mapNotNull { module ->
                val title = module.selectFirst(".module-title")?.text()?.trim().orEmpty()
                val books = module
                    .select("a.module-slide-a[href], a.book-layout[href]")
                    .mapNotNull(::parseExploreBook)
                    .distinctBy { it.id }
                if (title.isEmpty() || books.isEmpty()) null else ParsedExploreRow(
                    title,
                    books,
                    expandedListUrl(module, ".module-header a.module-header-btn[href]")
                )
            }
    }

    fun parseRankingRows(html: String): List<ParsedExploreRow> {
        val document = Jsoup.parse(html, host)
        return document.select(".category-list")
            .mapNotNull { category ->
                val title = category.selectFirst(".fl-header-l")?.text()?.trim().orEmpty()
                val books = category.select(".fl-content a[href*=/novel/]")
                    .mapNotNull(::parseExploreBook)
                    .distinctBy { it.id }
                if (title.isEmpty() || books.isEmpty()) null else ParsedExploreRow(
                    title,
                    books,
                    expandedListUrl(category, "a.fl-header[href], a.fl-content-li-more[href]")
                )
            }
    }

    /** Null means unrecognized; an empty book list means a recognized empty page. */
    fun parseListPage(html: String): ParsedListPage? {
        val document = Jsoup.parse(html, host)
        val books = document.select("a.book-layout[href]")
            .mapNotNull(::parseExploreBook)
            .distinctBy { it.id }
        if (books.isEmpty()) {
            val list = document.selectFirst("ol.book-ol") ?: return null
            // Candidate rows without usable book information are not an empty result.
            if (list.select(".book-li, a[href*=/novel/]").isNotEmpty()) return null
        }
        val nextPageUrl = document.selectFirst("#pagelink a.next[href]")
            ?.takeIf { it.attr("href") != "#" }
            ?.absUrl("href")
        return ParsedListPage(books, parseLastPage(document), nextPageUrl)
    }

    private fun expandedListUrl(element: Element, selector: String): String? {
        val candidates = element.select(selector).asSequence()
            .mapNotNull { link -> runCatching { URI(link.absUrl("href")) }.getOrNull() }
            .iterator()
        if (!candidates.hasNext()) return null
        val expectedHost = URI(host).host
        return candidates.asSequence().firstOrNull { url ->
            url.host == expectedHost && url.scheme == "https" &&
                (url.path.startsWith("/wenku/") || TOP_PAGE.matches(url.path))
        }?.toString()
    }

    private fun parseLastPage(document: Element): Int {
        document.selectFirst("#pagelink a.last")
            ?.text()
            ?.toIntOrNull()
            ?.let { return it }
        return PAGE_COUNT
            .find(document.selectFirst("#pagelink")?.text().orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 1
    }

    /** [keyword] is a trimmed user query or an HTTP response URL. */
    fun bookIdFromKeyword(keyword: String): String? {
        if (keyword.all(Char::isDigit) && keyword.isNotEmpty()) return keyword
        return bookIdFromHref(keyword)
    }

    private fun bookIdFromHref(href: String): String? {
        return BOOK_ID
            .find(href)
            ?.groupValues
            ?.get(1)
    }

    private fun parseExploreBook(element: Element): ParsedExploreBook? {
        val id = bookIdFromHref(element.attr("href")) ?: return null
        val image = element.selectFirst("img")
        val visibleTitle = element.selectFirst(".module-slide-caption, .book-title")
            ?.text()
            ?.trim()
            ?.ifEmpty { null }
        val imageTitle = image?.attr("alt")?.trim()?.ifEmpty { null }
        val title = visibleTitle ?: imageTitle ?: element.text()
        if (title.isBlank()) return null

        val author = element.selectFirst(".module-slide-author .gray")?.text()
            ?: element.selectFirst(".book-author")?.ownText().orEmpty()
        val coverUrl = image
            ?.attr("data-src")
            ?.ifBlank { image.attr("src") }
            ?.let(::normalizeUrl)
            .orEmpty()
            .ifEmpty { LinovelibUrls.cover(host, id) }

        return ParsedExploreBook(id, title, author, coverUrl)
    }

    private fun parseContentBlocks(element: Element): List<ParsedContentBlock> {
        if (element.normalName() == "img") {
            val sourceAttribute = if (element.attr("data-src").isNotBlank()) "data-src" else "src"
            val src = element.absUrl(sourceAttribute)
            return listOf(ParsedContentBlock.Image(normalizeUrl(src)))
        }
        if (element.selectFirst("img") == null) {
            val text = cleanContentText(element.textWithNewLines())
            if (text.isEmpty()) return emptyList()
            return listOf(ParsedContentBlock.Text(text))
        }
        val blocks = mutableListOf<ParsedContentBlock>()
        val text = StringBuilder()

        fun flushText() {
            val cleaned = cleanContentText(text.toString())
            if (cleaned.isNotEmpty()) blocks += ParsedContentBlock.Text(cleaned)
            text.clear()
        }

        element.childNodes().forEach { node ->
            when (node) {
                is TextNode -> text.append(node.text())
                is Element -> when {
                    node.normalName() == "br" -> text.append('\n')
                    node.normalName() == "img" || node.selectFirst("img") != null -> {
                        flushText()
                        blocks += parseContentBlocks(node)
                    }
                    else -> text.append(node.textWithNewLines())
                }
            }
        }
        flushText()
        return blocks
    }

    private fun parseWordCount(text: String): Int {
        val match = WORD_COUNT.find(text) ?: return 0
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0
        val unit = match.groupValues[2]
        return if (unit.isNotEmpty()) (number * 10_000).toInt() else number.toInt()
    }

    private fun parseLastUpdated(text: String): LocalDate {
        val match = LAST_UPDATED.find(text)
        return match?.value?.let(LocalDate::parse) ?: LinovelibDates.unknownLocalDate
    }

    private fun readParamUrl(script: String, pattern: Regex): String =
        pattern.find(script)?.groupValues?.get(2).orEmpty()

    fun chapterIdFromHref(href: String): String? =
        CHAPTER_ID.find(href)?.groupValues?.get(1)

    private fun normalizeUrl(url: String): String {
        if (url.isBlank()) return ""
        val absolute = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> host + url
            else -> "$host/$url"
        }
        return absolute.substringBefore("?")
    }

    private fun Element.textWithNewLines(): String {
        val parts = mutableListOf<String>()
        childNodes().forEach { node ->
            when (node) {
                is TextNode -> node.text().trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                is Element -> when (node.normalName()) {
                    "br" -> parts.add("\n")
                    else -> node.textWithNewLines().takeIf { it.isNotEmpty() }?.let(parts::add)
                }
            }
        }
        return parts.joinToString("\n")
            .replace(REPEATED_NEW_LINES, "\n")
            .trim()
    }

    private fun cleanContentText(text: String): String {
        val failureMarkerIndex = listOf(CONTENT_LOAD_FAILED, SIMPLIFIED_CONTENT_LOAD_FAILED)
            .map(text::indexOf)
            .filter { it >= 0 }
            .minOrNull()
        val mobileWarningIndex = text.indexOf(MOBILE_PAGE_WARNING).takeIf { it >= 0 }
        val cutIndex = listOfNotNull(failureMarkerIndex, mobileWarningIndex).minOrNull()
        val visibleText = cutIndex?.let { text.substring(0, it) } ?: text
        return if (failureMarkerIndex != null) {
            trimLoadFailureTail(visibleText)
        } else {
            visibleText.trim()
        }
    }

    private fun trimLoadFailureTail(text: String): String =
        text.trim()
            .replace(LOAD_FAILURE_TAIL, "")
            .trim()

    private fun htmlWithBreaksToText(element: Element): String =
        Jsoup.parseBodyFragment(
            element.html().replace(HTML_BREAK, "\n")
        ).body().wholeText()
            .replace(SPACES_AROUND_NEW_LINE, "\n")
            .replace(REPEATED_NEW_LINES, "\n")
            .trim()

    private companion object {
        val STATUS_COMPLETE = Regex("完[结結]")
        val NON_WHITESPACE = Regex("""\S""")
        val TOP_PAGE = Regex("""/top(?:full)?/[^/]+/\d+\.html""")
        val PAGE_COUNT = Regex("""\u7b2c\d+/(\d+)\u9875""")
        val BOOK_ID = Regex("""/novel/(\d+)""")
        val CHAPTER_ID = Regex("""/novel/\d+/(\d+)(?:_\d+)?\.html""")
        val WORD_COUNT = Regex("""([\d.]+)\s*([\u842c\u4e07]?)\u5b57""")
        val LAST_UPDATED = Regex("""\d{4}-\d{2}-\d{2}""")
        val PREVIOUS_URL = Regex("""url_previous\s*:\s*(['"])(.*?)\1""")
        val NEXT_URL = Regex("""url_next\s*:\s*(['"])(.*?)\1""")
        val REPEATED_NEW_LINES = Regex("\n{2,}")
        val LOAD_FAILURE_TAIL = Regex("""(?:…|．){1,}[（(]?$|\.{3,}[（(]?$""")
        val HTML_BREAK = Regex("""(?i)<br\s*/?>""")
        val SPACES_AROUND_NEW_LINE = Regex("[ \t]*\n[ \t]*")
        const val CONTENT_LOAD_FAILED = "\u5167\u5bb9\u52a0\u8f09\u5931\u6557"
        const val SIMPLIFIED_CONTENT_LOAD_FAILED = "\u5185\u5bb9\u52a0\u8f7d\u5931\u8d25"
        const val MOBILE_PAGE_WARNING = "\u624b\u6a5f\u7248\u9801\u9762"
    }
}
