package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.web.explore.AbstractDefaultExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LinovelibExplorePageProvider(
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    private val searchBookIds: (String) -> Flow<SearchResult>,
    private val host: String = LinovelibUrls.HOST
) : AbstractDefaultExplorePageProvider() {
    init {
        val wenkuFilters = LinovelibWenkuFilters(host)
        val wenkuUrl = LinovelibUrls.wenku(host, "lastupdate", 1)
        registerExpandedPageDataSource(
            WENKU_PAGE_ID,
            LinovelibLinkedExpandedPageDataSource(
                displayTag = "文库", targetUrl = wenkuUrl, htmlLoader = htmlLoader, parser = parser,
                filters = wenkuFilters.filters, filteredUrl = { wenkuFilters.url() }
            )
        )
        registerTapPage(LinovelibRowsExploreTapPage("推荐") {
            parser.parseExploreRows(htmlLoader(host)).map(::expandableRow)
        })
        registerTapPage(LinovelibRowsExploreTapPage("文库") {
            listOf(parser.parseListRow("全部轻小说", htmlLoader(wenkuUrl)).toExploreBooksRow(WENKU_PAGE_ID))
        })
        registerTapPage(LinovelibRowsExploreTapPage("排行") {
            parser.parseRankingRows(htmlLoader(LinovelibUrls.top(host))).map(::expandableRow)
        })
        registerTapPage(LinovelibRowsExploreTapPage("完结") {
            val url = LinovelibUrls.complete(host)
            listOf(expandableRow(parser.parseListRow("完结轻小说", htmlLoader(url)).copy(expandedUrl = url)))
        })
    }

    private fun expandableRow(row: ParsedExploreRow): ExploreBooksRow {
        val url = row.expandedUrl ?: return row.toExploreBooksRow()
        val pageId = "linovelib-list-${url.removePrefix(host)}"
        if (pageId !in exploreExpandedPageDataSourceMap) {
            registerExpandedPageDataSource(
                pageId, LinovelibLinkedExpandedPageDataSource(row.title, url, htmlLoader, parser)
            )
        }
        return row.toExploreBooksRow(pageId)
    }

    fun registerRelatedPage(tag: String): String {
        val displayTag = tag.trim().ifEmpty { "Related" }
        val baseId = "$RELATED_PAGE_ID-${Integer.toHexString(displayTag.hashCode())}"
        val targetUrl = parser.relatedTarget(displayTag)
        var pageId = baseId
        var suffix = 2
        while (true) {
            val existing = exploreExpandedPageDataSourceMap[pageId]
            if (existing == null) {
                registerExpandedPageDataSource(pageId, relatedPage(displayTag, targetUrl))
                return pageId
            }
            if (existing.title == displayTag) {
                if (targetUrl != null && existing !is LinovelibLinkedExpandedPageDataSource) {
                    registerExpandedPageDataSource(pageId, relatedPage(displayTag, targetUrl))
                }
                return pageId
            }
            pageId = "$baseId-${suffix++}"
        }
    }

    private fun relatedPage(displayTag: String, targetUrl: String?) =
        if (targetUrl == null) {
            LinovelibRelatedExpandedPageDataSource(searchBookIds, displayTag)
        } else {
            LinovelibLinkedExpandedPageDataSource(
                displayTag = displayTag,
                targetUrl = targetUrl,
                htmlLoader = htmlLoader,
                parser = parser
            )
        }

    companion object {
        const val RELATED_PAGE_ID = "linovelib-related"
        const val WENKU_PAGE_ID = "linovelib-wenku"
    }
}

private class LinovelibRowsExploreTapPage(
    override val title: String,
    private val rowsLoader: suspend () -> List<ExploreBooksRow>
) : ExploreTapPageDataSource {
    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        emit(rowsLoader())
    }
}

private fun ParsedExploreRow.toExploreBooksRow(expandedPageId: String? = null): ExploreBooksRow =
    ExploreBooksRow(
        title = title,
        expandable = expandedPageId != null,
        expandedPageDataSourceId = expandedPageId,
        bookList = books.take(12).map { book ->
            ExploreDisplayBook(
                id = book.id,
                title = book.title,
                author = book.author,
                coverUri = book.coverUrl.takeIf(String::isNotEmpty)?.let(Uri::parse) ?: Uri.EMPTY
            )
        }
    )
